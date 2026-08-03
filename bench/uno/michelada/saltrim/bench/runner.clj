(ns uno.michelada.saltrim.bench.runner
  "Timing harness. Deliberately dependency-free — criterium would give better
   statistics, but these measurements span tens of milliseconds to seconds and
   are dominated by real work (compiling formulas, draining an executor), not by
   the measurement overhead criterium exists to cancel out.

   What it does do: warm the JIT before measuring, report the MEDIAN of several
   runs rather than a mean (one GC pause should not become the headline), and
   build a fresh sheet per run so nothing is measured against a warmed cache
   that a real user would not have."
  (:require [uno.michelada.saltrim.sheet :as sheet]))

(defn- ms [^long nanos] (/ nanos 1e6))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (if (odd? n)
      (nth v (quot n 2))
      (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0))))

(defn timed
  "Run `f` once, return [elapsed-ms result]."
  [f]
  (let [t0 (System/nanoTime)
        r  (f)]
    [(ms (- (System/nanoTime) t0)) r]))

(defn measure
  "Median elapsed ms of `f` over `runs`, after `warmup` unmeasured runs.
   `f` gets no arguments and its result is discarded — set-up belongs inside
   `f` only if it should be part of the measurement.

   Returns `{:failed <reason>}` instead of a number when `f` throws. A shape
   that cannot be built is a finding, not a reason to abandon the whole run —
   `aggregate` above ~250 cells is exactly that (see doc/bench.md)."
  [{:keys [runs warmup] :or {runs 5 warmup 2}} f]
  (try
    (dotimes [_ warmup] (f))
    (median (repeatedly runs #(first (timed f))))
    (catch Throwable t
      (let [root (loop [e t] (if (.getCause e) (recur (.getCause e)) e))]
        {:failed (str (.getSimpleName (class root))
                      (when-let [m (.getMessage root)]
                        (str ": " (first (clojure.string/split m #" in class file")))))}))))

(defn build!
  "Install `cells` into a fresh sheet one at a time — what a user typing, or the
   .xlsx importer, actually does — then settle. Returns the sheet."
  [cells]
  (let [s (sheet/create-sheet)]
    (doseq [[a raw] cells] (sheet/set-cell! s a raw))
    (sheet/settle! s)
    s))

(defn load!
  "Install `cells` through `load-document!` — the bulk path a sheet load takes.
   Returns the sheet."
  [cells]
  (let [s (sheet/create-sheet)]
    (sheet/load-document! s (into {} (for [[a raw] cells] [a {:value raw}])))
    (sheet/settle! s)
    s))

(defn edit!
  "Time ONE edit on an already-built sheet, including the settle that makes the
   new value readable. This is the number that matters for collaboration: how
   long a peer waits between a keystroke landing and the pushed result."
  [s addr raw]
  (first (timed (fn [] (sheet/set-cell! s addr raw) (sheet/settle! s)))))

(defn read-all
  "Time reading every cell's computed value — what rendering a window does,
   scaled up to the whole sheet."
  [s addrs]
  (first (timed (fn [] (doseq [a addrs] (sheet/value s a))))))

(defn fmt-ms [x]
  (cond (map? x)    "**FAILED**"
        (>= x 1000) (format "%.2fs" (/ x 1000.0))
        (>= x 10)   (format "%.0fms" x)
        :else       (format "%.1fms" x)))

(defn table
  "Render rows as a markdown table with the given headers."
  [headers rows]
  (str "| " (clojure.string/join " | " headers) " |\n"
       "|" (clojure.string/join "|" (repeat (count headers) "---")) "|\n"
       (clojure.string/join
        "\n"
        (for [r rows] (str "| " (clojure.string/join " | " r) " |")))))
