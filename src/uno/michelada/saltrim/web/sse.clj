(ns uno.michelada.saltrim.web.sse
  "SSE plumbing over the Datastar SDK: response/opts, element + signal patches,
   and the WebKit fetch-stream flush tick."
  (:require
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [jsonista.core :as json]
            [hiccup2.core :as h]
            [uno.michelada.saltrim.util :as util]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [starfederation.datastar.clojure.adapter.common :as ac]
            [uno.michelada.saltrim.web.state :refer [sessions*]]))

(def ^:private sse-debug? (some? (System/getenv "SALTRIM_SSE_DEBUG")))

(def ^:private logging-write-profile
  (let [build (ac/->build-event-str)]
    {ac/write! (fn [event-type data-lines opts]
                 (util/log "SSE →" event-type "·"
                           (let [s (str/join " | " data-lines)]
                             (if (> (count s) 240) (str (subs s 0 240) "…") s)))
                 (build event-type data-lines opts))}))

(defn sse-opts
  "Add the SSE-tracing write profile to `->sse-response` opts when SALTRIM_SSE_DEBUG
   is set; otherwise pass them through unchanged (SDK uses its default profile)."
  [opts]
  (cond-> opts sse-debug? (assoc hk/write-profile logging-write-profile)))

(defn sse
  "One-shot SSE response: open, run f with the generator, close. f does the
   patch-elements!/patch-signals! calls."
  [req f]
  (hk/->sse-response req (sse-opts {hk/on-open (fn [gen] (f gen) (d*/close-sse! gen))})))

;; --- Safari/WebKit fetch-stream flush (server-side, WebKit-only) ---------
;; WebKit delivers a fetch() response body to JS in coalesced lumps and holds
;; the trailing bytes (below an internal threshold) until a LATER, separate
;; network write arrives — and drops them for good if nothing does. Datastar's
;; @get stream reads over fetch(), so a collaboration push to a peer that isn't
;; followed by more traffic never lands in Safari (verified: trailing edit lost
;; ~40% of the time, even after seconds). Chromium/Firefox stream incrementally
;; and are unaffected; the editor's own actions answer over one-shot @post
;; responses that CLOSE (=flush), so only peer broadcasts are affected.
;;
;; Padding the push itself does NOT help — same-instant bytes coalesce into the
;; same held lump (measured: 16 KB appended still lost 4/6). What reliably
;; triggers delivery is a *time-separated* follow-up write. So a few ms after a
;; broadcast to a WebKit peer we send a raw SSE comment (ignored by the client
;; parser — its colon is at column 0): that extra read cycle flushes the push.
;; ~30 ms is imperceptible yet reliable (0 lost across 24 edits). It is gated by
;; User-Agent (only WebKit pays) and coalesced to one pending tick per session,
;; so idle and non-WebKit streams stay completely silent — this is not a
;; heartbeat.
(def ^:private webkit-flush-ms 30)

(defonce ^:private flush-pool
  (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "saltrim-webkit-flush")
                         (.setDaemon true))))))

(defonce ^:private flush-pending (java.util.concurrent.ConcurrentHashMap.))

(defn webkit-ua?
  "True for WebKit engines (Safari desktop/iOS, iOS browsers) but not
   Chrome/Chromium/Edge — i.e. the ones whose fetch() SSE buffering needs the
   flush tick. iOS Chrome (\"CriOS\", no \"Chrome\") is WebKit and matches."
  [ua]
  (let [ua (str ua)]
    (and (str/includes? ua "AppleWebKit")
         (not (str/includes? ua "Chrome"))
         (not (str/includes? ua "Chromium")))))

(defn- flush-tick!
  "Send a raw SSE comment to sid's stream — a time-separated write that flushes
   WebKit's held fetch buffer. The colon-at-column-0 line is ignored by the
   Datastar client parser (no DOM/signal effect). The SDK has no comment
   primitive, so we write the http-kit channel directly, under the gen's lock."
  [sid]
  (when-let [g (:gen (@sessions* sid))]
    (when sse-debug? (util/log "SSE →" ": flush" "·" sid))
    (try (d*/lock-sse! g
           (http/send! (.ch ^starfederation.datastar.clojure.adapter.http_kit.impl.SSEGenerator g)
                       ": flush\n" false))
         (catch Throwable _))))

(defn webkit-flush!
  "Schedule a flush tick ~webkit-flush-ms after a broadcast to a WebKit peer,
   coalescing to at most one pending tick per session (so a burst of pushes
   costs one trailing flush, and idle streams none)."
  [sid]
  (when (nil? (.putIfAbsent flush-pending sid Boolean/TRUE))
    (.schedule flush-pool
               ^Runnable (fn [] (.remove flush-pending sid) (flush-tick! sid))
               (long webkit-flush-ms) java.util.concurrent.TimeUnit/MILLISECONDS)))

(defn patch-inner!
  "Replace inner HTML of `selector` with `html`. Blank `html` (e.g. an empty
   #self / #peers overlay) would make the SDK emit a `datastar-patch-elements`
   event with NO `elements` line, which Datastar's client parser rejects
   (\"Error in input stream\") — aborting the SSE stream and reconnect-storming.
   So clear with an inert HTML comment instead: a valid, non-empty `elements`
   payload that still empties the element visually."
  [gen selector html]
  (let [html (if (str/blank? html) "<!-- -->" html)]
    (d*/patch-elements! gen html {d*/selector selector d*/patch-mode d*/pm-inner})))

;; --- toasts -------------------------------------------------------------
;; A toast is an ELEMENT the server appends to the page's `#toasts` list, not a
;; signal written into a slot. As signals, `:err` and `:info` were two
;; single-value channels over one screen corner: a second message overwrote the
;; first before anyone had read it, and the two had to blank each other out to
;; avoid overlapping. Appended cards stack instead — every message gets its own.
;;
;; Each card carries its whole life in its own markup:
;;
;;   click    — `data-on:click="el.remove()"`, on every card.
;;   timeout  — an `info` card also runs a CSS animation whose last keyframe
;;              fades it out; `data-on:animationend` then removes the node. An
;;              `err` card has no such animation and so waits to be
;;              acknowledged: missing a failure costs more than missing a
;;              success.
;;
;; So this is fire-and-forget. Nothing server-side tracks a card, times it, or
;; ever has to take it back — no timers, no per-session bookkeeping, and no
;; client code beyond the two Datastar expressions above.

(defonce ^:private toast-n (java.util.concurrent.atomic.AtomicLong. 0))

(def ^:private toast-list
  "The `<ul>` in `web.render/page` that cards are appended to."
  "#toasts")

(defn- toast-html
  "One card. The message is ordinary hiccup content, so it is HTML-escaped —
   it routinely carries user text (a formula, a sheet name, an exception)."
  [kind msg]
  (str (h/html
        [:li (cond-> {:id            (str "toast" (.incrementAndGet toast-n))
                      :class         (str "toast " (name kind))
                      :title         "click to dismiss"
                      :data-on:click "el.remove()"}
               (= :info kind) (assoc :data-on:animationend "el.remove()"))
         msg])))

(defn toast!
  "Append a `:err` / `:info` card to the page's toast list."
  [gen kind msg]
  (d*/patch-elements! gen (toast-html kind msg)
                      {d*/selector toast-list d*/patch-mode d*/pm-append}))

(defn signals!
  "Patch signals — except `:err` and `:info`, which are not signals at all any
   more: a non-blank one raises its own toast card (see above) and never reaches
   the client as a value. A BLANK one is a no-op, where it used to clear the
   slot; there is no slot to clear, and a card belongs to whoever it was sent to.

   This stays the one choke point every handler patches through, so all ~90 call
   sites keep passing `{:err …}` / `{:info …}` exactly as before."
  [gen m]
  (doseq [kind [:err :info]
          :let  [msg (str (get m kind))]
          :when (not (str/blank? msg))]
    (toast! gen kind msg))
  (let [sigs (dissoc m :err :info)]
    ;; an all-toast call has no signals left to send — but a caller that
    ;; deliberately passed nothing (the /stream open flush) still gets its patch
    (when (or (seq sigs) (empty? m))
      (d*/patch-signals! gen (json/write-value-as-string sigs)))))

;; --- handlers -----------------------------------------------------------

(defn read-signals [req]
  (json/read-value (d*/get-signals req) json/keyword-keys-object-mapper))

