(ns uno.michelada.saltrim.dom-stub
  "The smallest DOM `app.cljs` can load against, so the client engine can be
   tested in node with no browser and no npm.

   Two reasons this exists rather than a headless browser:

   1. `app.cljs` runs four `addEventListener` calls at the TOP LEVEL (datastar-ready,
      datastar-fetch, pagehide, visibilitychange). Requiring the namespace at all
      needs `document` and `window` to be objects, or the bundle throws on load.
   2. The interesting half of the client is not the DOM writing — it is the
      geometry, the selection algebra and WHICH `sr-*` bridge event comes out.
      That bridge is the whole client->server contract (no hidden trigger
      buttons, nothing in HTML calls a cljs function), so a stub that records
      dispatched events tests the contract directly.

   LOAD ORDER MATTERS: this namespace installs the globals as a side effect of
   being loaded, so every test namespace that touches `app` must `:require` it
   FIRST. (`:preloads` would be the explicit hook, but the compiler only honours
   it under `:optimizations :none`.)

   What is deliberately NOT here: layout. Every element reports the sizes the
   test sets on it; nothing is measured. That is fine — the client never asks the
   DOM for cell geometry either, it computes it from #meta's data-*."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.constants :refer [CW RH]]))

;; --- recorded state ---------------------------------------------------------

(defonce ^:private els (atom {}))    ; id -> fake element
(defonce ^:private log (atom []))    ; sr-* events dispatched on window, in order
(defonce ^:private doc-ls (atom {})) ; document-level listeners, by type
(defonce ^:private win-ls (atom {})) ; window-level listeners, by type

(defn events
  "Every event dispatched on window since the last `clear-events!`, in order, as
   {:type \"sr-sel\" :detail {:ranges \"A1:B2\"}}."
  []
  @log)

(defn clear-events! [] (reset! log []))

(defn event
  "The LAST event of type `nm`, or nil. Bridge events are last-write-wins from
   the server's point of view too, so the last one is the one that mattered."
  [nm]
  (last (filter #(= nm (:type %)) @log)))


;; --- fake element -----------------------------------------------------------

(defn- attr-names
  "The attribute names an attribute selector mentions: \"[data-copy],[data-src]\"
   -> (\"data-copy\" \"data-src\"). Enough for the two selectors app.cljs uses."
  [sel]
  (map #(str/replace % #"[\[\]]" "") (str/split sel #",")))

(defn- mk-el [id]
  (let [ls    (atom {})
        attrs (atom {})
        cls   (atom #{})
        e     (js-obj)]
    (doto e
      (aset "id" id)
      (aset "dataset" (js-obj))
      (aset "style" (js-obj))
      (aset "innerHTML" "")
      (aset "textContent" "")
      (aset "value" "")
      (aset "clientWidth" 0)
      (aset "clientHeight" 0)
      (aset "__ls" ls)
      (aset "__attrs" attrs)
      (aset "__cls" cls)
      (aset "classList" (js-obj "contains" (fn [c] (contains? @cls c))
                                "add"      (fn [c] (swap! cls conj c) nil)
                                "remove"   (fn [c] (swap! cls disj c) nil)))
      (aset "addEventListener" (fn [t f & _] (swap! ls update t (fnil conj []) f) nil))
      (aset "removeEventListener" (fn [t f & _] (swap! ls update t #(vec (remove #{f} %))) nil))
      (aset "getAttribute" (fn [n] (get @attrs n nil)))
      (aset "setAttribute" (fn [n v] (swap! attrs assoc n v) nil))
      (aset "getBoundingClientRect" (fn [] #js {:left 0 :top 0}))
      (aset "focus" (fn [] nil))
      (aset "select" (fn [] nil))
      (aset "remove" (fn [] nil))
      ;; no parent chain in the stub: an element is its own nearest match
      (aset "closest" (fn [sel] (when (some #(contains? @attrs %) (attr-names sel)) e))))
    e))

(defn el
  "The fake element with this id, created on first ask."
  [id]
  (or (get @els id)
      (let [e (mk-el id)] (swap! els assoc id e) e)))

(defn classed!
  "Give `e` a CSS class (what `.classList.contains` answers on)."
  [e c] ((aget e "classList" "add") c) e)

(defn attr! [e n v] ((aget e "setAttribute") n v) e)

(defn cell-el
  "A grid cell div as the server renders it: id \"c-B3\", class \"cell\"."
  [a] (-> (el (str "c-" a)) (classed! "cell")))

(defn fire!
  "Run every listener `app.cljs` registered on `e` for `type`."
  [e type ev] (doseq [f (get @(aget e "__ls") type)] (f ev)) nil)

(defn fire-doc! [type ev] (doseq [f (get @doc-ls type)] (f ev)) nil)

(defn listeners
  "Which event types `app.cljs` bound on `e` — the wiring, as a set."
  [e] (set (keys @(aget e "__ls"))))

(defn doc-listeners [] (set (keys @doc-ls)))

;; --- #meta ------------------------------------------------------------------

(defn- ->attr [v]
  (cond (string? v) v
        (coll? v)   (js/JSON.stringify (clj->js v))
        :else       (str v)))

(defn set-meta!
  "Write the render window's geometry onto #meta the way the server does. Maps
   (colw/rowh/merges) are JSON-encoded, exactly as web.clj emits them."
  [m]
  (let [ds (.-dataset (el "meta"))]
    (doseq [[k v] m] (aset ds (name k) (->attr v))))
  nil)

(defn meta!
  "#meta as a freshly loaded page has it, with `m` overriding: origin window, the
   sheet's default cell size, no per-index overrides, no merges."
  [m]
  (set-meta! (merge {:tw 100000 :th 100000 :cb 0 :rb 0
                     :dcw CW :drh RH :colw {} :rowh {} :merges {}}
                    m)))

(defn set-viewport!
  "How big the browser says #cellclip is. The only measurement the client takes."
  [w h]
  (doto (el "cellclip") (aset "clientWidth" w) (aset "clientHeight" h))
  nil)

;; --- the globals ------------------------------------------------------------
;; Every id app.cljs looks up at init time must exist up front: it caches the
;; element in a closure, so one created later would never receive its listeners.

(def ^:private known-ids
  ["viewport" "meta" "cellclip" "addrbox" "editor" "ctl" "streamer"
   "cells" "selrange" "self" "peers" "editlayer" "colstrip" "rowstrip"
   "vbar" "vthumb" "hbar" "hthumb" "rzguide"])

(defn- install! []
  (doseq [id known-ids] (el id))
  (aset js/globalThis "document"
        (js-obj "getElementById"      (fn [id] (get @els id))
                "addEventListener"    (fn [t f & _] (swap! doc-ls update t (fnil conj []) f) nil)
                "removeEventListener" (fn [t f & _] (swap! doc-ls update t #(vec (remove #{f} %))) nil)
                "activeElement"       nil
                "visibilityState"     "visible"))
  (aset js/globalThis "window"
        (js-obj "addEventListener" (fn [t f & _] (swap! win-ls update t (fnil conj []) f) nil)
                "dispatchEvent"    (fn [ev]
                                     (swap! log conj {:type   (.-type ev)
                                                      :detail (js->clj (.-detail ev) :keywordize-keys true)})
                                     (doseq [f (get @win-ls (.-type ev))] (f ev))
                                     true)))
  ;; app.cljs watches #meta for attribute changes; nothing in a test mutates it
  ;; behind the client's back, so observing is a no-op.
  (aset js/globalThis "MutationObserver"
        (fn [_cb] (js-obj "observe" (fn [& _] nil) "disconnect" (fn [] nil))))
  nil)

(defonce ^:private installed (install!))
