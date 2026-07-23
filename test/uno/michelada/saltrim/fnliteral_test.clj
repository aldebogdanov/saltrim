(ns uno.michelada.saltrim.fnliteral-test
  "`#(...)` in formulas.

   Formula source is read by `edn/read-string`, and EDN has no dispatch macros —
   so `=(map #(* 2 %) $A1:A3)`, the single most reflexive thing a Clojure user
   types, died with \"No dispatch macro for: (\". Swapping in the full Clojure
   reader would also admit record literals, which construct host objects at READ
   time (before the sandbox sees anything), so `#(` becomes an ordinary call to a
   macro the sandbox provides."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.sheet :as sh]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [s (sh/create-sheet)]
                        (sh/set-cell! s "A1" "1")
                        (sh/set-cell! s "A2" "2")
                        (sh/set-cell! s "A3" "3")
                        (try (binding [*sh* s] (t))
                             (finally (sh/close! s))))))

(defn- val-of [src]
  (sh/set-cell! *sh* "Z9" src)
  (sh/settle! *sh*)
  (sh/value *sh* "Z9"))

(deftest the-shapes-people-actually-type
  (is (= [2 4 6] (val-of "=(vec (map #(* 2 %) $A1:A3))")))
  (is (= [2 3]   (val-of "=(vec (filter #(> % 1) $A1:A3))")))
  (is (= 14      (val-of "=(reduce + (map #(* %1 %1) $A1:A3))")))
  (is (= [2 4 6] (val-of "=(vec (map #(+ %1 %2) $A1:A3 $A1:A3))"))))

(deftest arity-comes-from-the-percent-symbols
  (testing "% and %1 are the same first argument"
    (is (= 1 (val-of "=(#(identity %) 1)")))
    (is (= 1 (val-of "=(#(identity %1) 1)")))
    ;; Clojure's reader maps both spellings onto one argument; a body that uses
    ;; both used to fail with "Unable to resolve symbol: %1"
    (is (= 1 (val-of "=(#(* % %1) 1)"))))
  (testing "gaps are filled — #(f %3) still takes three"
    (is (= 3 (val-of "=(#(identity %3) 1 2 3)"))))
  (testing "%& collects the rest, exactly as Clojure's own reader does"
    ;; no positional params here, so %& is ALL the arguments
    (is (= [1 2 3] (val-of "=(vec (#(vec %&) 1 2 3))")))
    ;; one positional plus a rest
    (is (= [1 [2 3]] (val-of "=(#(vector %1 (vec %&)) 1 2 3)")))
    ;; and arity is still enforced without one
    (is (:error (val-of "=(#(identity %1) 1 2 3)")))))

(deftest the-body-is-the-call-it-was-written-as
  ;; treating the spliced elements as separate statements would silently return
  ;; the LAST one, making #(* 2 %) an identity function that still "works"
  (is (= [2 4 6] (val-of "=(vec (map #(* 2 %) $A1:A3))")))
  (is (not= [1 2 3] (val-of "=(vec (map #(* 2 %) $A1:A3))"))))

(deftest a-percent-or-hash-inside-a-string-is-data
  ;; the rewrite is textual, so this is the case it has to get right
  (is (= "a#(b)1" (val-of "=(str \"a#(b)\" 1)")))
  (is (= "100% done" (val-of "=(str \"100%\" \" done\")")))
  (is (= "#(* 2 %)" (val-of "=(str \"#(* 2 %)\")")))
  (testing "and a character literal keeps the character it escapes"
    (is (= "x" (val-of "=(str \\x)")))
    (is (= 1 (val-of "=(count #{\\a})")))))

(deftest nesting-is-refused-clearly
  ;; Clojure rejects it too; say so instead of emitting something confusing
  (is (thrown-with-msg? Exception #"nested #\(\)"
                        (sh/set-cell! *sh* "Z9" "=(map #(#(* 2 %) %) $A1:A3)"))))

(deftest fn-still-works-and-refs-inside-a-literal-are-real-deps
  (is (= 12 (val-of "=(reduce + (map (fn [x] (* 2 x)) $A1:A3))")))
  (testing "a cell ref inside #() is an ordinary dependency"
    (sh/set-cell! *sh* "B1" "=(vec (map #(* % $A1) $A1:A3))")
    (sh/settle! *sh*)
    (is (= [1 2 3] (sh/value *sh* "B1")))
    (sh/set-cell! *sh* "A1" "10")
    (sh/settle! *sh*)
    (is (= [100 20 30] (sh/value *sh* "B1")) "it recomputes when A1 changes")))

(deftest flatten-prints-it-back-as-a-literal
  (sh/set-cell! *sh* "B1" "=(map #(* 2 %) $A1:A3)")
  (sh/settle! *sh*)
  (is (= "=(map #(* 2 %) $A1:A3)" (sh/flatten-src *sh* "B1" nil))))
