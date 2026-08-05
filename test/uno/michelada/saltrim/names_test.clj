(ns uno.michelada.saltrim.names-test
  "Cells referenced BY NAME — `$sales`, where the name is a cell's `:label`.

   The label already existed; it titled a node in the dependency-graph view and
   meant nothing else. Making it addressable is the whole feature, and the
   design decision under test throughout is WHERE the name resolves: at PARSE,
   against the label index, so `deps` / the cycle check / the compiler all see
   the ordinary ref markers an address-written formula produces. That is what
   makes a name free at recompute time — and what makes a label MOVING the one
   event that has to rebuild anything."
  (:require [clojure.test :refer [deftest is testing]]
            [uno.michelada.saltrim.sheet :as sheet]))

(defn- with-sheet [f]
  (let [s (sheet/create-sheet)]
    (try (f s) (finally (sheet/close! s)))))

(defn- put! [s & kvs]
  (doseq [[a v] (partition 2 kvs)] (sheet/set-cell! s a v))
  s)

(defn- label! [s nm & addrs]
  (doseq [a addrs] (sheet/set-style! s a :label nm))
  s)

(deftest a-label-names-one-cell
  (with-sheet
    (fn [s]
      (put! s "A1" "100" "B1" "0.2")
      (label! s "rate" "B1")
      (put! s "C1" "=(* $A1 $rate)")
      (sheet/settle! s)
      (is (= 20.0 (sheet/value s "C1")))
      (testing "the SOURCE keeps the name; only the parsed form has the address"
        (is (= "=(* $A1 $rate)" (sheet/raw s "C1"))))
      (testing "and the dep is ordinary and static, like any other ref"
        (is (= #{"A1" "B1"} (get-in @(:meta s) ["C1" :deps])))
        (is (= #{"rate"} (get-in @(:meta s) ["C1" :names]))))
      (testing "so a value change through the name just propagates"
        (sheet/set-cell! s "B1" "0.5")
        (sheet/settle! s)
        (is (= 50.0 (sheet/value s "C1")))))))

(deftest the-same-label-on-several-cells-is-a-range
  (with-sheet
    (fn [s]
      (put! s "A1" "1" "A2" "2" "A3" "3")
      (label! s "sales" "A1" "A2" "A3")
      (put! s "C1" "=(sum $sales)" "C2" "=(count $sales)")
      (sheet/settle! s)
      (is (= 6 (sheet/value s "C1")))
      (is (= 3 (sheet/value s "C2")) "a multi-cell name is a vector, not a scalar")
      (testing "row-major, whatever order the labels were written in"
        (put! s "B1" "10" "B2" "20")
        (label! s "block" "B2" "B1")
        (is (= ["B1" "B2"] (sheet/labelled s "block")))))))

(deftest area-needs-a-real-rectangle
  (with-sheet
    (fn [s]
      (put! s "A1" "1" "B1" "2" "A2" "3" "B2" "4")
      (label! s "grid" "A1" "B1" "A2" "B2")
      (put! s "D1" "=(transpose #area grid)" "D2" "=(sum $grid)")
      (sheet/settle! s)
      (is (= [[1 3] [2 4]] (sheet/value s "D1"))
          "#area gives rows of rows, so shape-sensitive functions work")
      (is (= 10 (sheet/value s "D2")) "the flat form still sums")
      (testing "a scattered group has no shape, and says so"
        (label! s "odd" "A1" "B2")
        (put! s "D3" "=(sum #area odd)")
        (sheet/settle! s)
        (is (= :ref (:code (sheet/value s "D3"))))
        (is (re-find #"not a rectangle" (:error (sheet/value s "D3"))))))))

(deftest a-name-that-resolves-to-nothing-is-NAME-not-a-refused-edit
  ;; Excel's behaviour, and the reason it matters here: you must be able to
  ;; write the formula before labelling the cell, and a formula must survive its
  ;; label being removed — `:nreaders` is what re-installs it when the name
  ;; comes back, and a refused write would never have been indexed.
  (with-sheet
    (fn [s]
      (put! s "A1" "2" "C1" "=(* $A1 $rate)")
      (sheet/settle! s)
      (is (= :name (:code (sheet/value s "C1"))))
      (testing "and it starts working the moment the label appears"
        (put! s "B1" "10")
        (label! s "rate" "B1")
        (sheet/settle! s)
        (is (= 20 (sheet/value s "C1")))))))

(deftest a-label-that-moves-takes-its-formulas-with-it
  (with-sheet
    (fn [s]
      (put! s "A1" "10" "A2" "99")
      (label! s "rate" "A1")
      (put! s "C1" "=(* 2 $rate)")
      (sheet/settle! s)
      (is (= 20 (sheet/value s "C1")))
      (testing "moving the label re-resolves the formula, deps and all"
        (label! s "" "A1")
        (label! s "rate" "A2")
        (sheet/settle! s)
        (is (= 198 (sheet/value s "C1")))
        (is (= #{"A2"} (get-in @(:meta s) ["C1" :deps]))
            "the OLD address must be out of the dependency graph"))
      (testing "and removing it leaves a #NAME? cell that keeps its formula"
        (label! s "" "A2")
        (sheet/settle! s)
        (is (= :name (:code (sheet/value s "C1"))))
        (is (= "=(* 2 $rate)" (sheet/raw s "C1")))))))

(deftest an-address-always-wins
  ;; `$q1` is column Q row 1 before it is anybody's label — otherwise which one
  ;; a formula meant would depend on the sheet's labels at the time it was typed
  (with-sheet
    (fn [s]
      (put! s "Q1" "7" "A1" "1")
      (label! s "q1" "A1")
      (put! s "C1" "=$q1")
      (sheet/settle! s)
      (is (= 7 (sheet/value s "C1")))
      (is (= #{"Q1"} (get-in @(:meta s) ["C1" :deps])))
      (is (nil? (get-in @(:meta s) ["C1" :names]))))))

(deftest only-a-literal-label-is-a-name
  ;; a computed label would mean any cell's edit could restructure formulas
  ;; elsewhere — the exact cost resolving at parse exists to avoid
  (with-sheet
    (fn [s]
      (put! s "A1" "1" "B1" "5")
      (sheet/set-style! s "B1" :label "=(str \"ra\" \"te\")")
      (put! s "C1" "=(* $A1 $rate)")
      (sheet/settle! s)
      (is (= :name (:code (sheet/value s "C1"))))
      (is (nil? (sheet/labelled s "rate")))
      (testing "but it still shows in the graph view"
        (is (= "rate" (sheet/style-value s "B1" :label)))))))

(deftest names-survive-a-reload
  ;; labels are STYLE props and styles load last, so without indexing them
  ;; first every named formula would fail to resolve exactly once — on the load
  ;; meant to restore it
  (with-sheet
    (fn [s]
      (put! s "A1" "4")
      (label! s "rate" "A1")
      (put! s "C1" "=(* 3 $rate)")
      (sheet/settle! s)
      (is (= 12 (sheet/value s "C1")))
      (let [doc (sheet/document s)]
        (with-sheet
          (fn [s2]
            (sheet/load-document! s2 doc)
            (sheet/settle! s2)
            (is (= 12 (sheet/value s2 "C1")))
            (is (= ["A1"] (sheet/labelled s2 "rate")))))))))

(deftest a-name-cannot-hide-a-cycle
  ;; resolution happens before the cycle check, so a cycle written by name is
  ;; refused exactly like one written by address
  (with-sheet
    (fn [s]
      (put! s "A1" "1")
      (label! s "self" "A1")
      (is (thrown-with-msg? Exception #"circular"
                            (sheet/set-cell! s "A1" "=(+ 1 $self)"))))))
