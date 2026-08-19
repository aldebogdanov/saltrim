(ns uno.michelada.saltrim.paste-test
  "Clipboard paste tiling: a copied clip fills the whole target SELECTION, not
   just its top-left cell (the bug: Ctrl+V into a range pasted one cell only)."
  (:require [clojure.test :refer [deftest is testing]]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.handlers :as h]))

(defn- paste-into!
  "Tile `clip` across target `sel` by calling the HANDLER'S OWN tiling, not a
   copy of it. This harness used to re-implement it, and a bounding-box bug in
   the real one was therefore invisible to a green suite — ⌘-clicking two
   distant cells pasted over everything between them.
   Mutates `s`. (sid absent -> undo records no-op.)"
  [s clip sel]
  (#'h/paste-ranges! s "no-session" clip (#'h/selected-ranges sel))
  (sheet/settle! s))

(deftest tile-origins
  (let [t #'h/tile-origins]
    (testing "a 1x1 clip fills every cell of a row range"
      (is (= [[7 0] [8 0] [9 0] [10 0] [11 0] [12 0]] (t 7 0 12 0 1 1))))
    (testing "single target cell -> one paste"
      (is (= [[7 0]] (t 7 0 7 0 1 1))))
    (testing "a 2x2 clip tiles a 4x4 target"
      (is (= [[0 0] [2 0] [0 2] [2 2]] (t 0 0 3 3 2 2))))
    (testing "target smaller than the clip -> one paste (so a block pastes whole)"
      (is (= [[5 5]] (t 5 5 5 5 3 3))))))

(deftest a-multi-range-selection-fills-only-what-is-selected
  ;; THE BUG: the handler reduced the selection to its bounding box and tiled
  ;; across that. ⌘-clicking two distant cells is TWO selected cells — all that
  ;; MAX-SEL-CELLS ever counted — and an entire rectangle of write targets.
  ;; B2 and J20 wrote 171 cells; A1 and XFD1048576 would have written 17 billion.
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "X")
    (sheet/settle! s)
    (let [clip (#'h/capture-clip s "A1:A1")]
      (paste-into! s clip "B2:B2 J20:J20")
      (testing "both selected cells get the clip"
        (is (= "X" (sheet/value s "B2")))
        (is (= "X" (sheet/value s "J20"))))
      (testing "and NOTHING between them is touched"
        (is (nil? (sheet/value s "E10")))
        (is (nil? (sheet/value s "C3")))
        (is (nil? (sheet/value s "I19")))
        (is (= #{"A1" "B2" "J20"} (set (sheet/cells s)))
            "exactly the source and the two targets")))
    (sheet/close! s)))

(deftest each-selected-range-is-tiled-on-its-own
  ;; two real rectangles, not two lone cells: each fills, the gap does not
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "7")
    (sheet/settle! s)
    (let [clip (#'h/capture-clip s "A1:A1")]
      (paste-into! s clip "C1:D2 G1:G2")
      (is (every? #(= "7" (str (sheet/value s %))) ["C1" "D1" "C2" "D2" "G1" "G2"]))
      (is (nil? (sheet/value s "E1")) "the gap between the ranges stays empty")
      (is (nil? (sheet/value s "F2"))))
    (sheet/close! s)))

(deftest the-selection-cap-now-bounds-the-writes
  ;; the cap counts CELLS; tiling per range means stamps can never outnumber
  ;; them, which is what makes the cap meaningful for paste at all
  ;; two lone cells at opposite corners are well UNDER the cap — which was the
  ;; whole trap: the cap passed them, and the bounding box between them was
  ;; seventeen billion write targets
  (let [ranges (#'h/selected-ranges "A1:A1 XFD1048576:XFD1048576")]
    (is (= [["A1" "A1"] ["XFD1048576" "XFD1048576"]] ranges))
    (is (= 2 (count (mapcat (fn [[a b]] (addr/range-cells a b)) ranges)))
        "two write targets, not the rectangle between them")))

(deftest capture-records-footprint
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "B2" "9")
    (let [clip (#'h/capture-clip s "B2:D4")]
      (is (= 3 (:w clip)) "selection width incl. empties")
      (is (= 3 (:h clip)) "selection height incl. empties")
      (is (= [1 1] (:origin clip)) "B2 -> [ci ri] = [1 1]"))))

(deftest paste-fills-selection
  ;; the reported bug, end to end: copy one Fibonacci formula cell, paste it into
  ;; a row range -> the whole range fills (relative refs re-resolve per cell).
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "0")
    (sheet/set-cell! s "B1" "1")
    (sheet/set-cell! s "C1" "=(+ $-2_ $-1_)")
    (sheet/settle! s)
    (let [clip (#'h/capture-clip s "C1:C1")]
      (is (= [1 1] [(:w clip) (:h clip)]) "single copied cell")
      ;; paste into D1:H1 (sid not in sessions* -> record-edit! just no-ops)
      (doseq [[tc tr] (#'h/tile-origins 3 0 7 0 (:w clip) (:h clip))]
        (#'h/paste-cells! s "no-session" clip tc tr))
      (sheet/settle! s)
      (is (= [0 1 1 2 3 5 8 13]
             (mapv #(sheet/value s (str % "1")) ["A" "B" "C" "D" "E" "F" "G" "H"]))
          "the formula filled the range, not just the first cell"))))

(deftest paste-carries-styles
  ;; the reported follow-up: paste must bring the cell's STYLES, not just value.
  (let [s (sheet/create-sheet)]
    (sheet/set-cell! s "A1" "7")
    (sheet/set-style! s "A1" :bg "cyan")
    (sheet/set-style! s "A1" :format "0.00")
    (sheet/settle! s)
    (let [clip (#'h/capture-clip s "A1:A1")]
      (is (= {:bg "cyan" :format "0.00"} (:styles (first (:cells clip)))) "styles captured")
      (paste-into! s clip "A2:A4")
      (is (= ["cyan" "cyan" "cyan"] (mapv #(sheet/style-value s % :bg) ["A2" "A3" "A4"])) "bg fills")
      (is (= "0.00" (sheet/style-value s "A4" :format)) "format fills")
      (is (= 7.0 (double (sheet/value s "A4"))) "value fills too"))))

(deftest paste-clips-to-selection
  ;; 3-cell column into a 10-cell range: fills EXACTLY the range, no overhang.
  (let [s (sheet/create-sheet)]
    (doseq [r (range 1 4)] (sheet/set-cell! s (str "A" r) (str (* 10 r))))
    (sheet/settle! s)
    (paste-into! s (#'h/capture-clip s "A1:A3") "C1:C10")
    (is (= [10 20 30 10 20 30 10 20 30 10]
           (mapv #(sheet/value s (str "C" %)) (range 1 11))) "C1:C10 filled (last tile clipped)")
    (is (nil? (sheet/value s "C11")) "no overhang past the selection")
    (is (nil? (sheet/value s "C12")))))

(deftest square-fills-bigger-square
  ;; copy a 2x2 block, fill a 4x4 selection — clean tiling, clipped to selection.
  (let [s (sheet/create-sheet)]
    (doseq [[a v] [["A1" "1"]["B1" "2"]["A2" "3"]["B2" "4"]]] (sheet/set-cell! s a v))
    (sheet/settle! s)
    (paste-into! s (#'h/capture-clip s "A1:B2") "D1:G4")
    (is (= [[1 2 1 2] [3 4 3 4] [1 2 1 2] [3 4 3 4]]
           (for [r [1 2 3 4]] (mapv #(sheet/value s (str % r)) ["D" "E" "F" "G"])))
        "2x2 tiles cleanly across 4x4")
    (is (nil? (sheet/value s "H1")) "no horizontal overhang")))

(deftest block-pasted-at-single-cell-lands-whole
  ;; copy a 2x3 block, paste at ONE target cell -> the whole block lands (unclipped).
  (let [s (sheet/create-sheet)]
    (doseq [[a v] [["A1" "10"]["A2" "20"]["A3" "30"]["B1" "40"]["B2" "50"]["B3" "60"]]]
      (sheet/set-cell! s a v))
    (sheet/settle! s)
    (paste-into! s (#'h/capture-clip s "A1:B3") "E5:E5")
    (is (= [[10 40] [20 50] [30 60]]
           (mapv (fn [r] [(sheet/value s (str "E" r)) (sheet/value s (str "F" r))]) [5 6 7]))
        "full 2x3 block pasted at the single target cell E5")))
