(ns uno.michelada.saltrim.flatten-test
  "Flatten feature: unparse (inverse of parse), inline (recursive formula-ref
   substitution + hygiene), the simplify rule engine, and sheet/flatten-src."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.simplify :as simplify]
            [uno.michelada.saltrim.sheet :as sh]))

(defn- pf [s] (:form (formula/parse s)))
(defn- mk [] (sh/create-sheet))
(defn- put [s a raw] (sh/set-cell! s a raw))
(defn- v [s a] (sh/settle! s) (sh/value s a))

;; --- unparse ---------------------------------------------------------------

(deftest unparse-round-trip
  (testing "round-trip law: (= form (:form (parse (unparse form))))"
    (doseq [src ["(* $A2 $A3)"
                 "(sum $B1:B3)"
                 "(sum $A1:B2)"
                 "(+ (sum $A1:B2) #cell C3 1)"
                 "(let [x 2] (str \"$A1 inside a string\" x))"
                 "(reduce + (map inc $A1:A3))"
                 "(if (> $A1 0) {:a 1, :b [1 2 3]} #{1/3 \\c nil})"
                 "(join \", \" [\"a\" \"b\"])"
                 "((fn [x] (* x x)) $A1)"
                 "(str 'sym :kw 2.5 true nil)"]]
      (let [form (pf src)]
        (is (= form (:form (formula/parse (formula/unparse form))))
            (str "round-trips: " src))))))

(deftest unparse-sugar
  (testing "refs render as $-sugar, full rectangles re-collapse"
    (is (= "$A1" (formula/unparse (pf "#cell A1"))))
    (is (= "(sum $A1:B2)" (formula/unparse (pf "(sum #cells A1:B2)"))))
    (is (= "(sum $A1:A3)" (formula/unparse (pf "(sum $A1:A3)")))))
  (testing "a hand-built vector of refs forming a rectangle collapses too"
    (is (= "(sum $A1:A2)" (formula/unparse (pf "(sum (vector $A1 $A2))")))))
  (testing "broken rectangles / wrong order stay explicit"
    (is (= "(sum (vector $A1 $B2))" (formula/unparse (pf "(sum (vector $A1 $B2))"))))
    (is (= "(sum (vector $A2 $A1))" (formula/unparse (pf "(sum (vector $A2 $A1))")))))
  (testing "range marker (unparse-side input) renders as range sugar"
    (is (= "$A1:B2" (formula/unparse (formula/range-marker "A1" "B2"))))
    (is (= "(sum $A1:B2)"
           (formula/unparse (list 'sum (formula/range-marker "A1" "B2"))))))
  (testing "$-lookalikes inside string literals stay strings"
    (is (= "(str \"$A1\")" (formula/unparse (pf "(str \"$A1\")"))))))

;; --- inline ----------------------------------------------------------------

(defn- fo-of
  "form-of over a plain {addr src-without-=} map (formula cells only)."
  [m]
  (fn [a] (some-> (m a) pf)))

(deftest inline-user-story
  (let [fo (fo-of {"A3" "(+ $B1 $B2)" "B2" "(inc $B3)"})]
    (is (= "(* $A2 (+ $B1 (inc $B3)))"
           (formula/unparse (formula/inline (pf "(* $A2 $A3)") fo)))
        "formula refs inline recursively; literal refs stay refs")))

(deftest inline-shapes
  (let [fo (fo-of {"A3" "(+ $B1 $B2)" "B2" "(inc $B3)"})]
    (testing "literal/blank refs stay leaf markers"
      (is (= "(+ $B1 $Z9)" (formula/unparse (formula/inline (pf "(+ $B1 $Z9)") fo)))))
    (testing "a range member that is a formula inlines inside the expanded vector"
      (is (= "(sum (vector $B1 (inc $B3) $B3))"
             (formula/unparse (formula/inline (pf "(sum $B1:B3)") fo)))))
    (testing "diamond: the body lands at every ref site"
      (is (= "(+ (+ $B1 (inc $B3)) (+ $B1 (inc $B3)))"
             (formula/unparse (formula/inline (pf "(+ $A3 $A3)") fo)))))
    (testing "an inlined body may carry its own let (no false hygiene refusal)"
      (is (= "(* 2 (let [t $B1] (* t t)))"
             (formula/unparse (formula/inline (pf "(* 2 $A3)")
                                              (fo-of {"A3" "(let [t $B1] (* t t))"}))))))))

(deftest inline-hygiene
  (let [fo (fo-of {"A3" "(sum $B1:B3)"})]
    (testing "an enclosing binder that an inlined body uses refuses, naming it"
      (let [e (try (formula/inline (pf "(let [sum 10] (* sum $A3))") fo)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= #{'sum} (:collisions (ex-data e))))
        (is (= "A3" (:addr (ex-data e)))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (formula/inline (pf "((fn [sum] (* sum $A3)) 7)") fo))))
    (testing "non-colliding binders inline fine"
      (is (= "(let [k 10] (* k (sum $B1:B3)))"
             (formula/unparse (formula/inline (pf "(let [k 10] (* k $A3))") fo)))))))

(deftest inline-size-cap
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"too large"
       (formula/inline (pf (str "(+ " (apply str (repeat 3000 "(inc 1) ")) ")")) {}))))

;; --- simplify ---------------------------------------------------------------

(defn- simp
  ([s] (simp s nil))
  ([s o] (formula/unparse (simplify/simplify (pf s) o))))

(deftest simplify-fold
  (is (= "3" (simp "(+ 1 2)")))
  (is (= "(* 3 8.0 $A1)" (simp "(* (+ 1 2) (pow 2 3) $A1)")) "folds bottom-up")
  (is (= "1/3" (simp "(/ 1 3)")) "ratios are embeddable")
  (is (= "(/ 1 0)" (simp "(/ 1 0)")) "a throwing fold doesn't fire")
  (is (= "(sqrt -1)" (simp "(sqrt -1)")) "NaN results don't fold")
  (is (= "\"ab\"" (simp "(str \"a\" \"b\")")))
  (is (= "(today)" (simp "(today)")) "impure ops never fold"))

(deftest simplify-if
  (is (= "(inc $A1)" (simp "(if (> 3 2) (+ $A1 1) 99)")) "cond folds, branch taken")
  (is (= "99" (simp "(if (< 3 2) $A1 99)")))
  (is (= "nil" (simp "(if false $A1)")) "missing else is nil")
  (is (= "(if $A1 1 2)" (simp "(if $A1 1 2)")) "non-literal cond untouched"))

(deftest simplify-assoc-and-idioms
  (is (= "(+ $A1 $B1 $C1 $D1 $E1)" (simp "(+ $A1 (+ $B1 (+ $C1 $D1)) $E1)")))
  (is (= "(str \"ab\" $A1)" (simp "(str (str \"a\" \"b\") $A1)")))
  (is (= "(inc $A1)" (simp "(+ $A1 1)")))
  (is (= "(inc $A1)" (simp "(+ 1 $A1)")))
  (is (= "(dec $A1)" (simp "(- $A1 1)")))
  (is (= "(- 1 $A1)" (simp "(- 1 $A1)")) "subtraction is not commutative")
  (is (= "(+ $A1 1.0)" (simp "(+ $A1 1.0)")) "double 1.0 keeps coercion")
  (is (= "(+ $A1 1N)" (simp "(+ $A1 1N)")) "bigint 1N keeps result type"))

(deftest simplify-identities-and-modes
  (testing "single-arg and/or is exact — both modes"
    (is (= "$A1" (simp "(and $A1)" {:strict true})))
    (is (= "$A1" (simp "(or $A1)"))))
  (testing "numeric identities only in full mode"
    (is (= "$A1" (simp "(* (+ $A1 0) 1)")))
    (is (= "(* (+ $A1 0) 1)" (simp "(* (+ $A1 0) 1)" {:strict true})))
    (is (= "(+ $A1 $B1)" (simp "(+ $A1 0 $B1)")) "n-ary identity drop")))

(deftest simplify-scope-safety
  (is (= "(let [sum 2] sum)" (simp "(let [sum 2] (+ sum 0))"))
      "user binder respected, unshadowed + still simplifies")
  (is (= "((fn [+] (+ 1 2)) 9)" (simp "((fn [+] (+ 1 2)) 9)"))
      "a shadowed operator never fires a rule"))

;; --- sheet/flatten-src end-to-end -------------------------------------------

(deftest flatten-src-end-to-end
  (let [s (mk)]
    (put s "A1" "=(* $A2 $A3)") (put s "A2" "6")
    (put s "A3" "=(+ $B1 $B2)") (put s "B1" "3")
    (put s "B2" "=(inc $B3)")   (put s "B3" "4")
    (sh/settle! s)
    (let [flat (sh/flatten-src s "A1" nil)]
      (is (= "=(* $A2 (+ $B1 (inc $B3)))" flat))
      (testing "the flattened source computes the same value"
        (put s "Z9" flat)
        (is (= (v s "A1") (v s "Z9")))))
    (testing "simplification applies through the inlined result"
      (put s "C1" "=(+ $A3 1)")
      (sh/settle! s)
      (is (= "=(+ $B1 (inc $B3) 1)" (sh/flatten-src s "C1" nil))
          "inline then associative-flatten")
      (is (= (v s "C1") (do (put s "Z8" (sh/flatten-src s "C1" nil)) (v s "Z8")))))
    (testing "non-formula cells refuse"
      (is (thrown? clojure.lang.ExceptionInfo (sh/flatten-src s "A2" nil)))
      (is (thrown? clojure.lang.ExceptionInfo (sh/flatten-src s "Q9" nil))))
    (sh/close! s)))
