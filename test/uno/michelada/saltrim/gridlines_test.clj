(ns uno.michelada.saltrim.gridlines-test
  "The grid is its own layer under the cells.

   It used to be four borders on every cell, with each cell drawn a pixel
   smaller than its slot — so between two neighbours sat a border, a 1px gap
   showing the page through, and the next border. Invisible on an empty grid;
   on two cells sharing a `bg` it was a three-pixel scar through one block of
   colour. These tests pin both halves: the lines land on exactly the pixels the
   old borders did, and a cell now covers its whole slot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [uno.michelada.saltrim.constants :refer [CW RH]]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.render :as render]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(defn- lefts [html] (map #(parse-long (second %)) (re-seq #"left:(-?\d+)px" html)))
(defn- tops  [html] (map #(parse-long (second %)) (re-seq #"top:(-?\d+)px" html)))

(deftest a-cell-fills-its-whole-slot
  (sheet/set-cell! *sh* "A1" "x")
  (sheet/set-cell! *sh* "B1" "y")
  (let [html (render/cells-html *sh* (range 0 2) (range 0 1))]
    (is (str/includes? html (str "width:" CW "px;height:" RH "px"))
        "not (dec CW) — the gap between neighbours is gone")
    (testing "so the two cells abut exactly, with nothing between them"
      (is (= [0 CW] (lefts html))))))

(deftest lines-land-where-the-old-borders-did
  ;; the old seam at a boundary X was: neighbour's right border [X-2,X-1), a 1px
  ;; gap [X-1,X), then this cell's left border [X,X+1). One 3px element with a
  ;; left and right border and a transparent middle is that, exactly.
  (let [html (render/gridlines-html *sh* (range 0 3) (range 0 2))]
    (is (= [-2 (- CW 2) (- (* 2 CW) 2) (- (* 3 CW) 2)] (lefts html))
        "a vertical band per column boundary, incl. the far edge of the last")
    (is (= [-2 (- RH 2) (- (* 2 RH) 2)] (tops html)))
    (is (str/includes? html "class=\"gl gv\""))
    (is (str/includes? html "class=\"gl gh\""))
    (testing "the bands span the whole rendered window"
      ;; 3 columns x 2 rows here
      (is (str/includes? html (str "height:" (* 2 RH) "px")) "vertical bands are full height")
      (is (str/includes? html (str "width:" (* 3 CW) "px")) "horizontal bands are full width"))))

(deftest lines-follow-resized-columns-and-rows
  (sheet/set-col-width! *sh* 0 50)
  (sheet/set-row-height! *sh* 0 40)
  (let [html (render/gridlines-html *sh* (range 0 2) (range 0 2))]
    (is (= [-2 48 (- (+ 50 CW) 2)] (lefts html)))
    (is (= [-2 38 (- (+ 40 RH) 2)] (tops html)))))

(deftest one-element-per-boundary-not-per-cell
  ;; the old borders cost four edges on every cell; a 40x30 window is 1200 cells
  ;; against 72 boundaries
  (let [n (count (re-seq #"class=\"gl" (render/gridlines-html *sh* (range 0 40) (range 0 30))))]
    (is (= (+ 41 31) n))))

(deftest a-styled-cell-paints-over-the-lines
  (sheet/set-cell! *sh* "A1" "1")
  (sheet/set-cell! *sh* "B1" "2")
  (doseq [a ["A1" "B1"]] (sheet/set-style! *sh* a :bg "gold"))
  (sheet/settle! *sh*)
  (let [html (render/cells-html *sh* (range 0 2) (range 0 1))]
    (is (= 2 (count (re-seq #"background-color:gold" html))))
    (is (not (str/includes? html "border:1px solid var(--grid)"))
        "a cell draws no border of its own — that is what used to split the colour")
    (testing "and the two coloured slots are contiguous"
      (is (= [0 CW] (lefts html))))))
