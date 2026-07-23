(ns uno.michelada.saltrim.mergecell-test
  "Merged cells: a cell swallows its neighbours into one big cell keeping the
   top-left (anchor) address. A `:merge` \"<rows>x<cols>\" span prop on the anchor
   drives it — presentational, non-destructive (covered cells keep their data),
   and it rides the ordinary style/cellprop plumbing (so it persists + branches +
   undoes for free)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.geom :as geom]
            [uno.michelada.saltrim.web.render :as render]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(defn- merge! [anchor span] (sheet/set-style! *sh* anchor sheet/merge-prop span))

(deftest parse-span-accepts-only-real-spans
  (is (= [2 3] (sheet/parse-span "2x3")))
  (is (= [1 4] (sheet/parse-span "1x4")))
  (is (= [4 1] (sheet/parse-span "4x1")))
  (testing "a 1×1 merge is a no-op, so it is not a span"
    (is (nil? (sheet/parse-span "1x1"))))
  (testing "garbage / blank -> nil"
    (is (nil? (sheet/parse-span "")))
    (is (nil? (sheet/parse-span nil)))
    (is (nil? (sheet/parse-span "2X3")))     ; uppercase X isn't the separator
    (is (nil? (sheet/parse-span "2x")))
    (is (nil? (sheet/parse-span "axb")))
    (is (nil? (sheet/parse-span "0x3")))))    ; a zero-sized side is meaningless

(deftest merge-spans-reads-anchor-props
  (merge! "B2" "2x3")
  (merge! "F1" "3x1")
  (is (= {"B2" [2 3] "F1" [3 1]} (sheet/merge-spans *sh*)))
  (testing "a blank/garbage span is ignored, not surfaced as a merge"
    (merge! "Z9" "1x1")
    (is (nil? (get (sheet/merge-spans *sh*) "Z9")))))

(deftest covered-is-the-rectangle-minus-the-anchor
  (merge! "B2" "2x3")                          ; B2 anchor, 2 rows × 3 cols -> B2..D3
  (let [cov (geom/covered (sheet/merge-spans *sh*))]
    (is (= #{"C2" "D2" "B3" "C3" "D3"} cov))
    (is (not (contains? cov "B2")) "the anchor itself is never covered")
    (is (not (contains? cov "E2")) "a cell just past the block is free")))

(deftest block-of-finds-the-covering-anchor
  (merge! "B2" "2x3")
  (let [anchors (sheet/merge-spans *sh*)]
    (is (= ["B2" 2 3] (geom/block-of anchors 1 1)) "the anchor is in its own block")
    (is (= ["B2" 2 3] (geom/block-of anchors 3 2)) "D3 (bottom-right) resolves to B2")
    (is (nil? (geom/block-of anchors 4 1)) "E2 is outside the block")
    (is (nil? (geom/block-of anchors 0 0)) "A1 is outside the block")))

(deftest span-px-sums-real-sizes
  (let [dcw (sheet/default-col-w *sh*) drh (sheet/default-row-h *sh*)]
    (is (= [(* 3 dcw) (* 2 drh)] (geom/span-px *sh* 1 1 2 3)) "uniform sizes multiply")
    (testing "a resized column inside the span is honored"
      (sheet/set-col-width! *sh* 2 (+ dcw 40))   ; column C wider by 40
      (is (= [(+ (* 3 dcw) 40) (* 2 drh)] (geom/span-px *sh* 1 1 2 3))))))

(deftest covered-cells-keep-their-data
  ;; merge is presentational: the swallowed cells' values survive, they are just
  ;; hidden — so a formula referencing one still works.
  (sheet/set-cell! *sh* "B2" "top-left")
  (sheet/set-cell! *sh* "C2" "5")
  (sheet/set-cell! *sh* "E2" "=(* $C2 2)")
  (sheet/settle! *sh*)
  (merge! "B2" "1x3")                           ; B2 swallows C2, D2
  (is (contains? (geom/covered (sheet/merge-spans *sh*)) "C2"))
  (is (= 5 (sheet/value *sh* "C2")) "hidden cell keeps its value")
  (is (= 10 (sheet/value *sh* "E2")) "a formula over a covered cell still computes"))

(deftest merge-round-trips-through-the-document
  ;; the span rides the style layer, so save -> load restores it verbatim
  (sheet/set-cell! *sh* "B2" "hi")
  (merge! "B2" "2x2")
  (let [doc (sheet/document *sh*)]
    (is (= "2x2" (get-in doc ["B2" :style :merge])) "span serialized under :style")
    (let [sh2 (sheet/create-sheet)]
      (try
        (sheet/load-document! sh2 doc)
        (is (= {"B2" [2 2]} (sheet/merge-spans sh2)) "restored on load")
        (finally (sheet/close! sh2))))))

(deftest undo-of-a-merge-re-renders-the-whole-window
  ;; a merge/unmerge changes the covered set + the anchor's footprint, which a
  ;; per-cell patch can't express — so undo-step must report :affected :all (like
  ;; a structural row/col insert), not just the anchor address.
  (merge! "B2" "2x3")
  (let [entry {:addr "B2" :prop sheet/merge-prop :before nil :after "2x3"}
        {:keys [affected]} (sheet/undo-step *sh* {:undo [entry] :redo []} :undo)]
    (is (= :all affected) "undoing a merge re-renders the whole window")
    (is (empty? (sheet/merge-spans *sh*)) "and the span was actually cleared"))
  (testing "a plain style prop still reports just its own address"
    (sheet/set-style! *sh* "A1" :bg "red")
    (let [entry {:addr "A1" :prop :bg :before nil :after "red"}
          {:keys [affected]} (sheet/undo-step *sh* {:undo [entry] :redo []} :undo)]
      (is (= ["A1"] affected)))))

(deftest rendering-hides-covered-and-spans-the-anchor
  (sheet/set-cell! *sh* "B2" "wide")
  (merge! "B2" "1x3")                           ; B2..D2
  (let [dcw (sheet/default-col-w *sh*)
        html (render/cells-html *sh* (range 0 6) (range 0 3))]
    (is (str/includes? html "id=\"c_B2\"") "the anchor is rendered")
    (is (not (str/includes? html "id=\"c_C2\"")) "a covered cell is not rendered")
    (is (not (str/includes? html "id=\"c_D2\"")) "a covered cell is not rendered")
    (is (str/includes? html "id=\"c_E2\"") "the cell past the block is still rendered")
    ;; a cell now fills its WHOLE slot (the grid lines are their own layer under
    ;; it), so the anchor covers three full columns rather than stopping a pixel
    ;; short of the third
    (is (str/includes? html (str "width:" (* 3 dcw) "px"))
        "the anchor spans three columns wide")
    (is (str/includes? html "class=\"cell merged\"") "the anchor is flagged merged")))
