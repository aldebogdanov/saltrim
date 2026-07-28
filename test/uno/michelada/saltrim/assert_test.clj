(ns uno.michelada.saltrim.assert-test
  "Cell assertions: a claim a cell makes about its own value, re-checked on every
   recompute. It rides the per-property (style/meta) plumbing, so most of what
   makes it useful — persistence, branching, merge, as-of, undo — is inherited
   rather than written. What is NEW, and what these tests pin, is the semantics
   of checking one: what counts as holding, and what a failure says."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [uno.michelada.saltrim.sheet :as sheet]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(defn- assert! [addr src] (sheet/set-style! *sh* addr sheet/assert-prop src))
(defn- viol [addr] (sheet/assert-violation *sh* addr))

(deftest a-cell-with-no-assertion-never-violates
  (sheet/set-cell! *sh* "A1" "5")
  (is (nil? (viol "A1")))
  (is (= {} (sheet/assert-violations *sh*))))

(deftest a-claim-that-holds-is-silent-and-one-that-stops-holding-speaks
  (sheet/set-cell! *sh* "A1" "5")
  (assert! "A1" "=(> $val 0)")
  (sheet/settle! *sh*)
  (is (nil? (viol "A1")))
  (testing "the SAME assertion fails once the value changes under it — nobody
            touched the assertion, which is the whole point of checking on
            recompute rather than on write"
    (sheet/set-cell! *sh* "A1" "-3")
    (sheet/settle! *sh*)
    (is (some? (viol "A1")))
    (is (str/includes? (viol "A1") "=(> $val 0)") "the message names the claim"))
  (testing "and goes quiet again when the value comes back"
    (sheet/set-cell! *sh* "A1" "1")
    (sheet/settle! *sh*)
    (is (nil? (viol "A1")))))

(deftest a-claim-can-fail-because-a-DIFFERENT-cell-changed
  ;; the reactive case: this is why a violation needs announcing at all, rather
  ;; than being something you notice while typing in the cell
  (sheet/set-cell! *sh* "A1" "10")
  (sheet/set-cell! *sh* "B1" "=(* $A1 2)")
  (assert! "B1" "=(< $val 100)")
  (sheet/settle! *sh*)
  (is (nil? (viol "B1")))
  (sheet/set-cell! *sh* "A1" "500")
  (sheet/settle! *sh*)
  (is (some? (viol "B1")) "B1 broke, and B1 was never edited"))

(deftest false-is-a-violation-not-a-truthy-string
  ;; `style-value` stringifies, which would turn `false` into the (non-empty,
  ;; truthy) string "false" and make every failing assertion read as passing.
  ;; This is the regression that check exists for.
  (sheet/set-cell! *sh* "A1" "1")
  (assert! "A1" "=false")
  (sheet/settle! *sh*)
  (is (some? (viol "A1")))
  (testing "nil is a violation too — an assertion that computed nothing did not pass"
    (assert! "A1" "=nil")
    (sheet/settle! *sh*)
    (is (some? (viol "A1")))))

(deftest an-assertion-that-throws-is-a-violation
  ;; never swallowed: a claim you cannot evaluate is not one you may assume
  (sheet/set-cell! *sh* "A1" "abc")
  (assert! "A1" "=(> $val 0)")
  (sheet/settle! *sh*)
  (is (some? (viol "A1")))
  (is (str/includes? (viol "A1") "could not be checked")))

(deftest an-empty-cell-says-so-in-words-instead-of-leaking-a-null-pointer
  ;; `(> nil 0)` throws an NPE whose message ("Cannot invoke Object.getClass()
  ;; because x is null") tells a spreadsheet user nothing at all
  (assert! "A1" "=(> $val 0)")
  (sheet/settle! *sh*)
  (let [m (viol "A1")]
    (is (str/includes? m "the cell is empty"))
    (is (not (str/includes? m "getClass")))
    (testing "and points at the two ways to say what you meant"
      (is (str/includes? m "some? $val"))
      (is (str/includes? m "or $val 0")))))

(deftest requiring-a-value-is-itself-an-assertion
  ;; the flip side of the case above: blank must still be CHECKABLE, so that
  ;; "this cell has to be filled in" is expressible
  (assert! "A1" "=(some? $val)")
  (sheet/settle! *sh*)
  (is (some? (viol "A1")) "blank fails it")
  (sheet/set-cell! *sh* "A1" "7")
  (sheet/settle! *sh*)
  (is (nil? (viol "A1")) "filled passes it"))

(deftest a-literal-assertion-is-reported-as-the-mistake-it-is
  ;; a non-formula can never be false, so it would silently pass forever — which
  ;; is worse than useless, since the user believes the cell is guarded
  (sheet/set-cell! *sh* "A1" "5")
  (assert! "A1" "(> $val 0)")            ; note: no leading =
  (is (str/includes? (viol "A1") "not a formula")))

(deftest clearing-removes-the-claim
  (sheet/set-cell! *sh* "A1" "-1")
  (assert! "A1" "=(> $val 0)")
  (sheet/settle! *sh*)
  (is (some? (viol "A1")))
  (assert! "A1" "")
  (is (nil? (viol "A1")))
  (is (= {} (sheet/assert-violations *sh*))))

(deftest violations-are-collected-sheet-wide
  ;; the per-cell mark can only speak for the ~600 cells in the rendered window;
  ;; this is what answers "is anything wrong" for the other million
  (doseq [a ["A1" "B2" "C3"]]
    (sheet/set-cell! *sh* a "-1")
    (assert! a "=(> $val 0)"))
  (sheet/set-cell! *sh* "D4" "5")
  (assert! "D4" "=(> $val 0)")
  (sheet/settle! *sh*)
  (let [v (sheet/assert-violations *sh*)]
    (is (= ["A1" "B2" "C3"] (keys v)) "only the failing ones, in address order")
    (is (not (contains? v "D4")))))

(deftest an-assertion-rides-the-ordinary-cellprop-plumbing
  ;; which is what gets it persistence, branching, 3-way merge and undo for free
  (sheet/set-cell! *sh* "A1" "5")
  (assert! "A1" "=(> $val 0)")
  (let [doc (sheet/document *sh*)]
    (is (= "=(> $val 0)" (get-in doc ["A1" :style :assert])))
    (let [s2 (sheet/create-sheet)]
      (try
        (sheet/load-document! s2 doc)
        (sheet/settle! s2)
        (is (nil? (sheet/assert-violation s2 "A1")) "restored, and holding")
        (sheet/set-cell! s2 "A1" "-9")
        (sheet/settle! s2)
        (is (some? (sheet/assert-violation s2 "A1")) "and still live after a reload")
        (finally (sheet/close! s2))))))

(deftest an-assertion-does-not-block-the-write-that-breaks-it
  ;; the model is REPORT, not reject: a reactive cell goes invalid because
  ;; something else changed, so there is no keystroke at this cell to refuse
  (sheet/set-cell! *sh* "A1" "5")
  (assert! "A1" "=(> $val 0)")
  (sheet/settle! *sh*)
  (sheet/set-cell! *sh* "A1" "-42")
  (sheet/settle! *sh*)
  (is (= -42 (sheet/value *sh* "A1")) "the value the user typed is what is there")
  (is (some? (viol "A1")) "and it is flagged"))
