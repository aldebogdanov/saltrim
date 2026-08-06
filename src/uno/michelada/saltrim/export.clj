(ns uno.michelada.saltrim.export
  "Build an .xlsx of a loaded sheet (Apache POI), LIVE where it can be.

   A formula cell exports as a real Excel formula whenever `xlformula` can spell
   it — so the workbook RECALCULATES in Excel rather than being a frozen wall of
   numbers — with SaltRim's computed result written alongside as the cached
   value, so it also opens showing the right thing before Excel recalculates.

   Where it cannot be spelled (a call into the sheet's own `def` library, a
   dynamic ref, any Clojure with no Excel name) the cell falls back to exactly
   what every cell used to get: the computed VALUE, with the Clojure source kept
   as a comment that now says the formula did not cross. The fallback is per
   CELL, so a sheet that is 90% translatable exports 90% live.

   Either way the cell carries its presentation — fill / font colour / bold /
   italic / alignment / number-format. Only the workbook WRITER is used here."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.xlformula :as xlformula])
  (:import (java.io ByteArrayOutputStream)
           (org.apache.poi.ss.usermodel FillPatternType HorizontalAlignment)
           (org.apache.poi.xssf.usermodel XSSFWorkbook XSSFColor)))

;; --- colour: CSS string -> XSSFColor (nil when unparseable, so we just skip) --

(def ^:private named-colors
  {"white" [255 255 255] "black" [0 0 0] "red" [255 0 0] "green" [0 128 0]
   "blue" [0 0 255] "yellow" [255 255 0] "orange" [255 165 0] "purple" [128 0 128]
   "gray" [128 128 128] "grey" [128 128 128] "silver" [192 192 192]
   "lime" [0 255 0] "navy" [0 0 128] "teal" [0 128 128] "maroon" [128 0 0]
   "olive" [128 128 0] "aqua" [0 255 255] "cyan" [0 255 255] "fuchsia" [255 0 255]
   "magenta" [255 0 255] "gold" [255 215 0] "pink" [255 192 203]
   "tomato" [255 99 71] "salmon" [250 128 114] "khaki" [240 230 140]
   "coral" [255 127 80] "tan" [210 180 140] "beige" [245 245 220]
   "ivory" [255 255 240] "wheat" [245 222 179]})

(defn- hex->rgb [s]
  (let [s (subs s 1)]
    (cond
      (= 3 (count s)) (mapv #(Integer/parseInt (str % %) 16) s)
      (= 6 (count s)) (mapv #(Integer/parseInt (subs s % (+ % 2)) 16) [0 2 4]))))

(defn- css->rgb
  "CSS colour string -> [r g b] (0-255), or nil if not understood."
  [s]
  (when-let [s (some-> s str/trim str/lower-case not-empty)]
    (try
      (cond
        (str/starts-with? s "#")   (hex->rgb s)
        (str/starts-with? s "rgb") (let [[r g b] (->> (re-seq #"\d+" s) (take 3)
                                                       (map #(Integer/parseInt %)))]
                                     (when (and r g b) [r g b]))
        :else                      (named-colors s))
      (catch Exception _ nil))))

(defn- xssf-color ^XSSFColor [rgb]
  (when rgb (XSSFColor. (byte-array (map unchecked-byte rgb)) nil)))

;; --- value + style mapping -------------------------------------------------

(defn- set-value! [cell v]
  (cond
    (nil? v)     nil
    (number? v)  (.setCellValue cell (double v))
    (boolean? v) (.setCellValue cell (boolean v))
    (map? v)     (.setCellValue cell (str "#ERR " (:error v)))   ; {:error msg}
    :else        (.setCellValue cell (str v))))

(def ^:private MAX-FORMULA-CHARS
  "Excel refuses a formula longer than this, and a refused formula takes the
   whole file down rather than the one cell — so an over-long translation is
   demoted to its value like any other untranslatable one. `xlformula` folds
   ranges back up precisely so this stays rare."
  8192)

(defn- formula-src
  "The cell's raw source if it is a formula, else nil."
  [sh a]
  (let [src (sheet/raw sh a)]
    (when (and src (str/starts-with? (str/trim (str src)) "=")) src)))

(defn- set-cached!
  "SaltRim's computed result as the formula cell's CACHED value, so the workbook
   opens showing numbers instead of blanks even before Excel recalculates. An
   `{:error …}` gets none — Excel will produce its own error, and writing our
   text would replace the formula with a string."
  [cell v]
  (cond
    (number? v)  (.setCellValue cell (double v))
    (boolean? v) (.setCellValue cell (boolean v))
    (string? v)  (.setCellValue cell ^String v)
    :else        nil))

(def ^:private aligns
  {"left"   HorizontalAlignment/LEFT   "right"  HorizontalAlignment/RIGHT
   "center" HorizontalAlignment/CENTER "centre" HorizontalAlignment/CENTER
   "justify" HorizontalAlignment/JUSTIFY})

(defn- prop [sh a k]
  ;; a style prop's computed value, only when it's a usable string (skip blanks
  ;; and {:error …} from a broken style formula)
  (let [v (sheet/style-value sh a k)]
    (when (string? v) (not-empty (str/trim v)))))

(defn- style-spec [sh a]
  (let [weight (some-> (prop sh a :weight) str/lower-case)
        slant  (some-> (prop sh a :slant) str/lower-case)]
    {:bg     (prop sh a :bg)
     :fg     (prop sh a :fg)
     :bold   (boolean (or (= "bold" weight) (some-> weight parse-long (>= 600))))
     :italic (boolean (#{"italic" "oblique"} slant))
     :align  (some-> (prop sh a :align) str/lower-case aligns)
     :fmt    (prop sh a :format)}))

(defn- styled? [{:keys [bg fg bold italic align fmt]}]
  (or bg fg bold italic align fmt))

(defn- safe-sheet-name [s]
  (let [s (-> (str (or s "Sheet1")) (str/replace #"[:\\/?*\[\]]" " ") str/trim)
        s (if (str/blank? s) "Sheet1" s)]
    (subs s 0 (min 31 (count s)))))

(def ^:private excel-name-re
  "What Excel accepts as a defined name: starts with a letter or underscore,
   then letters/digits/underscore/dot, and must not read as a cell address."
  #"(?i)^[a-z_][a-z0-9_.]*$")

(defn- exportable-name?
  [nm addrs]
  (and (re-matches excel-name-re nm)
       (not (addr/valid? nm))                       ; `q1` is a cell, not a name
       (<= (count nm) 255)
       (seq addrs)))

(defn- contiguous
  "[top-left bottom-right] when `addrs` fill a rectangle exactly, else nil.
   Excel's defined names take one area; a scattered label has no single one, so
   it is left out rather than exported as something it is not."
  [addrs]
  (let [ps (map addr/parse addrs)
        cs (map :ci ps) rs (map :ri ps)
        c0 (apply min cs) c1 (apply max cs)
        r0 (apply min rs) r1 (apply max rs)]
    (when (= (count addrs) (* (inc (- c1 c0)) (inc (- r1 r0))))
      [(addr/make c0 r0) (addr/make c1 r1)])))

(defn- absolutize
  "\"I1\" -> \"$I$1\". Split on the letter/digit boundary rather than a regex
   REPLACEMENT, where `$` starts a group reference and escaping it is its own
   small trap."
  [a]
  (let [i (count (take-while #(Character/isLetter ^char %) a))]
    (str "$" (subs a 0 i) "$" (subs a i))))

(defn- write-names!
  "Write the sheet's labels into the workbook as defined names."
  [wb ws labels]
  (doseq [[nm addrs] labels
          :when (exportable-name? nm addrs)
          :let [[tl br] (contiguous addrs)]
          :when tl
          ;; the whole target is built BEFORE anything is created: `createName`
          ;; registers the name immediately, so a throw half way through used to
          ;; leave a name behind that referred to nothing
          :let [target (str "'" (.getSheetName ws) "'!" (absolutize tl)
                            (when (not= tl br) (str ":" (absolutize br))))]]
    ;; a name Excel will not take is not worth failing the whole export for
    (try (doto (.createName wb)
           (.setNameName nm)
           (.setRefersToFormula target))
         (catch Exception _ nil))))

(defn workbook-bytes
  "A static .xlsx (byte[]) of sheet engine `sh`, tab named `sheet-name`."
  ^bytes [sh sheet-name]
  (with-open [wb (XSSFWorkbook.)]
    (let [ws      (.createSheet wb (safe-sheet-name sheet-name))
          data-fmt (.createDataFormat wb)
          helper  (.getCreationHelper wb)
          drawing (.createDrawingPatriarch ws)
          scache  (atom {})
          style-for
          (fn [spec]
            (when (styled? spec)
              (or (@scache spec)
                  (let [cs (.createCellStyle wb) font (.createFont wb)]
                    (when (:bold spec)   (.setBold font true))
                    (when (:italic spec) (.setItalic font true))
                    (when-let [c (xssf-color (css->rgb (:fg spec)))] (.setColor font c))
                    (.setFont cs font)
                    (when-let [c (xssf-color (css->rgb (:bg spec)))]
                      (.setFillForegroundColor cs c)
                      (.setFillPattern cs FillPatternType/SOLID_FOREGROUND))
                    (when-let [al (:align spec)] (.setAlignment cs al))
                    (when-let [m (:fmt spec)]
                      (try (.setDataFormat cs (.getFormat data-fmt m)) (catch Exception _)))
                    (swap! scache assoc spec cs)
                    cs))))
          add-comment!
          (fn [cell text]
            (try
              (let [anchor (.createClientAnchor helper)
                    _      (doto anchor
                             (.setCol1 (.getColumnIndex cell))
                             (.setRow1 (.getRowIndex cell))
                             (.setCol2 (+ 3 (.getColumnIndex cell)))
                             (.setRow2 (+ 4 (.getRowIndex cell))))
                    c      (.createCellComment drawing anchor)]
                (.setString c (.createRichTextString helper text))
                (.setCellComment cell c))
              (catch Exception _ nil)))
          addrs (->> (concat (sheet/cells sh) (keys (sheet/document-styles sh)))
                     (filter addr/valid?) distinct)
          ;; names we are about to write as defined names stay NAMES in the
          ;; formulas; the rest resolve to addresses, since Excel would have
          ;; nothing to look up
          named-areas (into {} (for [[nm as] (sheet/all-labels sh)
                                     :when (and (exportable-name? nm as) (contiguous as))]
                                 [nm as]))
          named (set (keys named-areas))
          resolve-name
          (let [addr-resolver (sheet/name-resolver sh)]
            (fn [nm shape]
              (if (named nm) (formula/name-marker nm shape) (addr-resolver nm shape))))
          live (atom 0)]
      ;; Labels back out as the workbook's DEFINED NAMES — the exact inverse of
      ;; what import does with them, so a label survives the round trip and the
      ;; formulas can keep saying `Rate` instead of `I1`.
      ;;
      ;; BEFORE the cells, not after: POI resolves a name while PARSING the
      ;; formula, so a `setCellFormula` that mentions one Excel does not know
      ;; about yet is refused — and every named formula quietly fell back to its
      ;; value.
      (write-names! wb ws named-areas)
      (doseq [a addrs]
        (let [v    (sheet/value sh a)
              spec (style-spec sh a)
              cs   (style-for spec)]
          (when (or (some? v) cs)
            (let [{:keys [ci ri]} (addr/parse a)
                  row  (or (.getRow ws ri) (.createRow ws ri))
                  cell (.createCell row ci)
                  fsrc (formula-src sh a)
                  ;; a cell that ERRORS here does not export live, even when it
                  ;; translates: Excel would compute its own answer from the same
                  ;; formula and might well succeed, and an export that quietly
                  ;; disagrees with the sheet you are looking at is worse than one
                  ;; that just shows the error. The source is in the comment.
                  xl   (when (and fsrc (not (map? v)))
                         (let [f (xlformula/try-excel fsrc resolve-name)]
                           (when (and f (<= (count f) MAX-FORMULA-CHARS)) f)))
                  ;; Translating is not the same as POI being able to WRITE it.
                  ;; `LET` is the case in hand: `xlformula` spells it correctly
                  ;; and POI's own parser then refuses it. That throw used to
                  ;; escape and take the WHOLE export down — one such cell and
                  ;; the user got no file at all — so the attempt belongs inside
                  ;; the per-cell fallback, which is where every other failure to
                  ;; cross the boundary already lands.
                  live? (boolean (when xl
                                   (try (.setCellFormula cell xl) true
                                        (catch Exception _
                                          (.setBlank cell)
                                          false))))
                  lbl  (prop sh a :label)
                  cmt  (prop sh a :comment)
                  note (cond-> []
                         cmt  (conj (str cmt))
                         live? (conj (str "Formula: " fsrc))
                         (and fsrc (not live?) (map? v))
                         (conj (str "Formula (errored here, so not exported live): " fsrc))
                         (and fsrc (not live?) (not (map? v)))
                         (conj (str "Formula (value only, no Excel equivalent): " fsrc))
                         lbl  (conj (str "Label: " lbl)))]
              (if live?
                (do (set-cached! cell v)
                    (swap! live inc))
                (set-value! cell v))
              (when cs (.setCellStyle cell cs))
              (when (seq note) (add-comment! cell (str/join "\n" note)))))))
      ;; make Excel recompute on open — the cached values we wrote are SaltRim's
      ;; answers, and the point of exporting formulas is that Excel owns them now
      (when (pos? @live) (.setForceFormulaRecalculation ws true))
      (let [baos (ByteArrayOutputStream.)]
        (.write wb baos)
        (.toByteArray baos)))))
