(ns uno.michelada.saltrim.runtime
  "Runtime support referenced by compiled cell bodies, resolving against the
   CURRENT execution context's metadata (works on executor threads).

   Uniform cell model:
   - every cell has a PUBLIC Spin in :registry -> `lookup`      (used by `await`)
   - literal cells also have an editable SignalRef in :vals -> `lookup-val`
     (used by the literal wrapper spin's `track`)

   Dynamic refs (`$(expr)`): the address is a RUNTIME value, so resolution,
   validation, cycle checking and dependency recording all happen here —
   `resolve-dyn` parses the expression's result into addresses, `lookup-dyn`
   guards + records + resolves each one (the :dyn metadata atom is the sheet's
   dynamic-edge registry {parent-addr #{target-addrs}}, the runtime complement
   of the static :deps in :meta)."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [uno.michelada.saltrim.addr :as addr]))

(defn- meta-get [k]
  (-> (ec/current-execution-context) ctx/get-metadata (get k)))

(defn lookup
  "Address -> public Spin for the current sheet. A referenced EMPTY cell (no
   registry entry) resolves to a fresh constant nil-Spin, so a ref to a blank cell
   yields nil (the stdlib aggregates filter nils) rather than erroring. Fresh per
   call so two blank refs in one formula await DISTINCT nodes — a shared node would
   trip Spindel's await-same-node-twice glitch. Filling the blank later is a
   structural change, so `sheet/set-cell!` rebuilds dependents to capture the real
   node (reactivity preserved)."
  [addr]
  (or (get @(meta-get :registry) addr)
      (spin nil)))

(defn lookup-val
  "Address -> editable SignalRef (literal cells only)."
  [addr]
  (or (get @(meta-get :vals) addr)
      (throw (ex-info "no value signal" {:addr addr}))))

;; --- dynamic refs ---------------------------------------------------------

(def MAX-DYN-RANGE
  "Cap on the cell count of a dynamically resolved range — an unbounded
   `$(str \"A1:ZZ\" huge)` would install that many awaits in one body."
  10000)

(defn- canon-addr
  "Canonical form of one address string (\"a01\" -> \"A1\"), or nil if it isn't
   a usable cell address (bad syntax, row 0, unparseable digits)."
  [s]
  (when (addr/valid? s)
    (try (let [{:keys [ci ri]} (addr/parse s)]
           (when (and (>= ci 0) (>= ri 0))
             (addr/make ci ri)))
         (catch Exception _ nil))))

(defn resolve-dyn
  "Parse the runtime RESULT of a `$(expr)` into concrete addresses.
   \"A5\" (any case, leading zeros tolerated) -> {:addrs [\"A5\"] :single? true};
   \"A1:B3\" -> {:addrs [row-major rectangle] :single? false}, capped at
   MAX-DYN-RANGE cells. Anything else throws (bad type, bad syntax, row 0,
   malformed range) — surfaced by the sheet layer as {:error …} -> #ERR."
  [raw]
  (let [s   (str raw)
        bad (fn [& [why]]
              (throw (ex-info (str "bad dynamic address " (pr-str raw)
                                   (when why (str " (" why ")")))
                              {:raw raw})))]
    (if (str/includes? s ":")
      (let [parts (str/split s #":" -1)
            [a b] (map canon-addr parts)]
        (when-not (and (= 2 (count parts)) a b) (bad "not a range"))
        (let [{ca :ci ra :ri} (addr/parse a)
              {cb :ci rb :ri} (addr/parse b)
              n (* (inc (abs (- ca cb))) (inc (abs (- ra rb))))]
          (when (> n MAX-DYN-RANGE)
            (throw (ex-info (str "dynamic range too large (" n " cells, max "
                                 MAX-DYN-RANGE ")")
                            {:raw raw :cells n})))
          {:addrs (addr/range-cells a b) :single? false}))
      (if-let [a (canon-addr s)]
        {:addrs [a] :single? true}
        (bad)))))

(defn const-spin
  "A fresh constant Spin over a plain value — awaited in place of a registry
   node the body has ALREADY awaited this run (a shared node awaited twice in
   one body glitches; a fresh constant node is inert and safe). See spike 07."
  [v]
  (spin v))

(defn- dyn-cycle?
  "Would reading `target` from inside `self`'s body close a cycle? True when
   `target` reaches `self` over the COMBINED edges: static :deps (from the
   sheet's :meta) plus recorded dynamic edges (`dyn`)."
  [meta dyn self target]
  (or (= target self)
      (loop [stack [target] seen #{}]
        (if-let [c (peek stack)]
          (let [stack (pop stack)]
            (cond
              (= c self) true
              (seen c)   (recur stack seen)
              :else      (recur (into stack (concat (get-in meta [c :deps])
                                                    (get dyn c)))
                                (conj seen c))))
          false))))

(defn lookup-dyn
  "Resolve ONE dynamically computed address from inside `self`'s body.
   `vm` maps addresses this body has ALREADY awaited this run (static deps +
   earlier dynamic results) to their values — a hit returns a fresh const-spin
   of the known value instead of the shared registry node (double-await guard;
   the edge is already recorded statically or by the earlier site).
   Otherwise: cycle-check + edge-record in ONE swap! on the :dyn registry (the
   update fn throws on a cycle, aborting the swap — and serializes two freshly
   mutual dyn cells racing on the executor: the loser sees the winner's edge
   and errors instead of deadlocking), then return the target's public Spin
   (or a fresh nil-spin for a blank cell, as `lookup` does)."
  [self addr vm]
  (if (contains? vm addr)
    (const-spin (get vm addr))
    (let [meta-atom (meta-get :meta)]
      (swap! (meta-get :dyn)
             (fn [dyn]
               (when (dyn-cycle? @meta-atom dyn self addr)
                 (throw (ex-info (str "circular reference (dynamic " self
                                      " -> " addr ")")
                                 {:self self :target addr})))
               (update dyn self (fnil conj #{}) addr)))
      (or (get @(meta-get :registry) addr)
          (spin nil)))))
