(ns uno.michelada.saltrim.deleteline-test
  "Deleting a row/column, as a user means it.

   The trap this ns exists for: a delete is NOT an insert with a negative delta.
   Inserting a line can only MOVE the cell a reference points at; deleting one
   can DESTROY it. Shifting such a reference back by one leaves a formula that
   still computes and silently reads a different cell — the worst outcome
   available — so it becomes #REF! instead."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sh]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [s (sh/create-sheet)]
                        (try (binding [*sh* s] (t))
                             (finally (sh/close! s))))))

(defn- v [a] (sh/settle! *sh*) (sh/value *sh* a))

(deftest delete-shift-rewrites-references
  (testing "a reference BEFORE the deleted line is untouched"
    (is (= "(+ $A1 1)" (formula/delete-shift "(+ $A1 1)" :row 2))))
  (testing "a reference PAST it shifts back"
    (is (= "(+ $A5 1)" (formula/delete-shift "(+ $A6 1)" :row 2)))
    (is (= "(+ #cell A5 1)" (formula/delete-shift "(+ #cell A6 1)" :row 2))))
  (testing "a reference AT it is destroyed, not re-pointed"
    ;; the whole point: $A3 must not quietly become $A2
    (is (= "(+ (deleted-ref \"A3\") 1)" (formula/delete-shift "(+ $A3 1)" :row 2)))
    (is (= "(+ (deleted-ref \"A3\") 1)" (formula/delete-shift "(+ #cell A3 1)" :row 2))))
  (testing "columns work the same way"
    (is (= "(+ (deleted-ref \"C1\") 1)" (formula/delete-shift "(+ $C1 1)" :col 2)))
    (is (= "(+ $C1 1)" (formula/delete-shift "(+ $D1 1)" :col 2))))
  (testing "a range loses exactly the one cell that was deleted"
    ;; the two ends are not symmetric: a start on the line stays (it now names
    ;; the cell that slid in), an end on the line steps back
    (is (= "(sum $A1:A4)" (formula/delete-shift "(sum $A1:A5)" :row 2)))
    (is (= "(sum #cells A1:A4)" (formula/delete-shift "(sum #cells A1:A5)" :row 2)))
    (is (= "(sum $A1:A2)" (formula/delete-shift "(sum $A1:A3)" :row 2)) "end ON the line")
    (is (= "(sum $A3:A4)" (formula/delete-shift "(sum $A3:A5)" :row 2)) "start ON the line"))
  (testing "a range typed backwards keeps its written order"
    (is (= "(sum $A5:A1)" (formula/delete-shift "(sum $A6:A1)" :row 2))))
  (testing "a range that was ONLY the deleted line is destroyed"
    (is (= "(sum (deleted-ref \"A3:A3\"))" (formula/delete-shift "(sum $A3:A3)" :row 2))))
  (testing "non-formula text and nil pass through"
    (is (= "plain" (formula/delete-shift "plain" :row 2)))
    (is (nil? (formula/delete-shift nil :row 2)))))

(deftest deleting-a-row-shifts-cells-and-breaks-refs-to-it
  (sh/set-cell! *sh* "A1" "1")
  (sh/set-cell! *sh* "A2" "2")
  (sh/set-cell! *sh* "A3" "3")
  (sh/set-cell! *sh* "B1" "=(+ $A3 100)")     ; points AT the row we delete
  (sh/set-cell! *sh* "B2" "=(sum $A1:A3)")    ; straddles it
  (sh/settle! *sh*)
  (is (= 103 (v "B1")))
  (is (= 6 (v "B2")))
  (sh/remove-line! *sh* :row 2)                ; delete row 3
  (testing "the deleted row is gone and nothing moved into a wrong place"
    (is (= 1 (v "A1")))
    (is (= 2 (v "A2")))
    (is (nil? (v "A3"))))
  (testing "the reference TO it errors instead of reading A2"
    (let [r (v "B1")]
      (is (:error r))
      (is (re-find #"#REF!" (:error r)))
      (is (re-find #"A3" (:error r)) "and names what was lost")))
  (testing "the range that straddled it just shrank"
    (is (= "=(sum $A1:A2)" (sh/raw *sh* "B2")))
    (is (= 3 (v "B2")))))

(deftest deleting-a-column-shifts-cells-back
  (sh/set-cell! *sh* "A1" "1")
  (sh/set-cell! *sh* "B1" "2")
  (sh/set-cell! *sh* "C1" "3")
  (sh/settle! *sh*)
  (sh/remove-line! *sh* :col 1)                ; delete column B
  (is (= 1 (v "A1")))
  (is (= 3 (v "B1")) "C slid left into B")
  (is (nil? (v "C1"))))

(deftest a-delete-is-undoable-with-its-content
  ;; restoring the geometry but not the cells would be a silent data loss, so the
  ;; undo entry carries the line it removed
  (sh/set-cell! *sh* "A1" "keep")
  (sh/set-cell! *sh* "A2" "doomed")
  (sh/set-style! *sh* "A2" :bg "tomato")
  (sh/set-cell! *sh* "A3" "after")
  (sh/settle! *sh*)
  (let [cells (sh/remove-line! *sh* :row 1)]
    (is (= {"A2" {:value "doomed" :style {:bg "tomato"}}} cells) "the line was captured")
    (is (= "after" (v "A2")) "A3 slid up")
    (testing "undo puts the row back, content and styling included"
      (let [{:keys [affected]} (sh/undo-step *sh* {:undo [{:op :delete :axis :row :at 1 :cells cells}]
                                                   :redo []}
                                             :undo)]
        (is (= :all affected))
        (is (= "keep" (v "A1")))
        (is (= "doomed" (v "A2")))
        (is (= "tomato" (sh/style-value *sh* "A2" :bg)))
        (is (= "after" (v "A3")))))))

(deftest undo-also-repairs-the-references-the-delete-rewrote
  ;; the half-undo this ns exists to prevent: `delete-shift` is NOT invertible —
  ;; nothing turns (deleted-ref "A3") back into $A3, or grows $A1:A2 back to
  ;; $A1:A3 — so the snapshot has to cover every formula the delete touched, not
  ;; only the line itself. Otherwise undo puts the row visibly back while every
  ;; formula that referenced it stays broken, which LOOKS repaired.
  (sh/set-cell! *sh* "A1" "1")
  (sh/set-cell! *sh* "A2" "2")
  (sh/set-cell! *sh* "A3" "3")
  (sh/set-cell! *sh* "B1" "=(+ $A3 100)")     ; points AT the doomed row
  (sh/set-cell! *sh* "B2" "=(sum $A1:A3)")    ; straddles it
  (sh/set-cell! *sh* "B5" "=(* 2 2)")         ; below the line, no refs -> untouched
  (sh/settle! *sh*)
  (let [snap (sh/remove-line! *sh* :row 2)]
    (is (= #{"A3" "B1" "B2"} (set (keys snap)))
        "the line, plus every formula the rewrite touched — and nothing else")
    (is (:error (v "B1")))
    (sh/restore-line! *sh* :row 2 snap)
    (testing "everything is back, including the formulas"
      (is (= 3 (v "A3")))
      (is (= "=(+ $A3 100)" (sh/raw *sh* "B1")))
      (is (= 103 (v "B1")) "the #REF! is gone, not just hidden")
      (is (= "=(sum $A1:A3)" (sh/raw *sh* "B2")))
      (is (= 6 (v "B2")))
      (is (= 4 (v "B5")) "a formula the delete never touched is unaffected"))))

(deftest redo-of-a-delete-re-captures-the-line
  ;; redoing must re-capture, or a SECOND undo would restore only the gap
  (sh/set-cell! *sh* "A1" "keep")
  (sh/set-cell! *sh* "A2" "doomed")
  (sh/settle! *sh*)
  (let [cells (sh/remove-line! *sh* :row 1)
        entry {:op :delete :axis :row :at 1 :cells cells}
        step1 (sh/undo-step *sh* {:undo [entry] :redo []} :undo)
        step2 (sh/undo-step *sh* (:stacks step1) :redo)]
    (is (nil? (v "A2")) "redo deleted it again")
    (let [step3 (sh/undo-step *sh* (:stacks step2) :undo)]
      (is (= :all (:affected step3)))
      (is (= "doomed" (v "A2")) "and undoing again still restores the content"))))

(deftest an-insert-is-still-its-own-inverse
  ;; the insert path is unchanged: the line it adds is blank, so references
  ;; simply shift back and nothing needs capturing
  (sh/set-cell! *sh* "A1" "1")
  (sh/set-cell! *sh* "B1" "=(+ $A1 1)")
  (sh/settle! *sh*)
  (sh/insert-line! *sh* :row 0)
  (is (= "=(+ $A2 1)" (sh/raw *sh* "B2")) "the reference followed the insert")
  (sh/delete-line! *sh* :row 0)
  (is (= "=(+ $A1 1)" (sh/raw *sh* "B1")) "and back")
  (is (= 2 (v "B1"))))
