(ns uno.michelada.saltrim.stdlib-test
  "The translated standard library — Excel's numerics under Clojure names.
   The implementations arrive cross-validated against Apache POI upstream, so
   what needs testing is the TRANSLATION: the names, the date boundary, and the
   promise that nothing already in a saved formula changed meaning."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.stdlib :as lib]
            [uno.michelada.saltrim.sheet :as sh]))

(defn- v [s a] (sh/settle! s) (sh/value s a))

(defn- sheet-with [cells]
  (let [s (sh/create-sheet)]
    (doseq [[a raw] cells] (sh/set-cell! s a raw))
    s))

(deftest catalog-is-real
  (testing "every borrowed name exists in the Excel registry — no typos, and a"
    (is (empty? (remove (set excel/exposed-names) lib/borrowed-names))
        "an upstream version bump that drops a function fails here"))
  (testing "no name is borrowed twice, under either spelling"
    (is (= (count lib/borrowed-names) (count (set lib/borrowed-names))))
    (is (= (count lib/borrowed-names) (count (set lib/borrowed-syms)))))
  (testing "the size is in the right ballpark — a curated set, not a dump"
    (is (< 150 (count lib/borrowed-names) 300))
    (is (< (count lib/borrowed-names) (count excel/exposed-names))
        "curation means leaving things out")))

(deftest nothing-shadows-clojure
  (testing "no stdlib name quietly replaces a clojure.core var"
    (let [core    (set (map str (keys (ns-publics 'clojure.core))))
          ;; The deliberate overrides, each with a reason in the stdlib ns:
          ;; `abs` predates core's, and the I/O family is replaced by refusals
          ;; because the sandbox has no console.
          allowed #{"abs" "println" "print" "prn" "pr" "printf"
                    "newline" "flush" "read" "read-line"}]
      (is (empty? (remove allowed (filter core (map str (keys formula/stdlib)))))))))

(deftest existing-names-keep-their-meaning
  (testing "a formula saved before the translation computes the same thing"
    (let [s (sheet-with [["A1" "10"] ["A2" "20"] ["A3" ""] ["A4" "30"]
                         ["B1" "=(round 2.5)"]
                         ["B2" "=(round -2.5)"]
                         ["B3" "=(ceil 2.1)"]
                         ["B4" "=(floor 2.9)"]
                         ["B5" "=(sum $A1:A4)"]
                         ["B6" "=(mean $A1:A4)"]
                         ["B7" "=(stdev $A1:A4)"]
                         ["B8" "=(xround -2.5 0)"]])]
      (is (= 3 (v s "B1")) "round is still 1-arg Math/round")
      (is (= -2 (v s "B2")) "…which rounds half UP, not away from zero")
      (is (= 3 (v s "B3")) "ceil is still 1-arg")
      (is (= 2 (v s "B4")) "floor is still 1-arg")
      (is (= 60 (v s "B5")) "aggregates still skip blanks")
      (is (= 20.0 (v s "B6")))
      (is (= 8.16496580927726 (v s "B7")) "stdev is still population")
      (is (= -3 (v s "B8")) "Excel's rounding stays where it was, under xround"))))

(deftest borrowed-functions-compute
  (testing "financial"
    (let [s (sheet-with [["A1" "=(pmt 0.08 10 -1000)"]
                         ["A2" "=(fv 0.06 10 -200 -500)"]
                         ["A3" "=(npv 0.1 [-10000 3000 4200 6800])"]
                         ["A4" "=(sln 30000 7500 10)"]])]
      (is (= 149.02948869707532 (v s "A1")))
      (is (= 3531.5828367476092 (v s "A2")))
      (is (= 1188.4434123352207 (v s "A3")))
      (is (= 2250 (v s "A4")))))
  (testing "statistics"
    (let [s (sheet-with [["A1" "1"] ["A2" "2"] ["A3" "3"] ["A4" "4"] ["A5" "5"]
                         ["B1" "=(norm-dist 42 40 1.5 true)"]
                         ["B2" "=(percentile $A1:A5 0.9)"]
                         ["B3" "=(stdev-s $A1:A5)"]
                         ["B4" "=(stdev-p $A1:A5)"]
                         ["B5" "=(large $A1:A5 2)"]
                         ["B6" "=(countif $A1:A5 \">3\")"]
                         ["B7" "=(correl $A1:A5 [2 4 6 8 10])"]])]
      (is (= 0.9087887181301249 (v s "B1")))
      (is (= 4.6 (v s "B2")))
      (is (= 1.5811388300841898 (v s "B3")))
      (is (= 1.4142135623730951 (v s "B4")) "the .P / .S split survives the rename")
      (is (= 4 (v s "B5")))
      (is (= 2 (v s "B6")))
      (is (= 1 (v s "B7")) "an integral result narrows to Long, as everywhere")))
  (testing "math, text, engineering"
    (let [s (sheet-with [["A1" "=(sin 0)"]
                         ["A2" "=(degrees pi)"]
                         ["A3" "=(mround 17 5)"]
                         ["A4" "=(ceiling-math 2.1)"]
                         ["A5" "=(gcd 24 36)"]
                         ["A6" "=(proper \"hello world\")"]
                         ["A7" "=(str-find \"c\" \"abcd\")"]
                         ["A8" "=(rept \"ab\" 3)"]
                         ["A9" "=(convert 1 \"lbm\" \"kg\")"]
                         ["A10" "=(dec2hex 255)"]
                         ["A11" "=(xor true false)"]])]
      (is (= 0 (v s "A1")))
      (is (= 180 (v s "A2")) "pi is a constant, not a zero-arg call")
      (is (= 15 (v s "A3")))
      (is (= 3 (v s "A4")))
      (is (= 12 (v s "A5")))
      (is (= "Hello World" (v s "A6")))
      (is (= 3 (v s "A7")) "FIND is str-find — clojure.core keeps `find`")
      (is (= "ababab" (v s "A8")))
      (is (= 0.45359237 (v s "A9")))
      (is (= "FF" (v s "A10")))
      (is (true? (v s "A11")))))
  (testing "lookup, over a reshaped flat range"
    (let [s (sheet-with [["A1" "a"] ["B1" "1"] ["A2" "b"] ["B2" "2"] ["A3" "c"] ["B3" "3"]
                         ["D1" "=(vlookup \"b\" (as-rows 2 $A1:B3) 2 false)"]
                         ["D2" "=(match \"c\" $A1:A3 0)"]
                         ["D3" "=(index $A1:A3 2)"]])]
      (is (= 2 (v s "D1")))
      (is (= 3 (v s "D2")))
      (is (= "b" (v s "D3"))))))

(deftest dates-are-iso-not-serials
  (testing "date arguments and date results are ISO strings on both sides"
    (let [s (sheet-with [["A1" "2026-07-29"]
                         ["B1" "=(eomonth $A1 1)"]
                         ["B2" "=(edate \"2026-01-31\" 1)"]
                         ["B3" "=(workday \"2026-07-01\" 10)"]
                         ["B4" "=(date 2026 7 29)"]
                         ["B5" "=(weekday $A1)"]
                         ["B6" "=(isoweeknum $A1)"]
                         ["B7" "=(networkdays \"2026-07-01\" \"2026-07-29\")"]
                         ["B8" "=(yearfrac \"2026-01-01\" \"2026-07-01\")"]
                         ["B9" "=(datedif \"2026-01-01\" \"2026-07-29\" \"m\")"]])]
      (is (= "2026-08-31" (v s "B1")) "end of the NEXT month, as a date")
      (is (= "2026-02-28" (v s "B2")) "month arithmetic clamps to a real day")
      (is (= "2026-07-15" (v s "B3")))
      (is (= "2026-07-29" (v s "B4")))
      (is (= 4 (v s "B5")) "a count stays a count")
      (is (= 31 (v s "B6")))
      (is (= 21 (v s "B7")))
      (is (= 0.5 (v s "B8")))
      (is (= 6 (v s "B9")))))
  (testing "a whole column of dates converts, not just scalars"
    (let [s (sheet-with [["A1" "=(xirr [-10000 2750 4250 3250 2750] [\"2008-01-01\" \"2008-03-01\" \"2008-10-30\" \"2009-02-15\" \"2009-04-01\"])"]
                         ["A2" "=(xnpv 0.09 [-10000 2750 4250] [\"2008-01-01\" \"2008-03-01\" \"2008-10-30\"])"]])]
      (is (< 0.373 (v s "A1") 0.374))
      (is (number? (v s "A2")))))
  (testing "holidays — an optional collection argument — convert too"
    (let [s (sheet-with [["A1" "=(networkdays \"2026-07-01\" \"2026-07-29\" [\"2026-07-03\"])"]
                         ["A2" "=(workday \"2026-07-01\" 10 [\"2026-07-03\"])"]])]
      (is (= 20 (v s "A1")) "one holiday fewer than the 21 without")
      (is (= "2026-07-16" (v s "A2")))))
  (testing "the serial layer is still there for xl/, and the two agree"
    (is (= (excel/date->serial "2026-08-31")
           (excel/call "EOMONTH" [(excel/date->serial "2026-07-29") 1])))))

(deftest errors-stay-loud
  (testing "a domain error is the cell's error, named the spreadsheet way"
    (let [s (sheet-with [["B1" "=(dollarde 1.1 0)"]
                         ["B2" "=(fact -1)"]
                         ["B3" "=(vlookup \"zzz\" (as-rows 2 [\"a\" 1]) 2 false)"]])]
      (is (= {:error "#NUM!"} (v s "B1")))
      (is (= {:error "#NUM!"} (v s "B2")))
      (is (= {:error "#N/A"} (v s "B3")) "a miss is #N/A, not nil")))
  (testing "a bad date string says so"
    (let [s (sheet-with [["B1" "=(eomonth \"not-a-date\" 1)"]])]
      (is (:error (v s "B1"))))))

(deftest blanks-and-reactivity
  (testing "blanks are skipped, as everywhere else in SaltRim"
    (let [s (sheet-with [["A1" "10"] ["A2" ""] ["A3" "20"]
                         ["B1" "=(counta $A1:A3)"]
                         ["B2" "=(countblank $A1:A3)"]
                         ["B3" "=(stdev-p $A1:A3)"]])]
      (is (= 2 (v s "B1")))
      (is (= 1 (v s "B2")))
      (is (= 5 (v s "B3")) "the blank is nothing, not a zero")))
  (testing "borrowed functions are reactive like any other"
    (let [s (sheet-with [["A1" "0.08"] ["B1" "=(pmt $A1 10 -1000)"]])]
      (is (= 149.02948869707532 (v s "B1")))
      (sh/set-cell! s "A1" "0.05")
      (is (= 129.50457496545658 (v s "B1"))))))
