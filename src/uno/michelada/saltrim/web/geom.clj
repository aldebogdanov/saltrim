(ns uno.michelada.saltrim.web.geom
  "Pure grid geometry + small stateless helpers shared across the web layer."
  (:require
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.constants :refer [CW MAX-COLS MAX-ROWS MAX-WIN-COLS MAX-WIN-ROWS RH WIN-COLS WIN-ROWS MIN-COLS MIN-ROWS BUF-COLS BUF-ROWS OVER]]))

(defn view-base
  "[col-base row-base] = top-left index of the rendered (overscanned) window."
  [{:keys [r0 c0]}]
  [(max 0 (- (long c0) OVER)) (max 0 (- (long r0) OVER))])

;; The window a view spans is a PX budget, not a cell count: WIN-COLS/WIN-ROWS
;; are that budget expressed at the DEFAULT cell size, so halving a sheet's
;; default column width doubles the columns needed to cover the same viewport.
(def ^:private WIN-PX-W (* WIN-COLS CW))
(def ^:private WIN-PX-H (* WIN-ROWS RH))

(defn- ceil-div [a b] (long (Math/ceil (/ (double a) (double (max 1 (long b)))))))

;; the client reports 0 until it has measured its viewport -> treat as "unknown"
(defn- as-count [x] (when (and (number? x) (pos? x)) (long x)))

(defn win-dims
  "[wc wr] = how many columns/rows `view` covers. The client measures its own
   viewport against this sheet's cell sizes and reports $wc/$wr; until it has
   (first paint, as-of pages) we fall back to the px budget the constants
   describe, scaled to THIS sheet's default cell size. Clamped, so a client can
   never ask the server to render the whole grid in one window."
  [sh {:keys [wc wr]}]
  [(-> (or (as-count wc) (ceil-div WIN-PX-W (sheet/default-col-w sh))) (max 1) (min MAX-WIN-COLS))
   (-> (or (as-count wr) (ceil-div WIN-PX-H (sheet/default-row-h sh))) (max 1) (min MAX-WIN-ROWS))])

(defn window
  "Cell coords [ci-range ri-range] rendered for `view` = {:r0 :c0 :wc :wr}: the
   columns/rows it covers plus OVER cells of overscan on each side (clamped)."
  [sh view]
  (let [[cb rb] (view-base view)
        [wc wr] (win-dims sh view)]
    [(range cb (min MAX-COLS (+ cb wc OVER OVER)))
     (range rb (min MAX-ROWS (+ rb wr OVER OVER)))]))

;; Axis sizing: columns/rows default to CW/RH but carry sparse per-index px
;; overrides (sheet :cols/:rows). Absolute offset of an index = uniform base
;; plus the (override-base) deltas of every sized index BEFORE it. The same
;; arithmetic runs in /app.js (from the maps in #meta) so client + server agree.

(defn col-w [sh ci] (or (sheet/col-width sh ci) (sheet/default-col-w sh)))
(defn row-h [sh ri] (or (sheet/row-height sh ri) (sheet/default-row-h sh)))

(defn- axis-off
  "Absolute start px of `i` along an axis whose default size is `base` and whose
   sparse overrides are `om` (index -> size)."
  [om base i]
  (reduce-kv (fn [acc k v] (cond-> acc (< (long k) (long i)) (+ (- (long v) base))))
             (* (long i) base) om))

(defn axis-x [sh ci] (axis-off (sheet/col-widths sh) (sheet/default-col-w sh) ci))
(defn axis-y [sh ri] (axis-off (sheet/row-heights sh) (sheet/default-row-h sh) ri))

;; --- colors -------------------------------------------------------------

(defn rgba [hex a]
  (let [h (subs hex 1)]
    (format "rgba(%d,%d,%d,%s)"
            (Integer/parseInt (subs h 0 2) 16)
            (Integer/parseInt (subs h 2 4) 16)
            (Integer/parseInt (subs h 4 6) 16) a)))

(defn- used-max
  "[max-ci max-ri] over non-empty cells (-1 if none)."
  [sh]
  (reduce (fn [[cm rm] a] (let [{:keys [ci ri]} (addr/parse a)]
                            [(max cm ci) (max rm ri)]))
          [-1 -1] (sheet/cells sh)))

(defn total-px
  "Logical scroll extent [w h] px (cells area only, no gutter/header): covers the
   used range and the current view plus a buffer. Just numbers for the custom
   scrollbar — no DOM element is this big."
  [sh {:keys [r0 c0] :as view}]
  (let [[cm rm] (used-max sh)
        [wc wr] (win-dims sh view)
        cols (min MAX-COLS (+ (max MIN-COLS (inc cm) (+ (long c0) wc)) BUF-COLS))
        rows (min MAX-ROWS (+ (max MIN-ROWS (inc rm) (+ (long r0) wr)) BUF-ROWS))]
    ;; total extent = absolute offset of the index just past the buffered range
    ;; (folds in any sparse width/height overrides)
    [(axis-x sh cols) (axis-y sh rows)]))

(defn in-window?
  "Is `addr` inside the window `view` renders? Bounds the cells a session gets
   pushed, so it must span exactly what `window` lays out — hence the same
   view-base (which CLAMPS at the origin, shifting the span right) and the same
   dims. Too tight here and a peer's edit patches an element they never got."
  [sh view addr]
  (let [{:keys [ci ri]} (addr/parse addr)
        [cb rb] (view-base view)
        [wc wr] (win-dims sh view)]
    (and (<= cb ci (+ cb wc OVER OVER -1))
         (<= rb ri (+ rb wr OVER OVER -1)))))

;; --- rendering ----------------------------------------------------------

(defn pretty-err [msg]
  (let [m (str msg)]
    (cond
      (re-find #"cannot be cast.*?(Number|Long|Double|Integer|Ratio|BigDecimal|BigInt)" m)
      "type error (number expected)"
      (re-find #"cannot be cast" m)    "type error"
      (re-find #"Divide by zero" m)    "divide by zero"
      (re-find #"Could not resolve symbol" m) "unknown name or function in the formula"
      (re-find #"circular" m)          "circular reference"
      (re-find #"locked by another" m) "cell is being edited by another collaborator"
      :else m)))

(defn qparam [req k]
  (some->> (:query-string req)
           (re-find (re-pattern (str "(?:^|&)" k "=([^&]+)")))
           second))

;; --- auth routes (login page, OAuth redirects, logout) -------------------

(defn url-encode [s] (java.net.URLEncoder/encode (str s) "UTF-8"))
(defn url-decode [s] (java.net.URLDecoder/decode (str s) "UTF-8"))

