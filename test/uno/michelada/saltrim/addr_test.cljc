(ns uno.michelada.saltrim.addr-test
  "`addr` is shared VERBATIM by the server and the browser, so it is tested on
   both. Everything here is deliberately platform-blind: the same assertions run
   under `clojure -X:test` and under `clojure -M:cljs-test`, because the ways
   this namespace can differ between them are silent ones — `(int char)` is a
   bit-or in ClojureScript, `Long/parseLong` has no counterpart, and a column
   letter that converts differently on the client points every cell at the wrong
   address without anything throwing."
  (:require #?(:clj  [clojure.test :refer [deftest testing is are]]
               :cljs [cljs.test :refer-macros [deftest testing is are]])
            [uno.michelada.saltrim.addr :as a]))

(deftest col-idx-roundtrip
  (are [col idx] (and (= idx (a/col->idx col)) (= col (a/idx->col idx)))
    "A" 0  "Z" 25  "AA" 26  "AB" 27  "AAB" 703))

(deftest parse-make
  (is (= {:col "AAB" :row 1234 :ci 703 :ri 1233} (a/parse "AAB1234")))
  (is (= "A1" (a/make 0 0)))
  (is (= "AB1234" (a/make 27 1233))))

(deftest valid
  (are [s ok] (= ok (a/valid? s))
    "A1" true  "AAB1234" true  "A1:A3" false  "A" false  "1" false))

(deftest valid-is-bounded
  (testing "row 0 names no cell (it parses to row index -1)"
    (is (false? (a/valid? "A0")))
    (is (false? (a/valid? "ZZ0"))))
  (testing "a row number too long to convert is refused on the STRING"
    ;; this one used to store, persist, and then throw NumberFormatException in
    ;; the geometry pass on every later render — bricking the sheet for good
    (is (false? (a/valid? "A99999999999999999999"))))
  (testing "indices outside the grid"
    (is (false? (a/valid? "ZZZZZZZZ1")))            ; column index ~2.2e11
    (is (false? (a/valid? "A1048577")))             ; one past MAX-ROWS
    (is (true?  (a/valid? "A1048576")))             ; the last row
    (is (false? (a/valid? "XFE1")))                 ; one past MAX-COLS
    (is (true?  (a/valid? "XFD1"))))                ; the last column
  (testing "non-strings and nil don't blow up"
    (are [x] (false? (a/valid? x))
      nil "" "  " :A1 "A1 " " A1" "-1" "A-1" "A1.5")))

(deftest range-size-counts-without-materializing
  (is (= 1 (a/range-size "A1" "A1")))
  (is (= 4 (a/range-size "A1" "B2")))
  (is (= 4 (a/range-size "B2" "A1")))                        ; order-insensitive
  (is (= (* 16384 1048576) (a/range-size "A1" "XFD1048576")))) ; never expanded

(deftest ranges
  (testing "vertical / horizontal / rectangle row-major"
    (is (= ["A1" "A2" "A3"]            (a/range-cells "A1" "A3")))
    (is (= ["A1" "B1" "C1"]            (a/range-cells "A1" "C1")))
    (is (= ["A1" "B1" "A2" "B2"]       (a/range-cells "A1" "B2")))
    (is (= ["A1" "A2" "A3"]            (a/range-cells "A3" "A1"))))) ; order-insensitive
