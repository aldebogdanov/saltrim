(ns calcloj.web
  "Windowed spreadsheet grid over the sheet engine, driven by Datastar.

   Viewport: a scroll container holds a full-size spacer (real scrollbar for a
   huge logical sheet) and an absolutely-positioned window of only the visible
   cells. Scroll -> post first row/col -> server re-renders the window and
   SSE-patches the cells + sticky headers. Empty cells cost nothing (absent
   from the registry, no spin).

   Formula bar: wide input mirroring the selected cell's SOURCE (value or
   formula). Focusing a cell sets $sel/$bar; editing the bar posts to that cell.

   Run:  clj -M:web   then open http://localhost:8080"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [calcloj.addr :as addr]
            [calcloj.sheet :as sheet]))

;; --- geometry -----------------------------------------------------------

(def ^:private CW 112)            ; cell width  px
(def ^:private RH 26)             ; cell height px
(def ^:private GUT 48)            ; row-header gutter px
(def ^:private HDR 26)            ; col-header height px
(def ^:private TOTAL-COLS 1000)   ; logical sheet size
(def ^:private TOTAL-ROWS 100000)
(def ^:private WIN-COLS 16)       ; window size (+overscan)
(def ^:private WIN-ROWS 34)
(def ^:private OVER 2)            ; overscan cells

(defn- xpos [ci] (+ GUT (* ci CW)))   ; cells/col-headers: left
(defn- ypos [ri] (* ri RH))           ; cells/row-headers: top (col strip is separate)

(defn- window
  "Visible cell coords [ci-range ri-range] for first row/col r0 c0 (clamped)."
  [r0 c0]
  (let [c0 (max 0 (- (long c0) OVER))
        r0 (max 0 (- (long r0) OVER))]
    [(range c0 (min TOTAL-COLS (+ c0 WIN-COLS)))
     (range r0 (min TOTAL-ROWS (+ r0 WIN-ROWS)))]))

;; --- state --------------------------------------------------------------

(defonce ^:private sheet* (atom nil))
(defn- the-sheet [] (or @sheet* (reset! sheet* (sheet/create-sheet))))
(defonce ^:private view* (atom {:r0 0 :c0 0}))

(defn- in-window? [addr]
  (let [{:keys [ci ri]} (addr/parse addr)
        {:keys [r0 c0]} @view*]
    (and (<= (- (long c0) OVER) ci (+ (long c0) WIN-COLS))
         (<= (- (long r0) OVER) ri (+ (long r0) WIN-ROWS)))))

;; --- rendering ----------------------------------------------------------

(defn- display [sh a]
  (let [v (sheet/value sh a)]
    (cond (nil? v) "" (map? v) "#ERR" :else (str v))))

(defn- cell-id [a] (str "c_" a))

(defn- cell-input
  "Minimal per-cell HTML: class (shared CSS) + position only. Focus/blur/change
   are delegated on #cells (handlers below), so cells stay tiny -> small SSE."
  [sh a ci ri]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)]
    [:input {:id (cell-id a) :class "cell"
             :value disp :data-raw raw :data-val disp
             :style (format "left:%dpx;top:%dpx" (xpos ci) (ypos ri))}]))

(defn- cells-html [sh cis ris]
  (str (h/html (for [ri ris ci cis] (cell-input sh (addr/make ci ri) ci ri)))))

(defn- colhead-html [cis]
  (str (h/html
        (for [ci cis]
          [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                     "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                     "border:1px solid #e0e0e0;font:12px sans-serif;")
                                (xpos ci) CW HDR HDR)}
           (addr/idx->col ci)]))))

(defn- rowhead-html [ris]
  (str (h/html
        (for [ri ris]
          [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                     "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                     "border:1px solid #e0e0e0;font:12px sans-serif;")
                                (ypos ri) GUT RH RH)}
           (inc ri)]))))

(defn- grid-layers [sh]
  (let [{:keys [r0 c0]} @view*
        [cis ris] (window r0 c0)
        full-w (+ GUT (* TOTAL-COLS CW))]
    (h/html
     ;; sticky column-header strip (pins to top); contains the corner + labels
     [:div {:id "colstrip"
            :style (format (str "position:sticky;top:0;z-index:2;height:%dpx;width:%dpx;"
                                "background:#f3f3f3;") HDR full-w)}
      [:div {:id "corner"
             :style (format (str "position:sticky;left:0;z-index:3;width:%dpx;height:%dpx;"
                                 "background:#e8e8e8;display:inline-block;") GUT HDR)}]
      [:div {:id "colhead"} (h/raw (colhead-html cis))]]
     ;; body: full-height spacer for real scrollbar; row-header strip + cells
     [:div {:id "space"
            :style (format "position:relative;width:%dpx;height:%dpx;"
                           full-w (* TOTAL-ROWS RH))}
      [:div {:id "rowstrip"
             :style (format (str "position:sticky;left:0;z-index:1;width:%dpx;height:%dpx;"
                                 "background:#f3f3f3;float:left;") GUT (* TOTAL-ROWS RH))}
       [:div {:id "rowhead"} (h/raw (rowhead-html ris))]]
      ;; event delegation: handlers live on #cells (persist across inner patches);
      ;; focus/blur don't bubble -> use focusin/focusout. id "c_A1" -> addr slice(2).
      [:div {:id "cells"
             :data-on:focusin
             (str "evt.target.classList.contains('cell') && "
                  "($sel=evt.target.id.slice(2), $bar=evt.target.dataset.raw, "
                  "evt.target.value=evt.target.dataset.raw)")
             :data-on:focusout
             "evt.target.classList.contains('cell') && (evt.target.value=evt.target.dataset.val)"
             :data-on:change
             (str "evt.target.classList.contains('cell') && "
                  "($cell=evt.target.id.slice(2), $v=evt.target.value, $bar=$v, @post('/cell'))")}
       (h/raw (cells-html sh cis ris))]])))

(defn- page [sh]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "calcloj"]
      [:style (h/raw (format (str "input.cell{position:absolute;width:%dpx;height:%dpx;"
                                  "box-sizing:border-box;border:1px solid #ddd;"
                                  "padding:2px 4px;font:13px monospace;}")
                             (- CW 1) (- RH 1)))]
      [:script {:type "module" :src "/datastar.js"}]]
     [:body {:data-signals "{cell:'', v:'', err:'', sel:'', bar:'', r0:0, c0:0}"
             :style "font-family:sans-serif;margin:0;padding:.6rem;"}
      [:div {:id "toast" :data-show "$err != ''" :data-text "$err"
             :data-on:click "$err=''"
             :style (str "position:fixed;top:1rem;right:1rem;max-width:26rem;background:#c0392b;"
                         "color:#fff;padding:.6rem .9rem;border-radius:6px;font:13px sans-serif;"
                         "cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.3);z-index:20;")}]
      ;; formula bar
      [:div {:style "display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;"}
       [:strong {:style "min-width:3.5rem;font:13px monospace;"
                 :data-text "$sel || '—'"}]
       [:input {:id "fbar" :data-bind:bar "" :placeholder "value or =formula"
                :data-on:keydown "evt.key==='Enter' && ($cell=$sel, $v=$bar, @post('/cell'))"
                :data-on:blur "$cell=$sel, $v=$bar, @post('/cell')"
                :style (str "flex:1;font:13px monospace;padding:5px 8px;border:1px solid #bbb;"
                            "border-radius:4px;")}]]
      ;; scroll viewport
      [:div {:id "scroll"
             :data-on:scroll__debounce.120ms
             (format "$r0=Math.floor(el.scrollTop/%d); $c0=Math.floor(el.scrollLeft/%d); @post('/view')" RH CW)
             :style "height:78vh;overflow:auto;border:1px solid #ccc;position:relative;"}
       (grid-layers sh)]]])))

;; --- SSE ----------------------------------------------------------------

(defn- patch-outer [html-strs]
  (str "event: datastar-patch-elements\n"
       "data: mode outer\n"
       (apply str (map #(str "data: elements " % "\n") html-strs))
       "\n"))

(defn- patch-inner [selector html]
  (str "event: datastar-patch-elements\n"
       "data: mode inner\n"
       "data: selector " selector "\n"
       "data: elements " html "\n\n"))

(defn- signals-event [m]
  (str "event: datastar-patch-signals\n"
       "data: signals " (json/write-value-as-string m) "\n\n"))

(defn- sse-response [body]
  {:status 200
   :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
   :body body})

;; --- handlers -----------------------------------------------------------

(defn- read-json [req]
  (when-let [b (:body req)]
    (json/read-value (slurp b) json/keyword-keys-object-mapper)))

(def ^:private edit-lock (Object.))

(defn- pretty-err [msg]
  (let [m (str msg)]
    (cond
      (re-find #"cannot be cast" m)    "type error (number expected)"
      (re-find #"Divide by zero" m)    "divide by zero"
      (re-find #"unknown cell" m)      "reference to empty cell"
      (re-find #"disallowed symbol" m) "not allowed in a formula"
      (re-find #"circular" m)          "circular reference"
      :else m)))

(defn- handle-cell [req]
  (let [sh (the-sheet)
        {:keys [cell v]} (read-json req)]
    (if (addr/valid? cell)
      (locking edit-lock
        (try
          (sheet/set-cell! sh cell (str v))
          (sheet/settle! sh)
          (let [affected (cons cell (sort (sheet/dependents* sh cell)))
                visible  (filter in-window? affected)   ; only patch on-screen
                errs (keep (fn [a]
                             (when-let [e (:error (sheet/value sh a))]
                               (str a ": " (pretty-err e))))
                           affected)]
            (sse-response
             (str (when (seq visible)
                    (patch-outer (map #(str (h/html (cell-input sh %
                                                                (:ci (addr/parse %))
                                                                (:ri (addr/parse %)))))
                                      visible)))
                  (signals-event {:err (if (seq errs) (str/join "; " errs) "")}))))
          (catch Throwable e
            (sse-response (signals-event {:err (str cell ": " (pretty-err (.getMessage e)))})))))
      (sse-response "\n"))))

(defn- handle-view [req]
  (let [sh (the-sheet)
        {:keys [r0 c0]} (read-json req)
        r0 (max 0 (long (or r0 0)))
        c0 (max 0 (long (or c0 0)))]
    (reset! view* {:r0 r0 :c0 c0})
    (let [[cis ris] (window r0 c0)]
      (sse-response
       (str (patch-inner "#cells"   (cells-html sh cis ris))
            (patch-inner "#colhead" (colhead-html cis))
            (patch-inner "#rowhead" (rowhead-html ris)))))))

(defn- app [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"]            (do (reset! view* {:r0 0 :c0 0})  ; browser scroll starts at 0
                              {:status 200 :headers {"Content-Type" "text/html"}
                               :body (page (the-sheet))})
    [:get "/datastar.js"] (if-let [r (io/resource "public/datastar.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no datastar"})
    [:post "/cell"]       (handle-cell req)
    [:post "/view"]       (handle-view req)
    {:status 404 :body "not found"}))

(defonce ^:private server* (atom nil))

(defn start! [& [port]]
  (when @server* (@server*))
  (reset! server* (http/run-server #'app {:port (or port 8080)}))
  (println "calcloj on http://localhost:" (or port 8080)))

(defn -main [& _] (start!) @(promise))
