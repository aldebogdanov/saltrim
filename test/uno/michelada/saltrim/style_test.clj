(ns uno.michelada.saltrim.style-test
  "The STYLE layer's reactivity.

   Style props are their own spins that only READ the value layer. The trap this
   ns guards: `set-cell!` rebuilds VALUE dependents when a cell's public spin is
   structurally replaced (literal->formula, blank->literal, …), but a style
   formula referencing that cell is not in `meta :deps` — so unless the style
   layer is rebuilt too, its spin keeps awaiting the DEAD spin object and the
   cell silently keeps its old colour forever."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.sheet :as sheet]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(defn- bg [] (sheet/style-value *sh* "B1" :bg))

(deftest style-follows-a-plain-value-change
  ;; baseline: a value-only edit propagates through the signal, no rebuild needed
  (sheet/set-cell! *sh* "A1" "5")
  (sheet/set-style! *sh* "B1" :bg "=(if (> $A1 10) \"red\" \"white\")")
  (sheet/settle! *sh*)
  (is (= "white" (bg)))
  (sheet/set-cell! *sh* "A1" "50")
  (sheet/settle! *sh*)
  (is (= "red" (bg)) "a literal->literal edit must repaint the style"))

(deftest style-survives-a-structural-replace
  ;; THE BUG (TECHDEBT "Verify suspected pre-existing gap"): changing A1 from a
  ;; LITERAL to a FORMULA replaces its public spin object. Value dependents are
  ;; rebuilt; the style spin must be too, or it awaits the dead spin.
  (sheet/set-cell! *sh* "A1" "5")
  (sheet/set-style! *sh* "B1" :bg "=(if (> $A1 10) \"red\" \"white\")")
  (sheet/settle! *sh*)
  (is (= "white" (bg)) "precondition")

  (testing "literal -> formula (spin replaced) still repaints"
    (sheet/set-cell! *sh* "A1" "=(+ 40 20)")     ; 60 -> red
    (sheet/settle! *sh*)
    (is (= "red" (bg))))

  (testing "formula -> literal (spin replaced again) still repaints"
    (sheet/set-cell! *sh* "A1" "1")
    (sheet/settle! *sh*)
    (is (= "white" (bg))))

  (testing "blank -> literal (spin created) still repaints"
    (sheet/set-cell! *sh* "A1" "")
    (sheet/settle! *sh*)
    (sheet/set-cell! *sh* "A1" "99")
    (sheet/settle! *sh*)
    (is (= "red" (bg)))))

(deftest val-sugar-survives-its-own-structural-replace
  ;; `$val` rewrites to a ref on the OWNER, so a style on A1 awaits A1's own
  ;; public spin — replacing it must repaint A1's own style too.
  (sheet/set-cell! *sh* "A1" "5")
  (sheet/set-style! *sh* "A1" :bg "=(if (> $val 10) \"red\" \"white\")")
  (sheet/settle! *sh*)
  (is (= "white" (sheet/style-value *sh* "A1" :bg)) "precondition")
  (sheet/set-cell! *sh* "A1" "=(* 30 3)")        ; literal -> formula, 90
  (sheet/settle! *sh*)
  (is (= "red" (sheet/style-value *sh* "A1" :bg))))

(deftest style-follows-a-transitive-structural-replace
  ;; the style reads A1, whose FORMULA reads C1; replacing C1's spin must
  ;; propagate through A1 to the style
  (sheet/set-cell! *sh* "C1" "1")
  (sheet/set-cell! *sh* "A1" "=(* $C1 2)")
  (sheet/set-style! *sh* "B1" :bg "=(if (> $A1 10) \"red\" \"white\")")
  (sheet/settle! *sh*)
  (is (= "white" (bg)) "precondition")
  (sheet/set-cell! *sh* "C1" "=(+ 50 0)")        ; literal -> formula, 50*2 = 100
  (sheet/settle! *sh*)
  (is (= "red" (bg))))
