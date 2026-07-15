(ns uno.michelada.saltrim.geom-test
  "The rendered window is a PX budget, not a cell count: it has to track the
   sheet's default cell size and the client's own viewport, or the grid comes up
   short of the viewport (empty strip to the right / below)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.constants :refer [CW MAX-WIN-COLS MAX-WIN-ROWS RH WIN-COLS WIN-ROWS OVER]]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.geom :as geom]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(defn- cols [view] (count (first (geom/window *sh* view))))
(defn- rows [view] (count (second (geom/window *sh* view))))

(deftest window-covers-the-same-px-at-any-default-size
  (let [view {:r0 10 :c0 10}]
    (testing "at the default cell size the window is the calibrated count + overscan"
      (is (= [WIN-COLS WIN-ROWS] (geom/win-dims *sh* view)))
      (is (= (+ WIN-COLS OVER OVER) (cols view)))
      (is (= (+ WIN-ROWS OVER OVER) (rows view))))
    (testing "halving the default column width doubles the columns rendered"
      (sheet/set-default-col-w! *sh* (quot CW 2))
      (is (= (* 2 WIN-COLS) (first (geom/win-dims *sh* view))))
      (is (= (+ (* 2 WIN-COLS) OVER OVER) (cols view))))
    (testing "and a wider default renders fewer — same px, less work"
      (sheet/set-default-col-w! *sh* (* 2 CW))
      (is (= (quot WIN-COLS 2) (first (geom/win-dims *sh* view)))))
    (testing "rows scale off the default row height the same way"
      (sheet/set-default-row-h! *sh* (quot RH 2))
      (is (= (* 2 WIN-ROWS) (second (geom/win-dims *sh* view)))))))

(deftest client-measurement-wins-over-the-guess
  ;; only the browser knows its viewport; $wc/$wr override the px-budget fallback
  (is (= [40 7] (geom/win-dims *sh* {:r0 0 :c0 0 :wc 40 :wr 7})))
  (is (= (+ 40 OVER OVER) (cols {:r0 0 :c0 0 :wc 40 :wr 7})))
  (testing "0 = not measured yet -> fall back to the server's own guess"
    (is (= [WIN-COLS WIN-ROWS] (geom/win-dims *sh* {:r0 0 :c0 0 :wc 0 :wr 0}))))
  (testing "a client can't make us render the whole grid"
    (is (= [MAX-WIN-COLS MAX-WIN-ROWS]
           (geom/win-dims *sh* {:r0 0 :c0 0 :wc 99999 :wr 99999}))))
  (testing "nonsense from a client falls back to the guess"
    (is (= [WIN-COLS WIN-ROWS] (geom/win-dims *sh* {:r0 0 :c0 0 :wc -5 :wr -5})))
    (is (= [WIN-COLS WIN-ROWS] (geom/win-dims *sh* {:r0 0 :c0 0 :wc "x" :wr nil})))))

(deftest in-window-matches-what-window-renders
  ;; a pushed cell outside the peer's rendered window patches nothing, so the
  ;; filter must not be tighter than the render
  (doseq [view [{:r0 0 :c0 0} {:r0 10 :c0 10 :wc 40 :wr 7}]]
    (let [[cis ris] (geom/window *sh* view)]
      (is (every? #(geom/in-window? *sh* view %)
                  (for [ci [(first cis) (last cis)] ri [(first ris) (last ris)]]
                    (uno.michelada.saltrim.addr/make ci ri)))
          (str "every rendered corner is in-window for " view)))))

(deftest window-clamps-at-the-grid-origin
  (is (= 0 (first (first (geom/window *sh* {:r0 0 :c0 0}))))
      "no negative indices at the top-left"))
