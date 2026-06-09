(ns uno.michelada.clorax.store-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [uno.michelada.clorax.sheet :as sheet]
            [uno.michelada.clorax.store :as store]))

(def ^:private id "test_roundtrip")

(defn- cleanup [] (io/delete-file (io/file "data" (str id ".edn")) true))

(deftest valid-id
  (is (store/valid-id? "default"))
  (is (store/valid-id? "tenant_42-x"))
  (is (not (store/valid-id? "../etc/passwd")))
  (is (not (store/valid-id? "a/b")))
  (is (not (store/valid-id? ""))))

(deftest roundtrip
  (cleanup)
  (try
    (let [s (sheet/create-sheet)]
      (sheet/set-cell! s "A1" "10")
      (sheet/set-cell! s "A2" "20")
      (sheet/set-cell! s "A3" "=(reduce + #cells A1:A2)")   ; range formula
      (sheet/set-cell! s "B1" "=(* #cell A3 2)")            ; chained formula
      (sheet/set-cell! s "C1" "hello")
      (sheet/settle! s)
      (store/save! id s))
    (testing "reload rebuilds values, formulas, chains"
      (let [s2 (store/load-sheet id)]
        (sheet/settle! s2)
        (is (= 10 (sheet/value s2 "A1")))
        (is (= 30 (sheet/value s2 "A3")) "range formula recomputed")
        (is (= 60 (sheet/value s2 "B1")) "chained formula recomputed")
        (is (= "hello" (sheet/value s2 "C1")))
        (testing "reloaded sheet is live: edit propagates"
          (sheet/set-cell! s2 "A1" "100")
          (sheet/settle! s2)
          (is (= 120 (sheet/value s2 "A3")))
          (is (= 240 (sheet/value s2 "B1"))))))
    (finally (cleanup))))

(deftest load-missing-nil
  (is (nil? (store/load-sheet "no_such_sheet_xyz"))))
