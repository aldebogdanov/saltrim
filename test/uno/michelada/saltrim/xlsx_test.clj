(ns uno.michelada.saltrim.xlsx-test
  "Excel import: the Ptg->form translator, workbook reading (values / styles /
   masks / sizes / fallbacks), the demote-and-verify pass, naming, caps, and
   the apostrophe literal escape the importer relies on."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mount.core :as mount]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.xlsx :as xlsx])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.apache.poi.ss.usermodel FillPatternType HorizontalAlignment IndexedColors]
           [org.apache.poi.xssf.usermodel XSSFEvaluationWorkbook XSSFFormulaEvaluator XSSFWorkbook]))

(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

;; --- fixture helpers --------------------------------------------------------

(defn- cell [sheet r c]
  (.createCell (or (.getRow sheet r) (.createRow sheet r)) c))

(defn- wb-bytes ^bytes [^XSSFWorkbook wb]
  (XSSFFormulaEvaluator/evaluateAllFormulaCells wb)
  (let [bos (ByteArrayOutputStream.)]
    (.write wb bos) (.close wb)
    (.toByteArray bos)))

(defn- in-stream [^bytes b] (ByteArrayInputStream. b))

;; --- translator --------------------------------------------------------------

(deftest translator
  (let [wb  (XSSFWorkbook.)
        _   (.createSheet wb "S1")
        _   (.createSheet wb "Other")
        pwb (XSSFEvaluationWorkbook/create wb)
        t   (fn [f] (xlsx/translate-formula f pwb 0))
        bad (fn [f] (try (t f) nil
                         (catch Exception e
                           (or (:uno.michelada.saltrim.xlsx/unsupported (ex-data e))
                               (.getMessage e)))))]
    (testing "aggregates and ranges"
      (is (= "=(sum $A1:A3)" (t "SUM(A1:A3)")) "single range rides the AttrPtg-sum path")
      (is (= "=(sum (flatten (vector $A1:B2 $C3 5)))" (t "SUM(A1:B2,C3,5)")))
      (is (= "=(mean $B1:B10)" (t "AVERAGE(B1:B10)")))
      (is (= "=(xmin $A1:A9)" (t "MIN(A1:A9)")))
      (is (= "=(count (filter number? $A1:A9))" (t "COUNT(A1:A9)"))))
    (testing "IF family: lazy branches, Excel number-truthiness"
      (is (= "=(if (> $A1 2) (sum $B1:B3) 0)" (t "IF(A1>2,SUM(B1:B3),0)")))
      (is (= "=(if (excel-truthy $A1) 1 2)" (t "IF(A1,1,2)")))
      (is (= "=(if (> $A1 1) \"yes\" false)" (t "IF(A1>1,\"yes\")")) "2-arg IF: Excel yields FALSE")
      (is (= "=(and (> $A1 1) (excel-truthy $B1))" (t "AND(A1>1,B1)")))
      (is (= "=(if-error (fn [] (/ $A1 $B1)) 0)" (t "IFERROR(A1/B1,0)")) "future-function NameXPxg path"))
    (testing "operators"
      (is (= "=(/ 50 100.0)" (t "50%")))
      (is (= "=(str $A1 \" x\")" (t "A1&\" x\"")))
      (is (= "=(- $A1)" (t "-A1")))
      (is (= "=(pow $A1 2)" (t "A1^2")))
      (is (= "=(not= $A1 $B1)" (t "A1<>B1"))))
    (testing "text / date / rounding"
      (is (= "=(count (str (trim (str $A1))))" (t "LEN(TRIM(A1))")))
      (is (= "=(year (today))" (t "YEAR(TODAY())")))
      (is (= "=(xdate 2024 3 15)" (t "DATE(2024,3,15)")))
      (is (= "=(xround $A1 2)" (t "ROUND(A1,2)"))))
    (testing "VLOOKUP: exact match only, width from the range"
      (is (= "=(xvlookup \"k\" $A1:C10 3 2)" (t "VLOOKUP(\"k\",A1:C10,2,FALSE)")))
      (is (bad "VLOOKUP(\"k\",A1:C10,2)") "approximate (default) match unsupported"))
    (testing "unsupported -> ex-info for the fallback path"
      (is (= "Ref3DPxg" (bad "Other!A1")) "cross-sheet")
      (is (bad "SUM(A:A)") "whole column")
      (is (bad "TRANSPOSE(A1:B2)") "unknown function"))))

;; --- workbook read -----------------------------------------------------------

(defn- fixture-wb ^bytes []
  (let [wb (XSSFWorkbook.)
        s1 (.createSheet wb "Data")
        s2 (.createSheet wb "Other")
        df (.createDataFormat wb)
        date-style (doto (.createCellStyle wb) (.setDataFormat (.getFormat df "yyyy-mm-dd")))
        fancy (let [f (doto (.createFont wb) (.setBold true))]
                (doto (.createCellStyle wb)
                  (.setFont f)
                  (.setFillForegroundColor (.getIndex IndexedColors/YELLOW))
                  (.setFillPattern FillPatternType/SOLID_FOREGROUND)
                  (.setAlignment HorizontalAlignment/CENTER)
                  (.setDataFormat (.getFormat df "#,##0.00"))))
        weird (doto (.createCellStyle wb) (.setDataFormat (.getFormat df "0.0E+00")))]
    (.setCellValue (cell s2 0 0) 5.0)
    (.setCellValue (cell s1 0 0) 10.0)                             ; A1
    (.setCellValue (cell s1 1 0) 3.5)                              ; A2
    (.setCellValue (cell s1 2 0) "hello")                          ; A3
    (.setCellValue (cell s1 3 0) "123")                            ; A4: text-number
    (.setCellValue (cell s1 4 0) true)                             ; A5
    (.setCellFormula (cell s1 0 1) "SUM(A1:A2)")                   ; B1
    (.setCellFormula (cell s1 1 1) "IF(A1>5,\"big\",\"small\")")   ; B2
    (.setCellFormula (cell s1 2 1) "Other!A1*2")                   ; B3: fallback
    (.setCellFormula (cell s1 3 1) "A1+C9")                        ; B4: Excel blank=0, ours nil
    (let [c (cell s1 0 2)]
      (.setCellValue c (java.time.LocalDate/of 2024 3 15)) (.setCellStyle c date-style))
    (let [c (cell s1 0 3)] (.setCellValue c 1234.5) (.setCellStyle c fancy))
    (let [c (cell s1 1 3)] (.setCellValue c 7.0) (.setCellStyle c weird))
    (.setColumnWidth s1 0 (* 20 256))
    (.setHeightInPoints (.getRow s1 0) 30.0)
    (wb-bytes wb)))

(deftest read-workbook-values-styles-sizes
  (let [{:keys [tabs]} (xlsx/read-workbook (in-stream (fixture-wb)))
        {:keys [doc report cols rows] :as t1} (first tabs)]
    (testing "values"
      (is (= "10" (get-in doc ["A1" :value])) "integral double narrows")
      (is (= "3.5" (get-in doc ["A2" :value])))
      (is (= "hello" (get-in doc ["A3" :value])))
      (is (= "'123" (get-in doc ["A4" :value])) "text-number apostrophe-escaped")
      (is (= "=true" (get-in doc ["A5" :value])))
      (is (= "2024-03-15" (get-in doc ["C1" :value])) "date -> ISO string"))
    (testing "formulas translate; untranslatable falls back to cached value + comment"
      (is (= "=(sum $A1:A2)" (get-in doc ["B1" :value])))
      (is (= "10" (get-in doc ["B3" :value])) "Other!A1*2 cached 10")
      (is (= "XLSX: =Other!A1*2" (get-in doc ["B3" :style :comment])))
      (is (= ["B3"] (mapv :addr (:fallbacks report)))))
    (testing "styles + masks"
      (is (= {:weight "bold" :bg "#ffff00" :align "center" :format "#,##0.00"}
             (get-in doc ["D1" :style])))
      (is (= ["0.0E+00"] (:masks-dropped report)) "unsupported mask dropped + reported")
      (is (nil? (get-in doc ["D2" :style :format]))))
    (testing "sizes"
      (is (contains? cols 0))
      (is (contains? rows 0))
      (is (pos? (:dcw t1)))
      (is (pos? (:drh t1))))
    (testing "second tab read independently"
      (is (= 1 (get-in (second tabs) [:report :cells]))))))

;; --- import! (db-backed): naming, persistence, demote+verify ----------------

(deftest import-end-to-end
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (let [report (xlsx/import! (in-stream (fixture-wb)) "dev-ann" "budget")
        names  (mapv :sname (:sheets report))]
    (testing "multi-tab -> one sheet per tab, base-tab names"
      (is (= ["budget-Data" "budget-Other"] names)))
    (testing "demote+verify caught the blank-ref mismatch"
      (is (= ["B4"] (mapv :addr (:demoted (first (:sheets report)))))))
    (testing "persisted sheets load with Excel's values"
      (let [{:keys [sh]} (store/load-record "dev-ann__budget-Data")]
        (try
          (is (= 10 (sheet/value sh "A1")))
          (is (= 13.5 (sheet/value sh "B1")) "translated formula computes")
          (is (= "big" (sheet/value sh "B2")))
          (is (= 10 (sheet/value sh "B3")) "fallback kept Excel's value")
          (is (= 10 (sheet/value sh "B4")) "demoted kept Excel's value")
          (is (= "XLSX: =A1+C9" (sheet/style-value sh "B4" :comment)))
          (is (= "123" (sheet/value sh "A4")) "escaped text stays text")
          (is (= "=(sum $A1:A2)" (sheet/raw sh "B1")) "live formula persisted as source")
          (finally (sheet/close! sh)))))
    (testing "name collisions get suffixed"
      (let [again (xlsx/import! (in-stream (fixture-wb)) "dev-ann" "budget")]
        (is (= ["budget-Data-2" "budget-Other-2"] (mapv :sname (:sheets again))))))))

(deftest import-caps
  (with-redefs [xlsx/max-cells 3]
    (is (thrown-with-msg? Exception #"too large"
                          (xlsx/import! (in-stream (fixture-wb)) "dev-ann" "budget")))))

(deftest base-name-sanitizing
  (is (= "budget" (xlsx/base-name "budget" "x.xlsx")))
  (is (= "my-file" (xlsx/base-name nil "my file.xlsx")))
  (is (= "imported" (xlsx/base-name nil "###.xlsx")))
  (is (= "budget-2026" (xlsx/base-name " budget 2026 " nil))))

;; --- the apostrophe escape (engine-level, importer relies on it) ------------

(deftest apostrophe-literal-escape
  (let [s (sheet/create-sheet)]
    (try
      (sheet/set-cell! s "A1" "'123")
      (sheet/set-cell! s "A2" "'=danger")
      (sheet/set-cell! s "A3" "''quoted")
      (sheet/set-cell! s "B1" "=(str $A1 \"!\")")
      (sheet/settle! s)
      (is (= "123" (sheet/value s "A1")) "number-looking text stays text")
      (is (= "=danger" (sheet/value s "A2")) "would-be formula stays text")
      (is (= "'quoted" (sheet/value s "A3")) "only the first apostrophe escapes")
      (is (= "123!" (sheet/value s "B1")) "escaped text usable from formulas")
      (is (= "'123" (sheet/raw s "A1")) "raw keeps the apostrophe")
      (finally (sheet/close! s)))))
