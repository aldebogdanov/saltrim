(ns uno.michelada.saltrim.excel-test
  "The Excel function pack and the adapter that carries plain SaltRim values
   in and out of it. The interesting surface is the SEAM, not the functions —
   they arrive cross-validated against Apache POI upstream. So: value
   translation, error reporting, and which functions we expose at all."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sh]))

(defn- v [s a] (sh/settle! s) (sh/value s a))

(deftest values-round-trip
  (testing "scalars"
    (is (= 30 (excel/call "SUM" [[10 20]])) "integral results narrow to Long")
    (is (= 1.5 (excel/call "AVERAGE" [[1 2]])))
    (is (= "AB" (excel/call "CONCAT" ["A" "B"])))
    (is (true? (excel/call "EXACT" ["a" "a"])))
    (is (false? (excel/call "EXACT" ["a" "A"]))))
  (testing "a blank cell is Excel's blank, not zero"
    (is (= 10 (excel/call "SUM" [[10 nil]])))
    (is (= 10 (excel/call "AVERAGE" [[10 nil]])) "AVERAGE skips it rather than averaging in a 0")
    (is (= 1 (excel/call "COUNT" [[10 nil]]))))
  (testing "non-values are refused, not coerced"
    (is (thrown? clojure.lang.ExceptionInfo (excel/call "SUM" [{:a 1}])))))

(deftest errors-are-named
  (testing "Excel error values surface as their spreadsheet name"
    (is (= "#DIV/0!" (try (excel/call "MOD" [1 0]) (catch Exception e (ex-message e)))))
    (is (= "#NUM!"   (try (excel/call "SQRT" [-1]) (catch Exception e (ex-message e)))))
    (is (= "#VALUE!" (try (excel/call "YEAR" ["2026-07-29"]) (catch Exception e (ex-message e))))
        "an ISO date string is not an Excel serial — fail loudly, don't guess")
    (is (= :div0 (:excel-error (try (excel/call "MOD" [1 0]) (catch Exception e (ex-data e)))))
        "the code is carried too, for a future typed-error layer"))
  (testing "a bad arity says so, instead of upstream's blanket #VALUE!"
    (is (= "PMT takes 3-5 arguments, got 1"
           (try (excel/call "PMT" [1]) (catch Exception e (ex-message e)))))
    (is (= "PI takes 0 arguments, got 2"
           (try (excel/call "PI" [1 2]) (catch Exception e (ex-message e)))))))

(deftest ranges-are-columns
  (testing "a flat range is a column, so list-shaped functions do the right thing"
    (is (= [1 2 3] (excel/call "SORT" [[3 1 2]])))
    (is (= [1 2 3] (excel/call "UNIQUE" [[1 2 2 3]])))
    (is (= [1 3] (excel/call "FILTER" [[1 2 3 4] [true false true false]]))))
  (testing "as-rows reshapes a flat row-major range into a rectangle"
    (is (= [["a" 1] ["b" 2]] (excel/as-rows 2 ["a" 1 "b" 2])))
    (is (= 2 (excel/call "VLOOKUP" ["b" (excel/as-rows 2 ["a" 1 "b" 2 "c" 3]) 2 false])))
    (is (= 4 (excel/call "INDEX" [(excel/as-rows 2 [1 2 3 4]) 2 2])))))

(deftest date-bridges
  (testing "ISO string <-> Excel serial round-trips"
    (is (= 46232 (excel/date->serial "2026-07-29")))
    (is (= "2026-07-29" (excel/serial->date 46232)))
    (is (= 2026 (excel/call "YEAR" [(excel/date->serial "2026-07-29")]))))
  (testing "the 1900 leap-year bug Excel preserves"
    (is (= 1 (excel/date->serial "1900-01-01")) "before the phantom 29th: pre-leap epoch")
    (is (= 61 (excel/date->serial "1900-03-01")) "after it: serial 60 is the date that never was")
    (is (= "1900-03-01" (excel/serial->date 61)))
    (is (= "1900-01-01" (excel/serial->date 1))))
  (testing "agreement with the engine's own DATE"
    (is (= (excel/date->serial "2026-07-29") (excel/call "DATE" [2026 7 29])))
    (is (= (excel/date->serial "1900-01-01") (excel/call "DATE" [1900 1 1])))))

(deftest exposure-rules
  (testing "the catalog and the exposed set are the same set"
    (let [listed (set (mapcat second excel/catalog))]
      (is (= listed (set excel/exposed-names))
          (str "catalog drift — only in catalog: "
               (sort (set/difference listed (set excel/exposed-names)))
               ", only exposed: "
               (sort (set/difference (set excel/exposed-names) listed))))))
  (testing "real functions are exposed"
    (is (every? (set excel/exposed-names)
                ["SUM" "PMT" "XIRR" "VLOOKUP" "NORM.DIST" "CONVERT" "TEXTJOIN"])))
  (testing "upstream's #N/A stubs are not — they would look like real functions"
    (is (every? (:stub excel/excluded) ["PRICE" "YIELD" "DURATION" "CALL" "EXEC" "CUBEVALUE"]))
    (is (not-any? (set excel/exposed-names) ["PRICE" "YIELD" "CALL" "CUBEVALUE"]))
    (is (not (contains? (:stub excel/excluded) "PMT")) "detection must not swallow real fns")
    (is (not (contains? (:stub excel/excluded) "VLOOKUP"))))
  (testing "volatile functions are not — SaltRim has no recalc sweep to refresh them"
    (is (= #{"CELL" "INDIRECT" "INFO" "NOW" "OFFSET" "RAND" "RANDARRAY" "RANDBETWEEN" "TODAY"}
           (:volatile excel/excluded)))
    (is (not-any? (set excel/exposed-names) ["NOW" "TODAY" "RAND"])))
  (testing "evaluator-bound functions are not — SaltRim has them natively"
    (is (every? (:lazy excel/excluded) ["IF" "IFERROR" "IFNA" "SWITCH" "MAP" "REDUCE"]))))

(deftest reachable-only-behind-xl
  (testing "the bare namespace gains nothing — Clojure stays the formula language"
    (let [s (sh/create-sheet)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unable to resolve symbol: PMT"
                            (sh/set-cell! s "A1" "=(PMT 0.08 10 -1000)"))
          "an unqualified Excel name is simply not a symbol here — rejected at install")))
  (testing "Excel's library does not leak into the stdlib"
    (is (not-any? (set (map str (keys formula/stdlib)))
                  ["SUM" "PMT" "NORM.DIST" "VLOOKUP"]))
    (is (contains? formula/stdlib 'sum) "the native stdlib is untouched")))

(deftest interop-through-the-engine
  (testing "qualified calls work, and mean Excel's thing"
    (let [s (sh/create-sheet)]
      (doseq [[a raw] [["A1" "10"] ["A2" "20"] ["A3" ""] ["A4" "30"]]]
        (sh/set-cell! s a raw))
      (sh/set-cell! s "B1" "=(xl/SUM $A1:A4)")
      (sh/set-cell! s "B2" "=(sum $A1:A4)")
      (sh/set-cell! s "B3" "=(xl/AVERAGE $A1:A4)")
      (sh/set-cell! s "B4" "=(xl/COUNTIF $A1:A4 \">15\")")
      (sh/set-cell! s "B5" "=(xl/PMT 0.08 10 -1000)")
      (sh/set-cell! s "B6" "=(+ (xl/SUM $A1:A4) (sum $A1:A4))")
      (is (= 60 (v s "B1")))
      (is (= 60 (v s "B2")) "the native stdlib is unaffected")
      (is (= 20 (v s "B3")) "blank skipped: 60/3")
      (is (= 2 (v s "B4")))
      (is (= 149.02948869707532 (v s "B5")))
      (is (= 120 (v s "B6")) "both layers in one formula")))
  (testing "an Excel error becomes the cell's error, reactively"
    (let [s (sh/create-sheet)]
      (sh/set-cell! s "A1" "0")
      (sh/set-cell! s "B1" "=(xl/MOD 1 $A1)")
      (is (= {:error "#DIV/0!" :code :div0} (v s "B1")))
      (sh/set-cell! s "A1" "3")
      (is (= 1 (v s "B1")) "recovers when the dependency changes"))))
