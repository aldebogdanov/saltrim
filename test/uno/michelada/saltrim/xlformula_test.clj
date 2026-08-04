(ns uno.michelada.saltrim.xlformula-test
  "SaltRim source -> Excel formula. The interesting test is the ROUND TRIP: run
   the result back through the importer and see whether the formula we started
   with comes out. That pins the two directions against each other, so a change
   to either vocabulary that forgets the other one fails here."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.xlformula :as xlf]
            [uno.michelada.saltrim.xlsx :as xlsx]))

(defn- ->xl [src] (xlf/source->excel src))
(defn- round [src] (xlsx/translate-formula (xlf/source->excel src)))
(defn- refused [src] (nil? (xlf/try-excel src)))

(deftest translates-to-excel
  (testing "operators, refs and ranges"
    (is (= "SUM(A1:A3)"    (->xl "=(sum $A1:A3)")))
    (is (= "A1+B1"         (->xl "=(+ $A1 $B1)")))
    (is (= "2*(A1+1)"      (->xl "=(* 2 (+ $A1 1))")) "unparse keeps the precedence")
    (is (= "-A1"           (->xl "=(- $A1)")))
    (is (= "A1^2"          (->xl "=(pow $A1 2)")))
    (is (= "A1<>B1"        (->xl "=(not= $A1 $B1)"))))
  (testing "the hand-mapped names undo the importer's choices"
    (is (= "MIN(A1:A9)"    (->xl "=(xmin $A1:A9)")) "xmin skips blanks, MIN is its source")
    (is (= "ROUND(A1,2)"   (->xl "=(xround $A1 2)")))
    (is (= "DATE(2024,3,15)" (->xl "=(xdate 2024 3 15)"))))
  (testing "the borrowed and xl/ tiers need no hand mapping at all"
    (is (= "PMT(A1,10,-1000)"    (->xl "=(pmt $A1 10 -1000)")))
    (is (= "NORM.DIST(1,0,1,TRUE)" (->xl "=(norm-dist 1 0 1 true)")))
    (is (= "FIND(\"a\",A1)"      (->xl "=(str-find \"a\" $A1)")) "renamed on the way in")
    (is (= "TRANSPOSE(A1:B2)"    (->xl "=(xl/TRANSPOSE $A1:B2)"))))
  (testing "the idioms the importer expanded, folded back up"
    (is (= "LEN(TRIM(A1))"       (->xl "=(count (str (trim (str $A1))))")))
    (is (= "COUNT(A1:A9)"        (->xl "=(count (filter number? $A1:A9))")))
    (is (= "COUNTA(A1:A9)"       (->xl "=(count (remove nil? $A1:A9))")))
    (is (= "IF(A1,1,2)"          (->xl "=(if (excel-truthy $A1) 1 2)")))
    (is (= "IFERROR(A1/B1,0)"    (->xl "=(if-error (fn [] (/ $A1 $B1)) 0)"))))
  (testing "let becomes LET"
    (is (= "LET(x,1,y,2,x+y+A1)" (->xl "=(let [x 1 y 2] (+ (+ x y) $A1))")))))

(deftest ranges-are-folded-back-up
  ;; `formula/parse` expands $A1:A3 into a (vector …) of per-cell refs and never
  ;; puts it back, so export has to recognise the rectangle itself. It matters
  ;; for more than looks: SUM(A1,A2,…,A500) would blow Excel's 8192-character
  ;; formula limit that SUM(A1:A500) sits nine characters inside.
  (is (= "SUM(A1:A3)"  (->xl "=(sum $A1:A3)")))
  (is (= "SUM(A1:B2)"  (->xl "=(sum $A1:B2)")) "a rectangle, not just a column")
  (is (= "SUM(A1,A3)"  (->xl "=(sum (vector $A1 $A3))"))
      "a gappy list is NOT a range — the fold is exact, never a guess")
  (is (= "SUM(A1:B2,C3,5)" (->xl "=(sum (flatten (vector $A1:B2 $C3 5)))"))
      "and a mixed argument list keeps the range that is in it")
  (is (> 8192 (count (->xl "=(sum $A1:A500)"))) "500 cells still fit in a formula"))

(deftest round-trips-through-the-importer
  (doseq [src ["=(sum $A1:A3)" "=(mean $B1:B10)" "=(xmin $A1:A9)" "=(+ $A1 $B1)"
               "=(* 2 (+ $A1 1))" "=(if (> $A1 2) (sum $B1:B3) 0)"
               "=(and (> $A1 1) (< $B1 5))" "=(not (= $A1 3))" "=(pow $A1 2)"
               "=(- $A1)" "=(str $A1 \" x\")" "=(xround $A1 2)" "=(xdate 2024 3 15)"
               "=(abs (- $A1 $B1))" "=(if-error (fn [] (/ $A1 $B1)) 0)"
               "=(pmt $A1 10 -1000)" "=(irr $A1:A5)" "=(norm-dist 1 0 1 true)"
               "=(stdev-p $A1:A9)" "=(sumif $A1:A9 \">5\")" "=(today)"
               "=(str-find \"a\" $A1)" "=(percentile $A1:A9 0.5)"
               "=(count (filter number? $A1:A9))" "=(count (str (trim (str $A1))))"
               "=(if (excel-truthy $A1) 1 2)" "=(let [x 1 y 2] (+ (+ x y) $A1))"
               "=(sum (flatten (vector $A1:B2 $C3 5)))" "=(xl/TRANSPOSE #area A1:B2)"
               "=(index #area A1:B2 2 1)"]]
    (is (= src (round src)) (str "round trip: " src)))
  (testing "deliberate non-identities — both come back BETTER than they went out"
    ;; xl/PMT and pmt ARE the same function; the importer prefers the Clojure
    ;; name now
    (is (= "=(pmt $A1 10 -1000)" (round "=(xl/PMT $A1 10 -1000)")))
    ;; a flat range handed to a shape-sensitive function is the bug #area fixes,
    ;; so a round trip UPGRADES it: the export loses nothing (Excel ranges carry
    ;; their own shape) and the import puts the shape back
    (is (= "=(xl/TRANSPOSE #area A1:B2)" (round "=(xl/TRANSPOSE $A1:B2)")))))

(deftest areas-export-as-one-range
  ;; without area folding an #area argument would splice into one range PER ROW,
  ;; so a one-argument function would be called with two
  (is (= "TRANSPOSE(A1:B2)" (->xl "=(xl/TRANSPOSE #area A1:B2)")))
  (is (= "MDETERM(A1:C3)"   (->xl "=(xl/MDETERM #area A1:C3)")))
  (is (= "INDEX(A1:B2,2,1)" (->xl "=(index #area A1:B2 2 1)")))
  (is (= "SUM(A1:B2)"       (->xl "=(sum $A1:B2)")) "a flat range still folds too"))

(deftest count-idiom-checks-its-shape
  ;; the matcher recognises three specific expansions, and anything else that
  ;; happens to start with `count` must be refused rather than bent into a
  ;; wrong-arity Excel call — `COUNT()` is not a formula Excel accepts, and a
  ;; formula Excel rejects costs the whole file, not the cell
  (is (refused "=(count (filter number?))") "no collection argument")
  (is (refused "=(count (filter odd? $A1:A9))") "a predicate that isn't number?")
  (is (refused "=(count (remove zero? $A1:A9))") "a predicate that isn't nil?")
  (is (refused "=(count (str $A1 $B1))") "2-arg str is a concatenation, not LEN")
  (is (refused "=(count $A1:A9)") "a bare count has no Excel spelling at all"))

(deftest what-cannot-cross
  (testing "Clojure with no Excel spelling"
    (is (refused "=(reduce + (map (fn [x] (* x x)) $A1:A5))"))
    (is (refused "=(my-own-fn $A1)") "a call into the sheet's ƒ library")
    (is (refused "=(sort $A1:A9)")))
  (testing "a dynamic ref names a cell only at run time — Excel has no such thing"
    (is (refused "=$(str \"A\" $C1)")))
  (testing "try-excel returns nil rather than throwing, because export wants the fallback"
    (is (nil? (xlf/try-excel "=(nonsense)")))
    (is (thrown? clojure.lang.ExceptionInfo (xlf/source->excel "=(nonsense)")))))
