(ns uno.michelada.saltrim.errors-test
  "Classified cell errors: the code a failure gets, what the cell then shows,
   and the formula-level branching that reads it."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.errors :as errors]
            [uno.michelada.saltrim.sheet :as sh]))

(defn- v [s a] (sh/settle! s) (sh/value s a))

(defn- sheet-with [cells]
  (let [s (sh/create-sheet)]
    (doseq [[a raw] cells] (sh/set-cell! s a raw))
    s))

(defn- code [s a] (:code (v s a)))

(deftest classification
  (testing "an Excel error keeps the code excel/call named it"
    (is (= :div0 (errors/classify (ex-info "#DIV/0!" {:excel-error :div0}))))
    (is (= :na   (errors/classify (ex-info "#N/A" {:excel-error :na})))))
  (testing "a deleted reference is #REF!"
    (is (= :ref (errors/classify (ex-info "#REF! — column B was deleted" {:ref "B"})))))
  (testing "plain Java failures are placed by class or message"
    (is (= :div0  (errors/classify (ArithmeticException. "Divide by zero"))))
    (is (= :value (errors/classify (ClassCastException. "class java.lang.String cannot be cast"))))
    (is (= :value (errors/classify (NumberFormatException. "nope"))))
    (is (= :name  (errors/classify (ex-info "Unable to resolve symbol: wat" {})))))
  (testing "anything unrecognised is #ERR — classification never fails"
    (is (= :error (errors/classify (Exception. "something we have never seen"))))
    (is (= :error (errors/classify (Exception.))))))

(deftest labels-and-detail
  (testing "the cell shows the short spreadsheet name"
    (is (= "#DIV/0!" (errors/label {:code :div0})))
    (is (= "#N/A"    (errors/label {:code :na})))
    (is (= "#ERR"    (errors/label {:code :error}))))
  (testing "a value with no code still renders — sheets predate this change"
    (is (= "#ERR" (errors/label {:error "whatever"}))))
  (testing "detail is the message behind the label, or nothing when it repeats it"
    (is (= "Divide by zero" (errors/detail {:error "Divide by zero" :code :div0})))
    (is (nil? (errors/detail {:error "#N/A" :code :na}))
        "an Excel error's message IS its label — don't say it twice")
    (is (nil? (errors/detail {:error "" :code :error})))))

(deftest cells-report-a-code
  (testing "each kind of failure lands on the right code, end to end"
    (let [s (sheet-with [["A1" "0"] ["A2" "text"] ["A3" "5"]
                         ["B1" "=(/ $A3 $A1)"]
                         ["B2" "=(+ $A2 1)"]
                         ["B3" "=(fact -1)"]
                         ["B4" "=(vlookup \"z\" (as-rows 2 [\"a\" 1]) 2 false)"]
                         ["B5" "=(xl/MOD 1 $A1)"]])]
      (is (= :div0  (code s "B1")) "Clojure's own divide by zero")
      (is (= :value (code s "B2")) "a cast failure")
      (is (= :num   (code s "B3")))
      (is (= :na    (code s "B4")))
      (is (= :div0  (code s "B5")) "and Excel's, through xl/")))
  (testing "the message survives alongside the code"
    (let [s (sheet-with [["A1" "=(fact -1)"]])]
      (is (= {:error "#NUM!" :code :num} (v s "A1")))))
  (testing "a reference to a removed column reports #REF!"
    ;; `remove-line!` is the user-facing delete (the one that invalidates
    ;; references); `delete-line!` is the undo of an insert and shifts them back.
    (let [s (sheet-with [["A1" "1"] ["B1" "2"] ["C1" "=(+ $A1 $B1)"]])]
      (sh/remove-line! s :col 1)
      (is (= :ref (code s "B1")) "C1 shifted into B1; the ref to the old B is gone"))))

(deftest branching-on-errors
  (testing "if-error catches a failure raised in the expression"
    (let [s (sheet-with [["A1" "0"] ["A2" "5"]
                         ["B1" "=(if-error (/ $A2 $A1) :fallback)"]
                         ["B2" "=(if-error (/ $A2 2.0) :fallback)"]])]
      (is (= :fallback (v s "B1")))
      (is (= 2.5 (v s "B2")) "and stays out of the way when nothing fails")))
  (testing "the importer's legacy thunk shape still works"
    ;; Saved sheets contain `(if-error (fn [] EXPR) fb)` from when if-error was
    ;; an ordinary function; the macro unwraps rather than double-wrapping it.
    (let [s (sheet-with [["A1" "0"] ["A2" "5"]
                         ["B1" "=(if-error (fn [] (/ $A2 $A1)) 0)"]
                         ["B2" "=(if-error (fn [] (/ $A2 $A1)) 0)"]])]
      (is (= 0 (v s "B1")))
      (is (= 0 (v s "B2")))))
  (testing "if-na catches ONLY a lookup miss — a real bug still surfaces"
    (let [s (sheet-with [["B1" "=(if-na (vlookup \"z\" (as-rows 2 [\"a\" 1]) 2 false) :missing)"]
                         ["B2" "=(if-na (/ 1 0) :missing)"]])]
      (is (= :missing (v s "B1")))
      (is (= :div0 (code s "B2")) "#DIV/0! is not #N/A — rethrown, not swallowed")))
  (testing "error-type and error? expose the code to the formula"
    (let [s (sheet-with [["B1" "=(error-type (/ 1 0))"]
                         ["B2" "=(error-type (+ 1 1))"]
                         ["B3" "=(error? (fact -1))"]
                         ["B4" "=(error? 42)"]
                         ["B5" "=(if (= :na (error-type (vlookup \"z\" (as-rows 2 [\"a\" 1]) 2 false))) \"miss\" \"hit\")"]])]
      (is (= :div0 (v s "B1")))
      (is (nil? (v s "B2")) "no error, no code")
      (is (true? (v s "B3")))
      (is (false? (v s "B4")))
      (is (= "miss" (v s "B5")))))
  (testing "a guard is reactive — it stops guarding when the failure goes away"
    (let [s (sheet-with [["A1" "0"] ["B1" "=(if-error (/ 10 $A1) :fallback)"]])]
      (is (= :fallback (v s "B1")))
      (sh/set-cell! s "A1" "2")
      (is (= 5 (v s "B1"))))))

(deftest propagation-is-not-catchable
  ;; Documents a real limitation rather than pretending it away: references are
  ;; hoisted and awaited BEFORE the body runs, so an error arriving from another
  ;; cell is not something `if-error` can intercept. Excel can, because there an
  ;; error is a VALUE that flows through operators. See TECHDEBT.
  (let [s (sheet-with [["A1" "=(fact -1)"]
                       ["B1" "=(+ $A1 1)"]
                       ["B2" "=(if-error (+ $A1 1) 0)"]])]
    (testing "an error does propagate to dependents, code intact"
      (is (= :num (code s "B1"))))
    (testing "but a guard around the reference cannot catch it (known gap)"
      (is (= :num (code s "B2"))
          "if this ever returns 0, errors became values — update TECHDEBT"))))
