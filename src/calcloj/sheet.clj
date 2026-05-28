(ns calcloj.sheet
  "Cell registry over a Spindel execution context — uniform Spin model.

   Per sheet (all in ctx metadata so compiled bodies resolve cells):
   - :registry {addr -> public Spin}      every non-blank cell (lookup / await)
   - :vals     {addr -> SignalRef}         editable input of literal cells
   - :meta     {addr -> {:raw :kind :deps}} document layer (raw text, etc.)

   Literal cell  = SignalRef (value) + a wrapper Spin (deref (track sig)).
   Formula cell  = Spin compiled from an `=`-expression; refs other cells via
                   `await`, so formula->formula works."
  (:require [clojure.string :as str]
            [calcloj.formula :as formula]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(defn create-sheet []
  (let [registry (atom {})
        vals     (atom {})
        meta     (atom {})
        rt       (ctx/create-execution-context
                  {:metadata {:registry registry :vals vals}})]
    {:rt rt :registry registry :vals vals :meta meta}))

(defn- classify [raw]
  (let [t (some-> raw str/trim)]
    (cond (or (nil? t) (= "" t))   :blank
          (str/starts-with? t "=") :formula
          :else                    :literal)))

(defn- parse-literal [raw]
  (let [t (str/trim raw)]
    (cond
      (re-matches #"[-+]?\d+" t)               (Long/parseLong t)
      (re-matches #"[-+]?\d*\.\d+([eE]\d+)?" t) (Double/parseDouble t)
      :else                                    t)))

(defn- rdeps
  "Addresses whose formula references `addr`."
  [meta addr]
  (keep (fn [[a m]] (when (contains? (:deps m) addr) a)) @meta))

(defn- write-cell!
  "Local update of one cell. Returns true if addr's PUBLIC spin object was
   created/replaced/removed (structural change -> dependents must rebuild)."
  [{:keys [registry vals meta]} addr raw]
  (let [prev-kind (get-in @meta [addr :kind])
        old-spin  (get @registry addr)]
    (case (classify raw)
      :blank
      (do (when old-spin (spin-core/cleanup-spin! old-spin))
          (swap! registry dissoc addr)
          (swap! vals dissoc addr)
          (swap! meta dissoc addr)
          true)

      :literal
      (let [v (parse-literal raw)]
        (if-let [vs (get @vals addr)]
          (reset! vs v)                              ; value-only: propagates
          (let [vs (sig/->SignalRef (str "val:" addr) v)]
            (sig/ensure-signal-initialized! vs)
            (swap! vals assoc addr vs)))
        (swap! meta assoc addr {:raw raw :kind :literal})
        (if (= prev-kind :literal)
          false                                       ; spin unchanged
          (do (when old-spin (spin-core/cleanup-spin! old-spin))
              (swap! registry assoc addr (formula/compile-literal-wrapper addr))
              true)))

      :formula
      (let [{:keys [form deps]} (formula/parse (subs (str/trim raw) 1))
            sp (formula/compile form)]
        (when old-spin (spin-core/cleanup-spin! old-spin))
        (swap! registry assoc addr sp)
        (swap! meta assoc addr {:raw raw :kind :formula :deps deps})
        true))))

(defn set-cell!
  "Set cell `addr` from raw input. When a cell's public spin is replaced
   (structural change), transitively rebuilds dependents so they re-capture
   the new node. Value-only edits skip the rebuild (signal propagates). The
   visited set guards against cycles. Returns the sheet."
  [{:keys [rt meta] :as sheet} addr raw]
  (binding [ec/*execution-context* rt]
    (let [visited (volatile! #{})]
      (letfn [(go [a r]
                (when-not (contains? @visited a)
                  (vswap! visited conj a)
                  (when (write-cell! sheet a r)
                    (doseq [d (rdeps meta a)]
                      (go d (get-in @meta [d :raw]))))))]
        (go addr raw))))
  sheet)

(defn dependents*
  "Transitive set of addresses whose formulas reference `addr` (reverse-dep
   closure), excluding `addr` itself. These are the cells whose value may
   change when `addr` changes — the set to re-render."
  [{:keys [meta]} addr]
  (let [m @meta]
    (loop [seen #{} frontier [addr]]
      (if-let [a (first frontier)]
        (let [ds  (keep (fn [[x mm]] (when (contains? (:deps mm) a) x)) m)
              new (remove #(or (= % addr) (contains? seen %)) ds)]
          (recur (into seen new) (into (subvec (vec frontier) 1) new)))
        seen))))

(defn settle! [{:keys [rt]}]
  (simple/await-drain-complete! rt :timeout-ms 5000))

(defn value
  "Current computed value of `addr`, or nil if blank. Errors -> {:error msg}."
  [{:keys [rt registry]} addr]
  (when-let [ref (get @registry addr)]
    (binding [ec/*execution-context* rt]
      (try @ref (catch Exception e {:error (.getMessage e)})))))

(defn raw   [{:keys [meta]} addr] (get-in @meta [addr :raw]))
(defn kind  [{:keys [meta]} addr] (get-in @meta [addr :kind]))
(defn cells [{:keys [meta]}] (keys @meta))
