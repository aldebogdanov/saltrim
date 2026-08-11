(ns uno.michelada.saltrim.xlsx-test
  "Excel import: the AST->form translator, workbook reading (values / styles /
   masks / sizes / fallbacks), the demote-and-verify pass, naming, caps, and
   the apostrophe literal escape the importer relies on."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mount.core :as mount]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.xlsx :as xlsx])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.apache.poi.ss SpreadsheetVersion]
           [org.apache.poi.ss.usermodel FillPatternType HorizontalAlignment IndexedColors]
           [org.apache.poi.ss.util AreaReference CellReference]
           [org.apache.poi.xssf.usermodel XSSFCell XSSFFormulaEvaluator XSSFWorkbook]))

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

(defn- formula!
  "Set a cell's formula through the XML, plus the cached value Excel would have
   written, bypassing POI's own formula parser.

   POI can neither PARSE nor EVALUATE a structured reference —
   `setCellFormula(\"SUM(Sales[Qty])\")` throws \"Specified named range Sales
   does not exist\" — so a workbook that uses one cannot be built the ordinary
   way, and no cached value would come out of `evaluateAllFormulaCells`. Excel
   writes both happily; this is a limitation of the fixture, not of the thing
   under test. Supplying the cached value is what lets `demote-verify!` do its
   job here instead of demoting every translated cell for want of an answer to
   compare against."
  [^XSSFCell c ^String f cached]
  (.setStringValue (.addNewF (.getCTCell c)) f)
  (.setCellValue c (double cached)))

(defn- raw-bytes
  "Serialise WITHOUT evaluating formulas — POI's evaluator cannot read a
   structured reference either, so there are no cached values for these cells.
   The importer keeps the formula live regardless; `demote-verify!` simply has
   nothing to check it against."
  ^bytes [^XSSFWorkbook wb]
  (let [bos (ByteArrayOutputStream.)]
    (.write wb bos) (.close wb)
    (.toByteArray bos)))

;; --- translator --------------------------------------------------------------

(deftest translator
  (let [t   xlsx/translate-formula
        bad (fn [f] (try (t f) nil
                         (catch Exception e
                           (or (:uno.michelada.saltrim.xlsx/unsupported (ex-data e))
                               (.getMessage e)))))]
    (testing "aggregates and ranges"
      (is (= "=(sum $A1:A3)" (t "SUM(A1:A3)")))
      (is (= "=(sum (flatten (vector $A1:B2 $C3 5)))" (t "SUM(A1:B2,C3,5)")))
      (is (= "=(mean $B1:B10)" (t "AVERAGE(B1:B10)")))
      (is (= "=(xmin $A1:A9)" (t "MIN(A1:A9)")))
      (is (= "=(count (filter number? $A1:A9))" (t "COUNT(A1:A9)"))))
    (testing "IF family: lazy branches, Excel number-truthiness"
      (is (= "=(if (> $A1 2) (sum $B1:B3) 0)" (t "IF(A1>2,SUM(B1:B3),0)")))
      (is (= "=(if (excel-truthy $A1) 1 2)" (t "IF(A1,1,2)")))
      (is (= "=(if (> $A1 1) \"yes\" false)" (t "IF(A1>1,\"yes\")")) "2-arg IF: Excel yields FALSE")
      (is (= "=(and (> $A1 1) (excel-truthy $B1))" (t "AND(A1>1,B1)")))
      (is (= "=(if-error (fn [] (/ $A1 $B1)) 0)" (t "IFERROR(A1/B1,0)"))))
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
    (testing "LET becomes a Clojure let"
      (is (= "=(let [x 1 y 2] (+ (+ x y) $A1))" (t "LET(x,1,y,2,x+y+A1)")))
      (is (= "=(let [rate_ (/ $A1 100)] (* $B1 rate_))" (t "LET(rate,A1/100,B1*rate)"))
          "a local that shadows a stdlib fn is renamed, not refused — `rate` is one")
      (is (= "defined name y" (bad "LET(x,1,y+1)"))
          "a name with nothing in scope is a DEFINED name, not a local"))
    (testing "array constants"
      (is (= "=[1 2 3]" (t "{1,2,3}")) "a single row flattens")
      (is (= "=[[1 2] [3 4]]" (t "{1,2;3,4}")))
      (is (= "=(sum [1 2 3])" (t "SUM({1,2,3})"))
          "already a collection — coll-arg must not wrap it in `vector` again"))
    (testing "unsupported -> ex-info, and the reason says what it actually was"
      ;; that string lands in the cell's audit :comment, so a user reads it
      (is (= "cross-sheet reference to Other" (bad "Other!A1")))
      (is (= "whole-col reference" (bad "SUM(A:A)")))
      (is (= "defined name Tax_Rate" (bad "A1*Tax_Rate"))
          "with no workbook to resolve it against")
      (is (= "structured table reference to Sales" (bad "SUM(Sales[Amount])")))
      (is (= "spill reference (A1#)" (bad "SUM(A1#)")))
      (is (= "range intersection" (bad "SUM(A1:A3 B1:B3)")))
      (is (= "function NOSUCHFN" (bad "NOSUCHFN(A1)")) "unknown function")
      (is (re-find #"range covers" (bad "SUM(A1:CV5000)")) "over the range cap"))
    (testing "a function with no hand-written mapping still translates LIVE"
      ;; the whole point of the xl/ namespace: an imported formula we have no
      ;; Clojure name for must not demote to a dead cached number
      (is (= "=(pmt $A1 10 -1000)" (t "PMT(A1,10,-1000)"))
          "borrowed by the stdlib under a Clojure name")
      (is (= "=(norm-dist 1 0 1 true)" (t "NORM.DIST(1,0,1,TRUE)")))
      (is (= "=(str-find \"a\" $A1)" (t "FIND(\"a\",A1)")) "including the renamed ones")
      (is (= "=(xl/AVERAGEA $A1:A9)" (t "AVERAGEA(A1:A9)"))
          "not in the stdlib (text-coercion variant) -> reached verbatim through xl/")
      (is (= "=(sumif $A1:A9 \">5\")" (t "SUMIF(A1:A9,\">5\")")) "SUMIF is borrowed too")
      (is (= "=(xl/EOMONTH $A1 2)" (t "EOMONTH(A1,2)"))
          "date-shaped: only xl/ speaks Excel serials, so the borrowed name is skipped"))
    (testing "a RECTANGLE reaches an Excel function as a rectangle"
      ;; ->rv turns a flat collection into a COLUMN, so a shape-sensitive
      ;; function handed $A1:B2 silently works on a 4x1 and answers wrongly
      (is (= "=(index #area A1:B2 2 1)" (t "INDEX(A1:B2,2,1)")))
      (is (= "=(det #area A1:B2)" (t "MDETERM(A1:B2)")))
      (is (= "=(matmul #area A1:B2 #area C1:D2)" (t "MMULT(A1:B2,C1:D2)"))
          "MMULT/TRANSPOSE are OURS, so the hand-written tier areafies them itself")
      (is (= "=(transpose #area A1:B2)" (t "TRANSPOSE(A1:B2)")))
      (is (= "=(sumproduct #area A1:B2 #area A1:B2)" (t "SUMPRODUCT(A1:B2,A1:B2)")))
      (is (= "=(index $A1:A9 2)" (t "INDEX(A1:A9,2)"))
          "a single COLUMN has no shape to lose and stays flat")
      (is (= "=(index $A1:E1 2)" (t "INDEX(A1:E1,2)")) "nor does a single row")
      (is (= "=(sum $A1:B2)" (t "SUM(A1:B2)"))
          "and a hand-mapped aggregate stays flat — `sum` filters with number?,
           which a nested vector would defeat"))
    (testing "a hand-written mapping still wins over the mechanical fallback"
      (is (= "=(xmin $A1:A9)" (t "MIN(A1:A9)")) "not xl/MIN — ours skips blanks")
      (is (= "=(sum $A1:A3)" (t "SUM(A1:A3)")))
      (is (= "=(xround $A1 2)" (t "ROUND(A1,2)"))))))

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
      (is (= ["B3"] (mapv :addr (:fallbacks report))))
      (is (= "cross-sheet reference to Other" (:reason (first (:fallbacks report))))
          "the import report says what it could not translate, not a token class"))
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

;; --- defined names ----------------------------------------------------------

(defn- named-wb
  "A workbook using DEFINED NAMES the way real ones do: a named constant cell, a
   named range, a sheet-scoped name shadowing a global, a name defined over
   another name, and one pointing at a second tab. (Not a self-referential one:
   POI refuses to write that workbook at all — see the unit test below.)"
  ^bytes []
  (let [wb (XSSFWorkbook.)
        s  (.createSheet wb "Data")
        o  (.createSheet wb "Other")
        ;; scope BEFORE the name: POI checks for a duplicate at `setNameName`,
        ;; and a sheet-scoped name is only allowed to shadow a global one once
        ;; it already knows it is scoped
        nm (fn [n refers & [sheet-idx]]
             (doto (.createName wb)
               (cond-> sheet-idx (.setSheetIndex sheet-idx))
               (.setNameName n)
               (.setRefersToFormula refers)))]
    (.setCellValue (cell s 0 0) 100.0)                    ; A1
    (.setCellValue (cell s 1 0) 200.0)                    ; A2
    (.setCellValue (cell s 2 0) 0.2)                      ; A3  (the rate)
    (.setCellValue (cell o 0 0) 7.0)                      ; Other!A1
    (nm "Rate"       "Data!$A$3")
    (nm "Sales"      "Data!$A$1:$A$2")
    (nm "Doubled"    "Data!$A$3*2")                       ; an expression
    (nm "RateAgain"  "Rate")                              ; a name over a name
    (nm "Elsewhere"  "Other!$A$1")                        ; cross-sheet
    (.setCellFormula (cell s 4 0) "A1*Rate")              ; A5
    (.setCellFormula (cell s 5 0) "SUM(Sales)")           ; A6
    (.setCellFormula (cell s 6 0) "Doubled")              ; A7
    (.setCellFormula (cell s 7 0) "A1*RateAgain")         ; A8
    (.setCellFormula (cell s 8 0) "A1*Elsewhere")         ; A9 — must refuse
    (wb-bytes wb)))

(deftest defined-names-resolve-to-what-they-point-at
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (let [report (xlsx/import! (in-stream (named-wb)) "dev-ann" "named")
        data   (first (:sheets report))
        why    (into {} (for [f (:fallbacks data)] [(:addr f) (:reason f)]))]
    (testing "a name resolves to its target, and the formula stays LIVE"
      (let [{:keys [sh]} (store/load-record "dev-ann__named-Data")]
        (try
          ;; the NAME survives: it became the label of the cell it points at
          (is (= "=(* $A1 $Rate)" (sheet/raw sh "A5")))
          (is (= "Rate" (sheet/style-value sh "A3" :label)))
          (is (= 20.0 (sheet/value sh "A5")))
          (is (= "=(sum (flatten (vector $Sales)))" (sheet/raw sh "A6"))
              "a named RANGE too — the same label on every cell of it")
          (is (= ["A1" "A2"] (sheet/labelled sh "Sales")))
          (is (= 300 (sheet/value sh "A6")))
          (is (= "=(* $A3 2)" (sheet/raw sh "A7")) "a name may be an expression")
          (is (= 0.4 (sheet/value sh "A7")))
          (is (= 20.0 (sheet/value sh "A8")) "a name over a name resolves")
          (finally (sheet/close! sh)))))
    (testing "and a refusal still names what it refused"
      (is (= "cross-sheet reference to Other" (why "A9"))))))

(deftest a-sheet-scoped-name-shadows-a-global-one
  ;; Asserted on the name table rather than end-to-end: POI's own evaluator
  ;; resolves a shadowed name to the GLOBAL one, so a fixture would carry a
  ;; cached value that disagrees with the right answer and `demote-verify!`
  ;; would (correctly) demote the translation. Real workbooks carry Excel's
  ;; cached values, not POI's, so this only bites synthetic ones.
  (let [wb (XSSFWorkbook.)
        _  (.createSheet wb "Data")
        _  (.createSheet wb "Other")
        nm (fn [n refers & [idx]]
             (doto (.createName wb)
               (cond-> idx (.setSheetIndex idx))
               (.setNameName n)
               (.setRefersToFormula refers)))]
    (nm "Rate" "Data!$A$3")            ; global
    (nm "Only" "Data!$A$9")            ; global, unshadowed
    (nm "Rate" "Data!$A$1" 0)          ; scoped to Data
    (let [names (fn [idx] (#'xlsx/defined-names wb idx))]
      (is (= "Data!$A$1" (get (names 0) "Rate")) "Data sees its own")
      (is (= "Data!$A$3" (get (names 1) "Rate")) "Other sees the global")
      (is (= "Data!$A$9" (get (names 0) "Only")) "an unshadowed global reaches both"))))

(deftest a-name-needs-the-workbook
  ;; translate-formula on a bare string has no names to resolve, and must say so
  ;; rather than inventing one
  (is (thrown-with-msg? Exception #"defined name Rate"
                        (xlsx/translate-formula "A1*Rate")))
  (is (= "=(* $A1 $A3)" (xlsx/translate-formula "A1*Rate"
                                                {:tab "Data" :names {"Rate" "Data!$A$3"}})))
  (testing "a name defined in terms of itself refuses instead of recurring"
    ;; POI will not write such a workbook, but a hand-built file can carry one,
    ;; and without the guard this is a StackOverflow rather than a refusal
    (is (thrown-with-msg? Exception #"refers to itself"
                          (xlsx/translate-formula "Loop+1"
                                                  {:tab "Data" :names {"Loop" "Loop"}}))))
  (testing "a local sheet prefix is not a cross-sheet reference"
    (is (= "=$A3" (xlsx/translate-formula "Data!A3" {:tab "Data"})))
    (is (thrown-with-msg? Exception #"cross-sheet reference to Other"
                          (xlsx/translate-formula "Other!A3" {:tab "Data"})))))

;; --- structured table references --------------------------------------------

(defn- table-wb
  "A workbook with a real Excel TABLE and formulas in every structured shape."
  ^bytes []
  (let [wb (XSSFWorkbook.)
        s  (.createSheet wb "Data")
        put (fn [r c v] (let [cl (cell s r c)]
                          (if (string? v) (.setCellValue cl ^String v)
                              (.setCellValue cl (double v)))))]
    (doseq [[r vs] (map-indexed vector [["Item" "Qty" "Price"]
                                        ["a" 1 10] ["b" 2 20] ["c" 3 30]])]
      (doseq [[c v] (map-indexed vector vs)] (put r c v)))
    (doto (.createTable s (AreaReference. (CellReference. "A1") (CellReference. "C4")
                                          SpreadsheetVersion/EXCEL2007))
      (.setName "Sales")
      (.setDisplayName "Sales"))
    (formula! (cell s 1 4) "Sales[@Qty]*Sales[@Price]" 10)  ; E2 — this row
    (formula! (cell s 5 0) "SUM(Sales[Qty])" 6)             ; A6 — one column
    (formula! (cell s 6 0) "SUM(Sales[[Qty]:[Price]])" 66)  ; A7 — a column range
    (formula! (cell s 7 0) "SUM(Sales)" 66)                 ; A8 — the data body
    (formula! (cell s 8 0) "COUNTA(Sales[#Headers])" 3)     ; A9 — the header band
    (raw-bytes wb)))

(deftest structured-table-references-resolve-to-their-cells
  (db/upsert-user! {:uid "dev-ann" :name "Ann"})
  (xlsx/import! (in-stream (table-wb)) "dev-ann" "tbl")
  (let [{:keys [sh]} (store/load-record "dev-ann__tbl")]
    (try
      (testing "a column is its data band, without the header"
        (is (= "=(sum $B2:B4)" (sheet/raw sh "A6")))
        (is (= 6 (sheet/value sh "A6"))))
      (testing "a column RANGE spans them"
        (is (= "=(sum $B2:C4)" (sheet/raw sh "A7")))
        (is (= 66 (sheet/value sh "A7"))))
      (testing "the bare table name is its data body"
        (is (= "=(sum $A2:C4)" (sheet/raw sh "A8"))))
      (testing "a band specifier picks the header row"
        (is (= "=(count (remove nil? $A1:C1))" (sheet/raw sh "A9")))
        (is (= 3 (sheet/value sh "A9"))))
      (testing "and [@col] resolves against the row it is written on"
        (is (= "=(* $B2 $C2)" (sheet/raw sh "E2")))
        (is (= 10 (sheet/value sh "E2"))))
      (finally (sheet/close! sh)))))

(deftest table-refusals-say-what-they-were
  (let [T {"Sales" {:sheet "Data" :sc 0 :ec 2 :sr 0 :er 3 :hdr 1 :tot 0
                    :cols {"Item" 0 "Qty" 1 "Price" 2}}
           "Costs" {:sheet "Other" :sc 0 :ec 1 :sr 0 :er 2 :hdr 1 :tot 0
                    :cols {"K" 0 "V" 1}}}
        bad (fn [f & [addr]]
              (try (xlsx/translate-formula f (cond-> {:tab "Data" :tables T}
                                               addr (assoc :addr addr)))
                   nil
                   (catch Exception e
                     (:uno.michelada.saltrim.xlsx/unsupported (ex-data e)))))]
    (is (= "table column Sales[Nope]" (bad "SUM(Sales[Nope])")))
    (is (= "structured table reference to Ghost" (bad "SUM(Ghost[X])")))
    (is (= "table Costs is on sheet Other" (bad "SUM(Costs[V])"))
        "a table name is workbook-global, so say where it actually is")
    (is (= "table Sales has no totals row" (bad "Sales[#Totals]")))
    (is (= "Sales[@…] outside a cell" (bad "Sales[@Qty]"))
        "this-row needs to know which row")))

(deftest import-caps
  (with-redefs [xlsx/max-cells 3]
    (is (thrown-with-msg? Exception #"too large"
                          (xlsx/import! (in-stream (fixture-wb)) "dev-ann" "budget")))))

(deftest base-name-sanitizing
  (is (= "budget" (xlsx/base-name "budget" "x.xlsx")))
  (is (= "my-file" (xlsx/base-name nil "my file.xlsx")))
  (is (= "imported" (xlsx/base-name nil "###.xlsx")))
  (is (= "budget-2026" (xlsx/base-name " budget 2026 " nil))))

;; --- the hand-mapped tier, as data ------------------------------------------

(deftest hand-mapped-matches-the-translator
  ;; `xlsx/hand-mapped` is what the ƒ panel reads to decide which of Excel's
  ;; functions already have a Clojure spelling — so a name that drifts out of
  ;; the `case` below would be advertised as native and arrive as `xl/NAME`.
  (testing "every listed name really is a case in the translator"
    ;; against `fname->form` itself rather than through the parser: `TRUE` and
    ;; `FALSE` are parsed as boolean LITERALS and never reach a call node at
    ;; all, which is the strongest form of "already native"
    (let [fname->form @#'xlsx/fname->form
          ;; a range second argument, since VLOOKUP refuses a table that is not
          ;; one before it can pick a tier
          args        [(formula/ref-marker "A1") (formula/range-marker "B1" "C9") 2 false]]
      (doseq [n xlsx/hand-mapped]
        (let [form (fname->form n args)]
          (is (not (re-find #"\bxl/" (pr-str form)))
              (str n " is listed as hand-mapped but fell through to xl/"))))))
  (testing "and a name that is NOT listed does fall through"
    (is (re-find #"\bxl/" (pr-str (xlsx/translate-formula "DSUM(A1,B1,C1)"))))))

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
