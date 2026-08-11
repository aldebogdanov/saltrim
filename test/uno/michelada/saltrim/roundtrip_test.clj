(ns uno.michelada.saltrim.roundtrip-test
  "The whole loop: .xlsx -> SaltRim -> edits -> .xlsx -> SaltRim.

   The unit tests check each leg against what it is supposed to produce. This
   one checks the two legs against EACH OTHER, over one workbook that exercises
   the vocabulary from a bare number up to a structured table reference — which
   is a different question, and the one a user actually asks: if I open my
   spreadsheet here, change it, and download it again, is it still my
   spreadsheet?

   The strongest assertion in here is the FIXED POINT: a second lap through the
   loop changes nothing at all — not a value, not a formula's source, not a
   label. A loop that merely 'mostly works' drifts, and drift shows up on the
   second lap even when the first looks clean.

   Three limits of the fixture, all POI's rather than ours, all deliberate:
     - POI cannot PARSE `LET` or a structured reference, so those formulas are
       written through the XML (`formula!`).
     - POI cannot EVALUATE them either, so their cached values are supplied by
       hand — which is what Excel would have written, and what lets
       `demote-verify!` do its job instead of demoting them for want of an
       answer to compare against.
     - `evaluateAllFormulaCells` aborts the whole sweep on the first formula it
       cannot read, so the fixture evaluates cell by cell."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mount.core :as mount]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.export :as export]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.xlsx :as xlsx])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.apache.poi.ss SpreadsheetVersion]
           [org.apache.poi.ss.usermodel CellType FillPatternType HorizontalAlignment IndexedColors]
           [org.apache.poi.ss.util AreaReference CellReference]
           [org.apache.poi.xssf.usermodel XSSFCell XSSFWorkbook]))

(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

;; --- building the workbook ---------------------------------------------------

(defn- cel [s r c] (.createCell (or (.getRow s r) (.createRow s r)) c))

(defn- put! [s r c v]
  (let [x (cel s r c)]
    (cond (string? v)  (.setCellValue x ^String v)
          (boolean? v) (.setCellValue x ^Boolean (boolean v))
          :else        (.setCellValue x (double v)))
    x))

(defn- f! [s r c ^String formula] (doto (cel s r c) (.setCellFormula formula)))

(defn- formula!
  "A formula POI's own parser refuses (`LET`, structured refs), plus the cached
   value Excel would have written."
  [s r c ^String formula cached]
  (let [x (cel s r c)]
    (.setStringValue (.addNewF (.getCTCell x)) formula)
    (.setCellValue x (double cached))
    x))

(defn- workbook ^bytes []
  (let [wb   (XSSFWorkbook.)
        data (.createSheet wb "Data")
        ref  (.createSheet wb "Ref")
        df   (.createDataFormat wb)
        money (doto (.createCellStyle wb) (.setDataFormat (.getFormat df "#,##0.00")))
        datef (doto (.createCellStyle wb) (.setDataFormat (.getFormat df "yyyy-mm-dd")))
        fancy (let [ft (doto (.createFont wb) (.setBold true))]
                (doto (.createCellStyle wb)
                  (.setFont ft)
                  (.setFillForegroundColor (.getIndex IndexedColors/YELLOW))
                  (.setFillPattern FillPatternType/SOLID_FOREGROUND)
                  (.setAlignment HorizontalAlignment/CENTER)))]
    ;; literals, and the three presentational things that must survive
    (put! data 0 0 10) (put! data 1 0 3.5) (put! data 2 0 "hello") (put! data 3 0 true)
    (.setCellStyle (put! data 4 0 45000) datef)
    (.setCellStyle (put! data 5 0 1234.5) money)
    (.setCellStyle (put! data 6 0 "head") fancy)

    ;; operators
    (f! data 0 1 "A1+A2") (f! data 1 1 "A1*2-1") (f! data 2 1 "A1/4")
    (f! data 3 1 "A1^2")  (f! data 4 1 "50%")    (f! data 5 1 "A3&\" world\"")
    (f! data 6 1 "A1>5")  (f! data 7 1 "-A1")

    ;; aggregates over a range
    (put! data 0 2 1) (put! data 1 2 2) (put! data 2 2 3) (put! data 3 2 4)
    (f! data 5 2 "SUM(C1:C4)")   (f! data 6 2 "AVERAGE(C1:C4)")
    (f! data 7 2 "MIN(C1:C4)")   (f! data 8 2 "MAX(C1:C4)")
    (f! data 9 2 "COUNT(C1:C4)") (f! data 10 2 "MEDIAN(C1:C4)")
    (f! data 11 2 "SUM(C1:C4,A1,5)")

    ;; logic, text, dates, rounding
    (f! data 0 3 "IF(A1>5,\"big\",\"small\")") (f! data 1 3 "IF(A1>5,1)")
    (f! data 2 3 "AND(A1>5,A2>1)")             (f! data 3 3 "OR(A1<5,A2>1)")
    (f! data 4 3 "NOT(A1>5)")                  (f! data 5 3 "IFERROR(A1/0,\"oops\")")
    (f! data 6 3 "LEN(TRIM(A3))")              (f! data 7 3 "UPPER(A3)")
    (f! data 8 3 "LEFT(A3,2)")                 (f! data 9 3 "SUBSTITUTE(A3,\"l\",\"L\")")
    (f! data 10 3 "YEAR(A5)")                  (f! data 11 3 "ROUND(A2,0)")
    (f! data 12 3 "ABS(-A1)")

    ;; all three function tiers: hand-mapped, borrowed, xl/-only
    (f! data 0 4 "PMT(0.08,10,-1000)")   (f! data 1 4 "STDEV.P(C1:C4)")
    (f! data 2 4 "GEOMEAN(C1:C4)")       (f! data 3 4 "SUMIF(C1:C4,\">2\")")
    (f! data 4 4 "AVERAGEA(C1:C4)")      (f! data 5 4 "DOLLARDE(1.02,16)")
    (f! data 6 4 "SUMPRODUCT(C1:C4,C1:C4)")

    ;; a rectangle, where shape decides the answer
    (put! data 0 5 1) (put! data 0 6 2) (put! data 1 5 3) (put! data 1 6 4)
    (f! data 3 5 "MDETERM(F1:G2)") (f! data 4 5 "INDEX(F1:G2,2,1)") (f! data 5 5 "SUM(F1:G2)")

    ;; LET and an array constant
    (formula! data 0 7 "LET(x,A1,y,A2,x*y)" 35)
    (f! data 1 7 "SUM({1,2,3})")

    ;; defined names: a cell, a range, and an expression with no cell to sit on
    (put! data 0 8 0.2) (put! data 1 8 100) (put! data 2 8 200)
    (let [nm (fn [n refers] (doto (.createName wb)
                              (.setNameName n) (.setRefersToFormula refers)))]
      (nm "Rate" "Data!$I$1") (nm "Amounts" "Data!$I$2:$I$3") (nm "Doubled" "Data!$I$1*2"))
    (f! data 4 8 "A1*Rate") (f! data 5 8 "SUM(Amounts)") (f! data 6 8 "Doubled")

    ;; a real table, and every structured shape
    (doseq [[r vs] (map-indexed vector [["Item" "Qty" "Price"]
                                        ["a" 1 10] ["b" 2 20] ["c" 3 30]])]
      (doseq [[c v] (map-indexed vector vs)] (put! data (+ 20 r) (+ 10 c) v)))
    (doto (.createTable data (AreaReference. (CellReference. "K21") (CellReference. "M24")
                                             SpreadsheetVersion/EXCEL2007))
      (.setName "Sales") (.setDisplayName "Sales"))
    (formula! data 21 13 "Sales[@Qty]*Sales[@Price]" 10)
    (formula! data 25 10 "SUM(Sales[Qty])" 6)
    (formula! data 26 10 "SUM(Sales[[Qty]:[Price]])" 66)
    (formula! data 27 10 "COUNTA(Sales[#Headers])" 3)

    ;; and the two that must REFUSE
    (put! ref 0 0 7)
    (f! data 0 9 "Ref!A1*2") (f! data 1 9 "SUM(A:A)")

    ;; POI aborts the whole sweep on the first formula it cannot read
    (let [ev (.createFormulaEvaluator (.getCreationHelper wb))]
      (doseq [i (range (.getNumberOfSheets wb))
              row (seq (.getSheetAt wb i))
              ^XSSFCell c (seq row)
              :when (= CellType/FORMULA (.getCellType c))]
        (try (.evaluateFormulaCell ev c) (catch Exception _ nil))))
    (let [bos (ByteArrayOutputStream.)]
      (.write wb bos) (.close wb)
      (.toByteArray bos))))

;; --- helpers -----------------------------------------------------------------

(defn- values [sh]
  (into {} (for [a (keys (sheet/document sh)) :let [v (sheet/value sh a)] :when (some? v)] [a v])))

(defn- sources [sh]
  (into {} (for [a (keys (sheet/document sh)) :let [r (sheet/raw sh a)] :when (some? r)] [a r])))

(defn- same? [a b]
  (if (and (number? a) (number? b))
    (< (Math/abs (- (double a) (double b))) 1e-9)
    (= a b)))

(defn- import! [bytes' name']
  (let [rep (xlsx/import! (ByteArrayInputStream. bytes') "u" name')
        sh  (:sh (store/load-record (str "u__" (:sname (first (:sheets rep))))))]
    (sheet/settle! sh)
    [rep sh]))

(defn- excel-formulas
  "{addr formula} for every cell the exported workbook holds as a live formula."
  [bytes']
  (let [s (.getSheetAt (XSSFWorkbook. (ByteArrayInputStream. bytes')) 0)]
    (into {} (for [row (seq s) ^XSSFCell c (seq row)
                   :when (= CellType/FORMULA (.getCellType c))]
               [(.formatAsString (CellReference. (.getRowIndex c) (.getColumnIndex c)))
                (.getCellFormula c)]))))

;; --- the loop ----------------------------------------------------------------

(deftest xlsx-saltrim-xlsx-loop
  (db/upsert-user! {:uid "u" :name "U"})
  (let [[rep sh] (import! (workbook) "wb")
        r1       (first (:sheets rep))]

    (testing "IMPORT: everything but the two deliberate refusals is live"
      (is (= 47 (:formulas r1)))
      (is (empty? (:demoted r1))
          "nothing disagreed with Excel's own cached value")
      (is (= [["J1" "cross-sheet reference to Ref"]
              ["J2" "whole-col reference"]]
             (mapv (juxt :addr :reason) (:fallbacks r1)))))

    (testing "and the vocabulary translated the way each tier promises"
      (doseq [[a src]
              {"B5"  "=(/ 50 100.0)"                          ; percent
               "C8"  "=(xmin $C1:C4)"                         ; hand-mapped: skips blanks
               "C12" "=(sum (flatten (vector $C1:C4 $A1 5)))"
               "D6"  "=(if-error (fn [] (/ $A1 0)) \"oops\")"
               "E1"  "=(pmt 0.08 10 -1000)"                   ; borrowed
               "E5"  "=(xl/AVERAGEA $C1:C4)"                  ; xl/-only
               "F4"  "=(det #area F1:G2)"                     ; a rectangle keeps its shape
               "H1"  "=(let [x $A1 y $A2] (* x y))"
               "I5"  "=(* $A1 $Rate)"                         ; a defined name became a label
               "K26" "=(sum $L22:L24)"}]                      ; a structured reference
        (is (= src (sheet/raw sh a)) a)))

    (testing "a defined name arrived as a LABEL on the cell it named"
      (is (= ["I1"] (sheet/labelled sh "Rate")))
      (is (= ["I2" "I3"] (sheet/labelled sh "Amounts")) "a named RANGE, on every cell"))

    ;; ---- CHANGES, the middle leg of the loop --------------------------------
    (sheet/set-cell! sh "A1" "20")
    (sheet/set-cell! sh "C1" "9")
    (sheet/set-cell! sh "P1" "=(* $A1 $Rate)")     ; a NEW formula, written by name
    (sheet/settle! sh)

    (testing "edits propagate before anything is written back"
      (is (= 23.5 (sheet/value sh "B1")))
      (is (= 18   (sheet/value sh "C6")))
      (is (= 4.0  (sheet/value sh "P1"))))

    (let [out       (export/workbook-bytes sh "wb-Data")
          xl        (excel-formulas out)
          [rep2 sh2] (import! out "again")
          r2        (first (:sheets rep2))]

      (testing "EXPORT: live formulas, and the names still spelled as names"
        (is (= 47 (count xl)) "every formula that has an Excel spelling")
        (is (= "A1*Rate"     (xl "I5")))
        (is (= "SUM(Amounts)" (xl "I6")))
        (is (= "A1*Rate"     (xl "P1")) "including the one added in SaltRim")
        (is (nil? (xl "H1"))
            "LET translates but POI cannot WRITE it — value, not a failed export")
        (is (nil? (xl "D11"))
            "`year` takes an ISO string; only xl/ speaks Excel serials"))

      (testing "RE-IMPORT is clean — nothing we wrote confuses the reader"
        (is (empty? (:fallbacks r2)))
        (is (empty? (:demoted r2))))

      (testing "every VALUE survived the loop"
        (let [before (values sh) after (values sh2)]
          (is (= (count before) (count after)))
          (is (empty? (for [[a v] before :when (not (same? v (get after a)))]
                        [a v (get after a)])))))

      (testing "and so did the labels, because export writes them back as names"
        (is (= (sheet/all-labels sh) (sheet/all-labels sh2))))

      (testing "presentation came through"
        (is (= "#,##0.00" (sheet/style-value sh2 "A6" :format)))
        (is (= "2023-03-15" (sheet/value sh2 "A5")) "a date is still a date")
        (is (= "#ffff00" (sheet/style-value sh2 "A7" :bg))))

      ;; ---- the fixed point ---------------------------------------------------
      (testing "a SECOND lap changes nothing — the loop does not drift"
        (let [[_ sh3] (import! (export/workbook-bytes sh2 "wb-Data") "third")]
          (is (empty? (for [[a v] (values sh2) :when (not (same? v (get (values sh3) a)))]
                        [a v (get (values sh3) a)]))
              "no value moved")
          (is (empty? (for [[a r] (sources sh2) :when (not= r (get (sources sh3) a))]
                        [a r (get (sources sh3) a)]))
              "and no formula was rewritten")
          (is (= (sheet/all-labels sh2) (sheet/all-labels sh3))))))))
