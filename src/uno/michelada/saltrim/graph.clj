(ns uno.michelada.saltrim.graph
  "Pure layout for the cell dependency-graph view. The engine already tracks each
   formula's deps (`sheet/deps`) and their reverse (`sheet/dependents*`); this
   turns a forward deps-map into a layered DAG ready to render. No engine, no db
   — just data, so it's easy to test.")

(defn build
  "From `deps-map` {addr #{addrs it references}} build a layered DAG.
   Edges are `[from to]` = `from` feeds `to` (to's formula reads from). Nodes =
   every addr in any edge. `layer` = longest-path depth from a source (a node
   with no incoming edge is layer 0), so each node sits to the RIGHT of
   everything it depends on. Returns {:nodes #{…} :edges [[from to]…]
   :layer {addr n}}. The graph is a DAG — value cycles are rejected by the engine
   before they can be stored. Dynamic-ref edges can transiently disagree (they
   are recorded by concurrent recomputes), so the longest-path walk carries a
   path guard: a back edge contributes layer 0 instead of recursing forever."
  [deps-map]
  (let [edges (vec (for [[a ds] deps-map, d ds] [d a]))   ; d -> a
        nodes (set (mapcat identity edges))
        preds (reduce (fn [m [f t]] (update m t (fnil conj #{}) f)) {} edges)
        layer (let [seen (atom {})]
                (letfn [(d [n path]
                          (or (@seen n)
                              (if (contains? path n)
                                0                          ; back edge: cut, don't recurse
                                (let [path (conj path n)
                                      v (if-let [ps (seq (preds n))]
                                          (inc (long (apply max (map #(d % path) ps))))
                                          0)]
                                  (swap! seen assoc n v)
                                  v))))]
                  (into {} (map (juxt identity #(d % #{}))) nodes)))]
    {:nodes nodes :edges edges :layer layer}))
