(ns spikes.10-await-fanout-chunking
  "SPIKE 10 — can a formula reference more than ~250 cells?

   `clojure -M:bench` found the ceiling: `=(sum $A1:A250)` compiles,
   `=(sum $A1:A260)` dies with

     java.lang.ClassFormatError: Too many arguments in method signature

   `formula/compile` awaits every distinct referenced cell in ONE spin body.
   Spindel's CPS transform turns each `await` into a nested continuation that
   carries every previously-bound local as a method ARGUMENT, so a body with
   ~250 awaits generates a method past the JVM's hard cap of 255 arguments.
   `MAX-RANGE-CELLS` is 10 000 — forty times what actually compiles.

   Spindel has no batch-await primitive (checked 0.1.15: `effects/await.cljc`
   exposes one `await`, one spin at a time). So the fix has to change the SHAPE
   of the generated code rather than call a better API.

   THE IDEA: chunk the awaits across intermediate spins. Instead of one body
   awaiting N cells, build ceil(N/K) chunk spins that each await ≤K cells and
   yield a vector, then have the cell's body await the CHUNKS. Nesting recurses
   when there are too many chunks, so the fan-out becomes a tree of depth
   log_K(N) and no single body ever exceeds K awaits.

     (let [ch1 (spin (let [x1 (await (lookup \"A1\")) …] [x1 …]))
           ch2 (spin …)]
       (spin (let [g1 (await ch1)
                   g2 (await ch2)]
               (apply uf (concat g1 g2)))))

   Four things this spike has to prove before the change is worth writing:

     1. the ceiling actually moves (1000+ cells in one formula);
     2. the result is CORRECT — chunk order must preserve ref order, or every
        range silently permutes;
     3. it stays REACTIVE — editing a cell inside a chunk must recompute the
        reader, which is the whole point of the engine;
     4. it does not cost more than it saves at ordinary sizes.

   Run: eval the `(comment …)` forms one at a time at a dev REPL."
  (:require [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [uno.michelada.saltrim.runtime :as rt]
            [uno.michelada.saltrim.sheet :as sheet]))

(comment

  ;; --- 1. the ceiling, before ---------------------------------------------
  ;; Bisected: 250 compiles, 260 does not. The exact number depends on how many
  ;; other locals the formula has, so it is a region, not a constant.

  (defn- try-sum [n]
    (try (let [s (sheet/create-sheet)]
           (doseq [i (range n)] (sheet/set-cell! s (str "A" (inc i)) (str i)))
           (sheet/set-cell! s "B1" (str "=(sum $A1:A" n ")"))
           (sheet/settle! s)
           [n :ok (sheet/value s "B1")])
         (catch Throwable t
           (let [root (loop [e t] (if (.getCause e) (recur (.getCause e)) e))]
             [n :throw (.getSimpleName (class root))]))))

  (mapv try-sum [200 250 260 300])
  ;; => [[200 :ok 19900] [250 :ok 31125]
  ;;     [260 :throw "ClassFormatError"] [300 :throw "ClassFormatError"]]


  ;; --- 2. the shape of the generated code ---------------------------------
  ;; What compile emits today, for a 3-cell range (one body, N awaits):
  ;;
  ;;   (spin (let [c_1 (await (rt/lookup "A1"))
  ;;               c_2 (await (rt/lookup "A2"))
  ;;               c_3 (await (rt/lookup "A3"))]
  ;;           (uf c_1 c_2 c_3)))
  ;;
  ;; What chunking emits (K = 2 here, so two chunks):
  ;;
  ;;   (let [ch_1 (spin (let [x_1 (await (rt/lookup "A1"))
  ;;                          x_2 (await (rt/lookup "A2"))]
  ;;                      [x_1 x_2]))
  ;;         ch_2 (spin (let [x_3 (await (rt/lookup "A3"))]
  ;;                      [x_3]))]
  ;;     (spin (let [g_1 (await ch_1)
  ;;                 g_2 (await ch_2)]
  ;;             (apply uf (concat g_1 g_2)))))
  ;;
  ;; The destructuring is deliberately NOT emitted as bindings: `apply` over the
  ;; concatenated chunks keeps the continuation chain carrying m locals instead
  ;; of N. A call with 1000 arguments is fine — Clojure compiles anything past
  ;; 20 to `.applyTo` — it is only the CPS continuation SIGNATURE that is capped.

  ;; Build one by hand at a size that cannot work today:
  (def n 1000)
  (def sh (sheet/create-sheet))
  (doseq [i (range n)] (sheet/set-cell! sh (str "A" (inc i)) (str i)))
  (sheet/settle! sh)

  (def addrs (mapv #(str "A" (inc %)) (range n)))

  (defn- chunk-form
    "(spin (let [x1 (await (lookup a1)) …] [x1 …])) for one group of addresses."
    [group]
    (let [syms (mapv (fn [_] (gensym "x_")) group)]
      (list 'spin
            (list 'let
                  (vec (mapcat (fn [s a]
                                 [s (list 'await
                                          (list 'uno.michelada.saltrim.runtime/lookup a))])
                               syms group))
                  (vec syms)))))

  (defn- fanout-form
    "A spin yielding the values of `addrs` in order, awaiting at most K per
     body. Recurses when there are more than K chunks."
    [addrs k]
    (if (<= (count addrs) k)
      (chunk-form addrs)
      (let [groups (partition-all k addrs)
            subs   (mapv #(fanout-form (vec %) k) groups)
            gsyms  (mapv (fn [_] (gensym "g_")) subs)]
        (list 'let (vec (mapcat (fn [s f] [s f]) gsyms subs))
              (list 'spin
                    (list 'let (vec (mapcat (fn [g s] [g (list 'await s)])
                                            gsyms gsyms))
                          (list* 'concat gsyms)))))))

  ;; NOTE the bug this spike caught on the first attempt: binding the chunk
  ;; spins and then awaiting them under the SAME symbol shadows the spin with
  ;; its value, which works — but only because the await bindings are evaluated
  ;; in order. Written the other way round (await first, bind later) the outer
  ;; spin awaits a value instead of a spin and Spindel throws. Keep the two
  ;; symbol sets distinct in the real implementation.

  (def probe
    (binding [*ns* (find-ns 'spikes.10-await-fanout-chunking)]
      (eval (list 'fn [] (fanout-form addrs 100)))))

  ;; 1000 cells, one reader — impossible before this spike:
  (def result (sheet/with-sheet sh (probe)))
  (count @result)                      ;; => 1000
  (reduce + @result)                   ;; => 499500  (0+1+…+999)


  ;; --- 3. reactivity through a chunk --------------------------------------
  ;; The point of the engine: change a cell buried inside chunk 7 and the
  ;; reader must recompute, not serve a stale vector.

  (sheet/set-cell! sh "A700" "100000")
  (sheet/settle! sh)
  (reduce + @result)                   ;; => 100000 + 499500 - 699 = 598801


  ;; --- 4. what it costs ----------------------------------------------------
  ;; Compare a 200-cell range (which compiles both ways) flat vs chunked, so
  ;; the extra spin layer is priced rather than assumed.
  ;;
  ;;   flat    (one body, 200 awaits) : ~ measure here
  ;;   chunked (K=100, 2 chunks)      : ~ measure here
  ;;
  ;; Expectation: chunking ADDS a spin per 100 cells and one extra await hop,
  ;; and REMOVES a very deep continuation chain. At 200 cells it should be a
  ;; wash; the win is that 1000 works at all.

  :rcf)
