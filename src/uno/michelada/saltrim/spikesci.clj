(ns uno.michelada.saltrim.spikesci
  "Spike: replace the host-eval + symbol-whitelist formula path with SCI, WITHOUT
   disturbing the reactive await machinery.

   Insight: after the await-lift, the user body is PURE — every `await` lives in
   the `let` bindings (our infra), the body just computes over already-resolved
   cell values. So SCI never sees `spin`/`await`/`track`; it only evaluates the
   user expression with the resolved values injected as fn args. The spin wrapper
   stays host-compiled.

   Build per formula:
     (fn [uf] (spin (let [c1 (await (lookup A1)) ...] (uf c1 ...))))   ; host
     uf = (sci/eval '(fn [c1 ...] <user-body>))                        ; sandboxed
     -> (factory uf)

   Proves: (1) `let`/locals work (the thing the whitelist couldn't allow),
   (2) injected cell values compute, (3) reactive recompute still fires on a dep
   change, (4) the sandbox blocks host fns like `slurp`."
  (:require [sci.core :as sci]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.core :as ec]
            [uno.michelada.saltrim.runtime :as rt]
            [uno.michelada.saltrim.sheet :as sheet]))

;; --- parse (markers only; ranges omitted for the spike) -----------------

(defn- ref? [x] (and (seq? x) (= ::ref (first x))))

(defn- parse
  "Formula string (no leading =) + owner addr -> marker form. `#cell A1` ->
   (::ref \"A1\"); bare `$val` -> (::ref self)."
  [s self]
  (let [form (edn/read-string {:readers {'cell (fn [sym] (list ::ref (str sym)))}} s)]
    (walk/postwalk #(if (= '$val %) (list ::ref self) %) form)))

(defn- ref-addrs [form]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (ref? x) (vswap! acc conj (second x))) x) form)
    (vec (distinct @acc))))

;; --- SCI sandbox --------------------------------------------------------
;; Default SCI core: a curated, side-effect-free clojure.core subset, real
;; lexical scope (let/fn/destructuring), NO host interop. Later: add a per-sheet
;; namespace + a predefined fn stdlib here.
(def ^:private sci-ctx (sci/init {}))

;; --- compile ------------------------------------------------------------

(defn compile-sci
  "Marker form -> reactive Spin. The user body is SCI-compiled to a host-callable
   fn of the cell values; the spin awaits each cell then calls it."
  [marker-form]
  (let [addrs (ref-addrs marker-form)
        syms  (mapv (fn [a] (gensym (str "c_" a "_"))) addrs)
        a->s  (zipmap addrs syms)
        body  (walk/postwalk #(if (ref? %) (a->s (second %)) %) marker-form)
        user-fn (sci/eval-form (sci/fork sci-ctx) (list 'fn syms body))   ; sandboxed
        bnds   (vec (mapcat (fn [a s] [s (list 'await (list `rt/lookup a))]) addrs syms))
        factory (binding [*ns* (find-ns 'uno.michelada.saltrim.spikesci)]
                  (eval (list 'fn ['uf] (list 'spin (list 'let bnds (list* 'uf syms))))))]
    (factory user-fn)))

(defn- install! [s addr formula self]
  (binding [ec/*execution-context* (:rt s)]
    (swap! (:registry s) assoc addr (compile-sci (parse formula self)))))

(defn -main [& _]
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "10")
    (sheet/set-cell! s "B1" "20")

    ;; THE win: a local binding — rejected by the old whitelist, fine in SCI.
    (install! s "C1" "(let [x #cell A1 y #cell B1] (+ x y (* x 2)))" "C1")
    ;; higher-order + lambda, also impossible before
    (install! s "C2" "(reduce + (map (fn [n] (* n n)) [#cell A1 #cell B1]))" "C2")
    (sheet/settle! s)
    (println "C1 (let x y) =" (sheet/value s "C1") " expect 50  (10+20+10*2)")
    (println "C2 (map/fn/reduce) =" (sheet/value s "C2") " expect 500  (100+400)")

    ;; reactive recompute still fires: change a dep, re-read.
    (sheet/set-cell! s "A1" "100")
    (sheet/settle! s)
    (println "C1 after A1=100 =" (sheet/value s "C1") " expect 320  (100+20+100*2)")
    (println "C2 after A1=100 =" (sheet/value s "C2") " expect 10400  (10000+400)")

    ;; sandbox: host fns are not resolvable.
    (println "slurp resolvable? ->"
             (try (sci/eval-form (sci/fork sci-ctx) '(slurp "/etc/passwd"))
                  :LEAK
                  (catch Exception e (.getMessage e))))
    ;; NOTE: SCI exposes `eval`, but it's SANDBOXED self-eval (evaluates in the
    ;; SCI ctx, can't reach host) — proven by nesting slurp inside it.
    (println "eval is in-sandbox? ->"
             (try (sci/eval-form (sci/fork sci-ctx) '(eval '(slurp "/etc/passwd")))
                  :LEAK
                  (catch Exception e (str "safe: " (.getMessage e)))))
    (shutdown-agents)))
