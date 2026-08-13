(ns uno.michelada.saltrim.export-test
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.approx :as approx]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.export :as export])
  (:import (org.apache.poi.xssf.usermodel XSSFFormulaEvaluator XSSFWorkbook)
           (java.io ByteArrayInputStream)))

(defn- roundtrip
  "Build a sheet via `setup`, export to .xlsx, read it back, and return a map of
   helpers over the first worksheet."
  [setup]
  (let [s (sheet/create-sheet)]
    (setup s)
    (sheet/settle! s)
    (let [bytes (export/workbook-bytes s "the-sheet")
          wb    (XSSFWorkbook. (ByteArrayInputStream. bytes))
          ws    (.getSheetAt wb 0)
          cell  (fn [a] (let [{:keys [ci ri]} (addr/parse a)]
                          (some-> (.getRow ws ri) (.getCell ci))))]
      {:bytes bytes :wb wb :ws ws :cell cell})))

(deftest exports-valid-xlsx
  (let [{:keys [bytes ws]} (roundtrip (fn [s] (sheet/set-cell! s "A1" "hi")))]
    (is (= [0x50 0x4B] [(bit-and 0xff (aget bytes 0)) (bit-and 0xff (aget bytes 1))])
        "starts with the PK zip magic")
    (is (= "the-sheet" (.getSheetName ws)))))

(deftest formula-exports-live
  ;; a formula Excel can spell is written as a real Excel formula, with SaltRim's
  ;; answer as the cached value so the file opens showing it. The Clojure source
  ;; still rides along as a comment.
  (let [{:keys [cell ws]} (roundtrip
                           (fn [s]
                             (sheet/set-cell! s "B1" "100")
                             (sheet/set-cell! s "B2" "=(+ #cell B1 242)")))]
    (is (= 100.0 (.getNumericCellValue (cell "B1"))))
    (is (= "FORMULA" (str (.getCellType (cell "B2")))))
    (is (= "B1+242" (.getCellFormula (cell "B2"))))
    (is (= 342.0 (.getNumericCellValue (cell "B2"))) "our answer is the cached value")
    (is (.getForceFormulaRecalculation ws) "and Excel is asked to recompute on open")
    (is (= "Formula: =(+ #cell B1 242)"
           (some-> (cell "B2") .getCellComment .getString .getString))
        "the Clojure source rides along as a comment")
    (is (nil? (some-> (cell "B1") .getCellComment)) "literals get no formula comment")))

(deftest a-formula-excel-cannot-spell-falls-back-to-its-value
  ;; the fallback is per CELL, so one untranslatable formula does not cost the
  ;; sheet its live ones
  (let [{:keys [cell]} (roundtrip
                        (fn [s]
                          (sheet/set-cell! s "A1" "3")
                          (sheet/set-cell! s "A2" "4")
                          (sheet/set-cell! s "B1" "=(sum $A1:A2)")
                          (sheet/set-cell! s "B2" "=(reduce + (map (fn [x] (* x x)) $A1:A2))")))]
    (is (= "SUM(A1:A2)" (.getCellFormula (cell "B1"))) "this one crosses")
    (is (= "NUMERIC" (str (.getCellType (cell "B2")))) "this one does not")
    (is (= 25.0 (.getNumericCellValue (cell "B2"))) "and keeps its computed value")
    (is (= "Formula (value only, no Excel equivalent): =(reduce + (map (fn [x] (* x x)) $A1:A2))"
           (some-> (cell "B2") .getCellComment .getString .getString))
        "the comment says the formula did not cross, so nobody assumes it is live")))

(deftest excel-recomputes-what-we-exported
  ;; the strongest check available offline: hand the file to POI's own Excel
  ;; formula engine — code we did not write — and see whether it agrees with
  ;; SaltRim cell for cell
  (let [{:keys [wb ws]} (roundtrip
                         (fn [s]
                           (doseq [i (range 5)]
                             (sheet/set-cell! s (str "A" (inc i)) (str (+ 2 i))))
                           (sheet/set-cell! s "B1" "=(sum $A1:A5)")
                           (sheet/set-cell! s "B2" "=(if (> $A1 1) (* $A1 10) 0)")
                           (sheet/set-cell! s "B3" "=(mean $A1:A5)")
                           (sheet/set-cell! s "B4" "=(xmax $A1:A5)")
                           (sheet/set-cell! s "B5" "=(pmt 0.08 10 -1000)")))
        cel  (fn [i] (.getCell (.getRow ws i) 1))]
    (is (every? #(= "FORMULA" (str (.getCellType (cel %)))) (range 5))
        "all five exported as formulas")
    (XSSFFormulaEvaluator/evaluateAllFormulaCells wb)
    (let [got (mapv #(.getNumericCellValue (cel %)) (range 5))]
      (is (= [20.0 20.0 4.0 6.0] (subvec got 0 4))
          "Excel's own engine recomputes them to SaltRim's answers")
      ;; PMT goes through Math/pow in POI too, so it is platform-dependent in
      ;; the last ulp — see the approx ns
      (is (approx/= 149.02948869707532 (nth got 4))))))

(deftest comment-label-and-formula-note-compose
  ;; three independent things want the ONE Excel comment a cell gets: the user's
  ;; own :comment (which is also where the .xlsx IMPORTER leaves its audit
  ;; trail), the note about the formula, and the :label. They stack in that
  ;; order — none of them overwrites another.
  (let [{:keys [cell]} (roundtrip
                        (fn [s]
                          (sheet/set-cell! s "A1" "3")
                          (sheet/set-cell! s "B1" "=(sum $A1:A1)")
                          (sheet/set-style! s "B1" :comment "checked by Ann")
                          (sheet/set-style! s "B1" :label "total")
                          (sheet/set-cell! s "B2" "=(reduce + $A1:A1)")
                          (sheet/set-style! s "B2" :comment "XLSX: =SUM(A1)")
                          (sheet/set-style! s "B2" :label "squares")
                          (sheet/set-cell! s "B3" "7")
                          (sheet/set-style! s "B3" :comment "just a note")
                          (sheet/set-style! s "B3" :label "seven")))
        note (fn [a] (some-> (cell a) .getCellComment .getString .getString))]
    (is (= "checked by Ann\nFormula: =(sum $A1:A1)\nLabel: total" (note "B1"))
        "a live formula keeps the user's comment above it and the label below")
    (is (= (str "XLSX: =SUM(A1)\n"
                "Formula (value only, no Excel equivalent): =(reduce + $A1:A1)\n"
                "Label: squares")
           (note "B2"))
        "an import's audit trail survives alongside the export's own note")
    (is (= "just a note\nLabel: seven" (note "B3"))
        "and a cell with no formula gets no formula line")))

(deftest text-and-errors
  (let [{:keys [cell]} (roundtrip
                        (fn [s]
                          (sheet/set-cell! s "A1" "Сумма")          ; unicode text
                          (sheet/set-cell! s "A2" "=(/ 1 0)")))]    ; runtime error
    (is (= "Сумма" (.getStringCellValue (cell "A1"))))
    (is (clojure.string/starts-with? (.getStringCellValue (cell "A2")) "#ERR")
        "an erroring cell exports its #ERR text, never a broken Excel formula")
    (is (= "STRING" (str (.getCellType (cell "A2"))))
        "and NOT a live formula, even though `1/0` translates perfectly well —
         Excel would compute its own answer and quietly disagree with the sheet")
    (is (= "Formula (errored here, so not exported live): =(/ 1 0)"
           (some-> (cell "A2") .getCellComment .getString .getString)))))

(deftest styles-and-number-format-carry
  (let [{:keys [wb cell]} (roundtrip
                           (fn [s]
                             (sheet/set-cell! s "A1" "bold")
                             (sheet/set-style! s "A1" :weight "bold")
                             (sheet/set-cell! s "B1" "5")
                             (sheet/set-style! s "B1" :bg "#ffcc00")
                             (sheet/set-cell! s "C1" "1234.5")
                             (sheet/set-style! s "C1" :format "#,##0.00")))
        bold? (fn [c] (.getBold (.getFontAt wb (.getFontIndexAsInt (.getCellStyle c)))))]
    (is (true? (bold? (cell "A1"))) "weight=bold -> bold font")
    (is (= "FFFFCC00"
           (.getARGBHex (.getFillForegroundColorColor (.getCellStyle (cell "B1")))))
        "bg=#ffcc00 -> solid fill")
    (is (= "#,##0.00" (.getDataFormatString (.getCellStyle (cell "C1"))))
        "number-format mask passes through as an Excel format code")))

(deftest empty-sheet-exports
  (testing "a sheet with no cells still produces a valid workbook"
    (let [{:keys [bytes ws]} (roundtrip (fn [_] nil))]
      (is (pos? (count bytes)))
      (is (some? ws)))))
