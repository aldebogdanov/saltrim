(ns uno.michelada.saltrim.simplify
  "Algebraic cleanup of parsed formula (marker) forms — a small rewrite system
   applied bottom-up to a fixpoint. The flatten feature (`sheet/flatten-src`)
   runs it over an inlined formula to make the result idiomatic.

   Rules:
     R1 constant fold        (+ 1 2)        -> 3         (whitelisted pure ops,
                                                          all-literal args)
     R2 literal if           (if true a b)  -> a
     R3 associative flatten  (+ a (+ b c))  -> (+ a b c) (+ * str and or min max)
     R4 inc/dec idioms       (+ x 1)        -> (inc x),  (- x 1) -> (dec x)
     R5a single-arg identity (and x)/(or x) -> x         (exact — (and x) IS x)
     R5b numeric identity    (+ x 0)/(* x 1)-> x         [FULL MODE ONLY]

   Strictness: R1–R5a preserve semantics exactly, including error behavior.
   R5b is value-exact whenever the original evaluates, but can turn an ERROR
   into a VALUE when x isn't numeric (a blank cell ref is nil: (+ nil 0)
   throws, plain nil doesn't). `{:strict true}` disables R5b.

   Scope safety: a rule only fires when its operator symbol is NOT locally
   bound — `(let [sum 10] (+ sum 0))` keeps the user's `sum`, and the env is
   threaded through binding forms via `formula/direct-binders`.

   Termination: every rule strictly decreases the node count, so the
   pass-to-fixpoint loop is bounded by the initial size (hard-capped anyway).
   Deliberately excluded: (* x 0) -> 0 (changes 0.0 to 0 on doubles) and
   folding through user defs (the fold context is the bare stdlib)."
  (:require [clojure.edn :as edn]
            [sci.core :as sci]
            [uno.michelada.saltrim.formula :as formula]))

;; --- constant folding (R1) ------------------------------------------------

(def ^:private fold-ops
  "Operators safe to evaluate at simplify time: pure, deterministic (`today`
   is NOT here), total over their domain (a throw just means the rule doesn't
   fire). Stdlib names resolve in the fold context below."
  '#{+ - * / quot rem mod inc dec max min abs
     = not= < > <= >= not and or
     str count
     ceil floor round sqrt pow exp ln log10 sign
     upper lower trim starts-with? ends-with? includes? blank?})

(def ^:private fold-ctx
  "Bare stdlib SCI context for folding — never the sheet's own context, so
   user defs can't run (or loop) at simplify time."
  (delay (formula/new-ctx nil)))

(defn- lit?
  "A literal scalar — the only argument shape R1 folds over."
  [x]
  (or (number? x) (string? x) (boolean? x) (nil? x) (keyword? x) (char? x)))

(defn- ok-result?
  "Fold results must be literal scalars that survive a print/read round-trip
   (the folded value gets embedded in source): finite doubles only (NaN passes
   a naive = check — Java Double.equals(NaN,NaN) is true — so test explicitly)."
  [r]
  (and (lit? r)
       (or (not (instance? Double r)) (Double/isFinite r))
       (or (nil? r) (= r (edn/read-string (pr-str r))))))

(defn- try-fold
  "Evaluate `x` in the bare-stdlib sandbox; the folded value, or ::no when it
   throws or the result isn't embeddable."
  [x]
  (try
    (let [r (sci/eval-form (sci/fork @fold-ctx) x)]
      (if (ok-result? r) r ::no))
    (catch Throwable _ ::no)))

;; --- the rules --------------------------------------------------------------

(def ^:private assoc-ops '#{+ * str and or min max})

(defn- truthy? [x] (not (or (false? x) (nil? x))))

(defn- long-lit?
  "Is `x` the Long literal `v`? (1N/1.0 don't count — coercion through them
   changes result types, so type-sensitive rules only fire on Longs.)"
  [x v] (and (instance? Long x) (= v x)))

(defn- long-one? [x] (long-lit? x 1))

(defn- rule
  "One rewrite of node `x` (head symbol `h`, already known unbound), or ::no."
  [x h strict?]
  (let [args (rest x)]
    (cond
      ;; R1 constant fold
      (and (fold-ops h) (every? lit? args))
      (let [r (try-fold x)]
        (if (not= ::no r) r ::no))

      ;; R2 literal if — pick the taken branch ((if c t) has an implicit nil else)
      (and (= 'if h) (<= 2 (count args) 3) (lit? (first args)))
      (if (truthy? (first args)) (second args) (nth args 2 nil))

      ;; R3 associative flatten — splice a same-op child into its parent
      (and (assoc-ops h) (some #(and (seq? %) (= h (first %))) args))
      (apply list h (mapcat #(if (and (seq? %) (= h (first %))) (rest %) [%]) args))

      ;; R4 inc/dec idioms (the Long literal 1 only — 1.0/1N would change the
      ;; result type through coercion)
      (and (= '+ h) (= 2 (count args)) (some long-one? args))
      (list 'inc (if (long-one? (first args)) (second args) (first args)))

      (and (= '- h) (= 2 (count args)) (long-one? (second args)))
      (list 'dec (first args))

      ;; R5a single-arg and/or — exact: (and x) IS x
      (and ('#{and or} h) (= 1 (count args)))
      (first args)

      ;; R5b numeric identities [full mode] — drop +0 / *1 arguments (Long
      ;; literals only, same type caution as R4)
      (and (not strict?) ('#{+ *} h)
           (let [id ({'+ 0 '* 1} h)] (some #(long-lit? % id) args)))
      (let [id   ({'+ 0 '* 1} h)
            kept (remove #(long-lit? % id) args)]
        (case (count kept)
          0 id
          1 (first kept)
          (apply list h kept)))

      :else ::no)))

;; --- the engine -------------------------------------------------------------

(defn- rewrite
  "Apply rules to node `x` until none fires (each strictly shrinks the node)."
  [x env strict?]
  (if (and (seq? x) (symbol? (first x)) (not (env (first x))))
    (let [x' (rule x (first x) strict?)]
      (if (= ::no x') x (recur x' env strict?)))
    x))

(defn- pass
  "One bottom-up pass: simplify children (binding forms extend `env` for their
   subtrees), then rewrite the node. Ref/range markers are opaque leaves."
  [x env strict?]
  (cond
    (formula/ref? x)       x
    (formula/range-ref? x) x

    (seq? x)
    (let [env' (into env (or (formula/direct-binders x) #{}))]
      (rewrite (apply list (map #(pass % env' strict?) x)) env strict?))

    (vector? x) (mapv #(pass % env strict?) x)
    (map? x)    (into {} (map (fn [[k v]] [(pass k env strict?)
                                           (pass v env strict?)]) x))
    (set? x)    (into #{} (map #(pass % env strict?) x))
    :else x))

(defn simplify
  "Simplify a marker form (see the ns docstring for the rules). Full mode by
   default; `{:strict true}` keeps only the rules that preserve error behavior
   exactly. Always returns a form — at worst the input unchanged."
  ([form] (simplify form nil))
  ([form {:keys [strict]}]
   (loop [f form, i 0]
     (let [f' (pass f #{} (boolean strict))]
       (if (or (= f' f) (<= 50 i)) f' (recur f' (inc i)))))))
