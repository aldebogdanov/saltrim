(ns uno.michelada.saltrim.xlsx
  "Import an .xlsx workbook as SaltRim sheets — the reverse of `export`, but
   LIVE: Excel formulas are TRANSLATED to Clojure/SCI source, not snapshotted.

   Translation rides POI's own formula parser: `FormulaParser/parse` returns
   the formula as RPN `Ptg` tokens, which a small stack machine folds into a
   SaltRim marker form (`formula/ref-marker` / `formula/range-marker`), then
   `formula/unparse` prints as `=(…)` source. The vocabulary maps Excel
   functions onto the stdlib (SUM→sum, AVERAGE→mean, IF→if + `excel-truthy`,
   IFERROR→`if-error`, MIN/MAX→`xmin`/`xmax`, …).

   Token model (verified empirically against POI 5.5.1):
   - IF/CHOOSE arrive as a trailing FuncVarPtg with the full operand count;
     the AttrPtg control tokens (optimizedIf/skip/space/…) are ignorable.
   - single-range SUM never arrives as a function — it is AttrPtg(isSum),
     popping ONE operand.
   - post-BIFF \"future functions\" (IFERROR, …) arrive as a NameXPxg pushed
     FIRST plus a trailing FuncVarPtg named \"#external#\" whose operand count
     INCLUDES the NameXPxg.

   Anything untranslatable (cross-sheet refs, named ranges, whole-column
   ranges, unknown functions, …) falls back to the cell's CACHED value from
   the file, with the original formula kept as an audit `:comment`. After the
   sheet is built, a demote-and-verify pass compares every translated cell
   against Excel's cached value and demotes mismatches the same way — imported
   sheets are 100% correct-or-commented.

   Values: dates become ISO yyyy-MM-dd strings (the stdlib date fns' format);
   integral doubles narrow to longs; text that SaltRim would misread (leading
   `=`/`'`, number-looking) is apostrophe-escaped. Styles map onto the five
   cell props + the `:format` mask (kept only when fmt.clj understands it);
   column/row sizes and the sheet defaults carry over."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store])
  (:import [java.io InputStream]
           [org.apache.poi.ss.usermodel CellType DateUtil FillPatternType HorizontalAlignment]
           [org.apache.poi.ss.formula FormulaParsingWorkbook FormulaParser FormulaType]
           [org.apache.poi.ss.formula.ptg Ptg IntPtg NumberPtg StringPtg BoolPtg MissingArgPtg
            RefPtg AreaPtg NameXPxg AddPtg SubtractPtg MultiplyPtg DividePtg PowerPtg ConcatPtg
            UnaryMinusPtg UnaryPlusPtg PercentPtg EqualPtg NotEqualPtg LessThanPtg LessEqualPtg
            GreaterThanPtg GreaterEqualPtg ParenthesisPtg AttrPtg AbstractFunctionPtg]
           [org.apache.poi.xssf.usermodel XSSFCell XSSFColor XSSFEvaluationWorkbook XSSFFont
            XSSFRow XSSFSheet XSSFWorkbook]))

(def max-cells
  "Refuse workbooks with more non-empty cells than this — every imported cell
   becomes a reactive node."
  20000)

(def max-bytes
  "Upload size cap for /import."
  (* 8 1024 1024))

(def max-range-cells
  "A single range reference bigger than this (whole columns are ~1M rows) is
   untranslatable — ranges expand statically to per-cell refs."
  4096)

;; --- Ptg[] -> marker form -------------------------------------------------

(defn- unsupported!
  [reason]
  (throw (ex-info (str "unsupported: " reason) {::unsupported reason})))

(defn- num-lit
  "An Excel double as a Clojure literal — integral values narrow to Long
   (Excel numbers are all doubles; 3.0 in a cell means 3)."
  [^double d]
  (if (and (Double/isFinite d) (== d (Math/rint d))
           (<= -9.007199254740992E15 d 9.007199254740992E15))
    (long d)
    d))

(defn- ref-form [^RefPtg p]
  (formula/ref-marker (addr/make (.getColumn p) (.getRow p))))

(defn- area-form [^AreaPtg p]
  (let [r0 (.getFirstRow p) r1 (.getLastRow p)
        c0 (.getFirstColumn p) c1 (.getLastColumn p)]
    (when (> (* (inc (- r1 r0)) (inc (- c1 c0))) max-range-cells)
      (unsupported! "range too large (whole column/row?)"))
    (formula/range-marker (addr/make c0 r0) (addr/make c1 r1))))

(def ^:private bin-op
  {AddPtg '+ SubtractPtg '- MultiplyPtg '* DividePtg '/ PowerPtg 'pow ConcatPtg 'str
   EqualPtg '= NotEqualPtg 'not= LessThanPtg '< LessEqualPtg '<=
   GreaterThanPtg '> GreaterEqualPtg '>=})

(defn- fname-token? [x] (and (seq? x) (= ::fname (first x))))

(defn- coll-arg
  "One collection form from Excel aggregate args: a single range stays range
   sugar; scalars become a vector; a mix flattens ranges into the rest."
  [args]
  (cond
    (and (= 1 (count args)) (formula/range-ref? (first args))) (first args)
    (some formula/range-ref? args) (list 'flatten (apply list 'vector args))
    :else (apply list 'vector args)))

(def ^:private boolish-heads
  '#{= not= < > <= >= and or not blank? starts-with? ends-with? includes? if-error})

(defn- truthy-wrap
  "Excel conditions are number-truthy (0=false); wrap in `excel-truthy` unless
   the form is already obviously boolean."
  [c]
  (if (or (boolean? c) (and (seq? c) (boolish-heads (first c))))
    c
    (list 'excel-truthy c)))

(defn- vlookup-form [args]
  (let [[k rng col approx] args]
    (when-not (formula/range-ref? rng)
      (unsupported! "VLOOKUP over a non-range table"))
    (when-not (or (false? approx) (and (number? approx) (zero? approx)))
      (unsupported! "VLOOKUP approximate match (pass FALSE as the 4th argument)"))
    (let [w (inc (- (:ci (addr/parse (nth rng 2))) (:ci (addr/parse (nth rng 1)))))]
      (list 'xvlookup k rng w col))))

(defn- fname->form
  "One Excel function call (name + already-translated args) as a SaltRim form."
  [name args]
  (let [n (str/upper-case (str/replace (str name) #"^_xlfn\." ""))
        nargs (count args)]
    (case n
      "SUM"     (list 'sum (coll-arg args))
      "AVERAGE" (list 'mean (coll-arg args))
      "MEDIAN"  (list 'median (coll-arg args))
      "MIN"     (list 'xmin (coll-arg args))
      "MAX"     (list 'xmax (coll-arg args))
      "COUNT"   (list 'count (list 'filter 'number? (coll-arg args)))
      "COUNTA"  (list 'count (list 'remove 'nil? (coll-arg args)))
      "IF"      (list 'if (truthy-wrap (first args)) (second args)
                      (if (= 2 nargs) false (nth args 2)))
      "AND"     (apply list 'and (map truthy-wrap args))
      "OR"      (apply list 'or (map truthy-wrap args))
      "NOT"     (list 'not (truthy-wrap (first args)))
      "ABS"     (list 'abs (first args))
      "SQRT"    (list 'sqrt (first args))
      "EXP"     (list 'exp (first args))
      "LN"      (list 'ln (first args))
      "LOG10"   (list 'log10 (first args))
      "SIGN"    (list 'sign (first args))
      "POWER"   (list 'pow (first args) (second args))
      "ROUND"   (if (= 2 nargs)
                  (list 'xround (first args) (second args))
                  (list 'round (first args)))
      ("CONCATENATE" "CONCAT")
      (if (some formula/range-ref? args)
        (list 'join "" (coll-arg args))
        (apply list 'str args))
      "LEN"     (list 'count (list 'str (first args)))
      "UPPER"   (list 'upper (list 'str (first args)))
      "LOWER"   (list 'lower (list 'str (first args)))
      "TRIM"    (list 'trim (list 'str (first args)))
      "TODAY"   (list 'today)
      "YEAR"    (list 'year (first args))
      "MONTH"   (list 'month (first args))
      "DAY"     (list 'day (first args))
      "DATE"    (apply list 'xdate args)
      "IFERROR" (list 'if-error (list 'fn [] (first args)) (second args))
      "TRUE"    true
      "FALSE"   false
      "VLOOKUP" (vlookup-form args)
      (unsupported! (str "function " n)))))

(defn ptgs->form
  "POI RPN tokens -> one SaltRim marker form (stack machine). Throws
   (ex-info ::unsupported) on anything outside the vocabulary — the caller
   falls back to the cached value."
  [ptgs]
  (let [stack (volatile! [])
        push! (fn [x] (vswap! stack conj x))
        pop-n! (fn [n]
                 (let [st @stack]
                   (when (< (count st) n) (unsupported! "malformed formula (stack underflow)"))
                   (vreset! stack (subvec st 0 (- (count st) n)))
                   (subvec st (- (count st) n))))]
    (doseq [^Ptg p ptgs]
      (cond
        (instance? IntPtg p)        (push! (long (.getValue ^IntPtg p)))
        (instance? NumberPtg p)     (push! (num-lit (.getValue ^NumberPtg p)))
        (instance? StringPtg p)     (push! (.getValue ^StringPtg p))
        (instance? BoolPtg p)       (push! (.getValue ^BoolPtg p))
        (instance? MissingArgPtg p) (push! nil)
        (instance? RefPtg p)        (push! (ref-form p))
        (instance? AreaPtg p)       (push! (area-form p))
        (instance? NameXPxg p)      (push! (list ::fname (.getNameName ^NameXPxg p)))
        (bin-op (class p))          (let [[a b] (pop-n! 2)] (push! (list (bin-op (class p)) a b)))
        (instance? UnaryMinusPtg p) (let [[x] (pop-n! 1)]
                                      (push! (if (number? x) (- x) (list '- x))))
        (instance? UnaryPlusPtg p)  nil
        (instance? PercentPtg p)    (let [[x] (pop-n! 1)] (push! (list '/ x 100.0)))
        (instance? ParenthesisPtg p) nil
        (instance? AttrPtg p)       (when (.isSum ^AttrPtg p)
                                      (let [[x] (pop-n! 1)] (push! (list 'sum (coll-arg [x])))))
        (instance? AbstractFunctionPtg p)
        (let [^AbstractFunctionPtg f p
              args (pop-n! (.getNumberOfOperands f))
              [name args] (if (= "#external#" (.getName f))
                            (if (fname-token? (first args))
                              [(second (first args)) (rest args)]
                              (unsupported! "external function without a name"))
                            [(.getName f) args])]
          (when (some fname-token? args) (unsupported! "nested name token"))
          (push! (fname->form name args)))

        :else (unsupported! (.getSimpleName (class p)))))
    (let [st @stack]
      (if (= 1 (count st))
        (first st)
        (unsupported! "malformed formula (unbalanced)")))))

(defn translate-formula
  "Excel formula string -> SaltRim source \"=(…)\". Throws (::unsupported in
   ex-data) when it can't be translated."
  [fstr ^FormulaParsingWorkbook pwb sheet-idx]
  (str "=" (formula/unparse (ptgs->form (FormulaParser/parse fstr pwb FormulaType/CELL (int sheet-idx))))))

;; --- cell values / styles --------------------------------------------------

(defn- text-src
  "Source for an Excel TEXT cell: apostrophe-escape anything SaltRim would
   misread as a formula, an escape, or a number."
  [s]
  (if (or (str/starts-with? s "=")
          (str/starts-with? s "'")
          (re-matches #"\s*[-+]?\d+\s*" s)
          (re-matches #"\s*[-+]?\d*\.\d+([eE]\d+)?\s*" s))
    (str "'" s)
    s))

(defn- value->src
  "A cached/computed Excel value as SaltRim cell source."
  [v]
  (cond
    (nil? v)     ""
    (number? v)  (str (num-lit (double v)))
    (boolean? v) (str "=" v)
    :else        (text-src (str v))))

(defn- date-cell? [^XSSFCell c]
  (try (DateUtil/isCellDateFormatted c) (catch Exception _ false)))

(defn- iso-date [^XSSFCell c]
  (str (.toLocalDate (.getLocalDateTimeCellValue c))))

(defn- cached-value
  "Excel's last-computed value of a FORMULA cell, as a Clojure value
   (::error when the cache is an error; dates as ISO strings)."
  [^XSSFCell c]
  (condp = (.getCachedFormulaResultType c)
    CellType/NUMERIC (if (date-cell? c) (iso-date c) (num-lit (.getNumericCellValue c)))
    CellType/STRING  (.getStringCellValue c)
    CellType/BOOLEAN (.getBooleanCellValue c)
    CellType/ERROR   ::error
    nil))

(defn- color-hex [^XSSFColor xc]
  (when xc
    (when-let [rgb (.getRGB xc)]
      (let [rgb (if (= 4 (alength rgb)) (java.util.Arrays/copyOfRange rgb 1 4) rgb)]
        (when (= 3 (alength rgb))
          (format "#%02x%02x%02x"
                  (bit-and 255 (aget rgb 0)) (bit-and 255 (aget rgb 1))
                  (bit-and 255 (aget rgb 2))))))))

(def ^:private mask-ok-re
  ;; the fmt.clj token subset: literal prefix, digits (#0 with , grouping),
  ;; optional decimals, optional %, literal suffix — no ; sections, no [] codes,
  ;; no date letters (letters would land in prefix/suffix, which may not
  ;; contain digit tokens, so "yyyy-mm-dd" and "0.0E+00" both fail).
  #"[^0#.,%]*[0#][0#,]*(\.[0#]+)?%?[^0#.,%]*")

(defn- style-props
  "SaltRim style/format props of one cell (only explicit, non-default ones).
   Returns [props dropped-mask-or-nil]."
  [^XSSFCell c]
  (let [cs (.getCellStyle c)
        ^XSSFFont font (.getFont cs)
        fg   (color-hex (.getXSSFColor font))
        bg   (when (= FillPatternType/SOLID_FOREGROUND (.getFillPattern cs))
               (color-hex (.getFillForegroundColorColor cs)))
        al   (condp = (.getAlignment cs)
               HorizontalAlignment/LEFT "left" HorizontalAlignment/CENTER "center"
               HorizontalAlignment/RIGHT "right" nil)
        mask (let [m (.getDataFormatString cs)]
               (when-not (or (str/blank? m) (#{"General" "@"} m)) m))
        ok   (and mask (re-matches mask-ok-re mask) (not (date-cell? c)))
        props (cond-> {}
                (.getBold font)              (assoc :weight "bold")
                (.getItalic font)            (assoc :slant "italic")
                (and fg (not= "#000000" fg)) (assoc :fg fg)
                bg                           (assoc :bg bg)
                al                           (assoc :align al)
                ok                           (assoc :format mask))]
    [props (when (and mask (not ok) (not (date-cell? c))) mask)]))

;; --- workbook walk ----------------------------------------------------------

(defn- read-cell
  "One physical cell -> {:addr :value :style :cached :original :fallback} —
   :value nil means skip (blank); :cached/:original only for translated
   formulas; :fallback {:formula :reason} when translation failed."
  [^XSSFCell c pwb sheet-idx]
  (let [a (addr/make (.getColumnIndex c) (.getRowIndex c))
        [props dropped] (style-props c)
        base {:addr a :style props :dropped-mask dropped}]
    (condp = (.getCellType c)
      CellType/BLANK   (assoc base :value nil)
      CellType/STRING  (assoc base :value (text-src (.getStringCellValue c)))
      CellType/NUMERIC (assoc base :value (if (date-cell? c)
                                            (iso-date c)
                                            (str (num-lit (.getNumericCellValue c)))))
      CellType/BOOLEAN (assoc base :value (str "=" (.getBooleanCellValue c)))
      CellType/ERROR   (assoc base :value nil)
      CellType/FORMULA
      (let [fstr (.getCellFormula c)
            cv   (cached-value c)]
        (try
          (assoc base :value (translate-formula fstr pwb sheet-idx)
                 :cached cv :original fstr)
          (catch Exception e
            (-> base
                (assoc :value (let [s (value->src (when-not (= ::error cv) cv))]
                                (when-not (str/blank? s) s))
                       :fallback {:formula fstr
                                  :reason (or (::unsupported (ex-data e)) (.getMessage e))})
                (assoc-in [:style :comment] (str "XLSX: =" fstr))))))
      (assoc base :value nil))))

(defn- read-sizing [^XSSFSheet s used-cols]
  (let [dcw-chars (.getDefaultColumnWidth s)
        drh-pts   (.getDefaultRowHeightInPoints s)
        cols (into {} (for [ci used-cols
                            :let [w (.getColumnWidth s (int ci))]
                            :when (< 64 (Math/abs (- w (* 256 dcw-chars))))]
                        [ci (max 8 (Math/round (.getColumnWidthInPixels s (int ci))))]))
        rows (into {} (for [^XSSFRow r (seq s)
                            :let [h (.getHeightInPoints r)]
                            :when (< 0.5 (Math/abs (- h drh-pts)))]
                        [(.getRowNum r) (max 8 (Math/round (* h (/ 4.0 3.0))))]))]
    {:cols cols :rows rows
     :dcw (max 24 (Math/round (+ (* 7.0 dcw-chars) 5.0)))
     :drh (max 12 (Math/round (* drh-pts (/ 4.0 3.0))))}))

(defn- read-tab [^XSSFWorkbook wb pwb idx]
  (let [^XSSFSheet s (.getSheetAt wb idx)
        cells (vec (for [^XSSFRow row (seq s), ^XSSFCell c (seq row)
                         :let [m (read-cell c pwb idx)]
                         :when (:value m)]
                     m))
        doc   (into {} (for [{:keys [addr value style]} cells]
                         [addr (cond-> {:value value}
                                 (seq style) (assoc :style style))]))]
    (merge
     {:name (.getSheetName wb idx)
      :doc doc
      :cached    (into {} (keep (fn [m] (when (contains? m :cached) [(:addr m) (:cached m)])) cells))
      :originals (into {} (keep (fn [m] (when (:original m) [(:addr m) (:original m)])) cells))
      :report {:cells     (count cells)
               :formulas  (count (filter :original cells))
               :fallbacks (vec (keep (fn [m] (when (:fallback m)
                                               (assoc (:fallback m) :addr (:addr m)))) cells))
               :masks-dropped (vec (distinct (keep :dropped-mask cells)))}}
     (read-sizing s (distinct (map #(:ci (addr/parse (:addr %))) cells))))))

(defn read-workbook
  "InputStream -> {:tabs [{:name :doc :cached :originals :cols :rows :dcw :drh
   :report} …]}. Throws on the total-cell cap."
  [^InputStream in]
  (with-open [wb (XSSFWorkbook. in)]
    (let [pwb  (XSSFEvaluationWorkbook/create wb)
          tabs (mapv #(read-tab wb pwb %) (range (.getNumberOfSheets wb)))
          total (reduce + (map #(get-in % [:report :cells]) tabs))]
      (when (> total max-cells)
        (throw (ex-info (str "workbook too large: " total " cells (max " max-cells ")")
                        {:cells total})))
      {:tabs tabs})))

;; --- build + verify + persist ----------------------------------------------

(defn- close-num? [a b]
  (and (number? a) (number? b)
       (< (Math/abs (- (double a) (double b)))
          (* 1e-9 (max 1.0 (Math/abs (double a)) (Math/abs (double b)))))))

(defn- matches? [ours cached] (or (= ours cached) (close-num? ours cached)))

(defn- demote-verify!
  "Force every translated formula cell to agree with Excel's cached value:
   erroring or mismatching cells become the cached literal + an audit :comment.
   Loops until stable (a demotion changes downstream inputs). Returns
   [{:addr :cached :was} …]."
  [sh cached originals]
  (loop [acc []]
    (let [ds (vec (for [[a cv] cached
                        :let [v (sheet/value sh a)]
                        :when (and (some? cv) (not= ::error cv)
                                   (or (and (map? v) (:error v)) (not (matches? v cv))))]
                    {:addr a :cached cv :was (if (map? v) (:error v) v)}))]
      (if (empty? ds)
        acc
        (do (doseq [{:keys [addr cached]} ds]
              (sheet/set-cell! sh addr (value->src cached))
              (sheet/set-style! sh addr :comment (str "XLSX: =" (originals addr))))
            (sheet/settle! sh)
            (recur (into acc ds)))))))

(defn- build-tab!
  "One read tab -> a live, verified sheet engine. Caller owns closing it."
  [{:keys [doc cols rows dcw drh cached originals]}]
  (let [sh (sheet/create-sheet)]
    (try
      (sheet/load-document! sh doc)
      (sheet/load-sizing! sh cols rows)
      (sheet/set-default-col-w! sh dcw)
      (sheet/set-default-row-h! sh drh)
      (sheet/settle! sh)
      {:sh sh :demoted (demote-verify! sh cached originals)}
      (catch Throwable e (sheet/close! sh) (throw e)))))

(defn- sanitize-name [s fallback]
  (let [n (-> (str s)
              (str/replace #"\.xlsx$" "")
              (str/replace #"[^A-Za-z0-9-]+" "-")
              (str/replace #"-{2,}" "-")
              (str/replace #"(^-+)|(-+$)" ""))
        n (subs n 0 (min 32 (count n)))
        n (str/replace n #"-+$" "")]
    (if (store/valid-name? n) n fallback)))

(defn base-name
  "The import's base sheet name: the user's input if any, else the upload's
   filename, sanitized to the sheet-name charset."
  [input filename]
  (sanitize-name (or (not-empty (str/trim (str input))) (str filename)) "imported"))

(defn- unique-name
  "First free sheet name for `uid` among base, base-2 … base-99 (also avoiding
   `taken` from this batch)."
  [uid base taken]
  (or (some (fn [n] (let [id (store/storage-id uid n)]
                      (when (and id (not (taken n)) (not (store/exists? id))) n)))
            (cons base (map #(str (subs base 0 (min 29 (count base))) "-" %) (range 2 100))))
      (throw (ex-info "no free sheet name" {:base base}))))

(defn import!
  "Import every tab of the workbook `in` as NEW sheets owned by `uid`, named
   <base> (single tab) or <base>-<tab> (multi). All tabs are built and
   verified BEFORE anything persists — a failing tab aborts the whole import.
   Returns {:sheets [{:sname :cells :formulas :fallbacks :demoted
   :masks-dropped} …]}."
  [^InputStream in uid base]
  (let [{:keys [tabs]} (read-workbook in)
        multi? (< 1 (count tabs))
        named  (loop [ts tabs taken #{} out []]
                 (if-let [t (first ts)]
                   (let [want (if multi?
                                (sanitize-name (str base "-" (:name t)) (str base "-tab"))
                                base)
                         n    (unique-name uid want taken)]
                     (recur (rest ts) (conj taken n) (conj out (assoc t :sname n))))
                   out))
        built  (atom [])]
    (try
      (doseq [t named] (swap! built conj (assoc (build-tab! t) :tab t)))
      (doseq [{:keys [sh tab]} @built]
        (let [id (store/storage-id uid (:sname tab))]
          (db/ensure-sheet! id uid (:sname tab))
          (store/save! id sh {:author uid})))
      {:sheets (mapv (fn [{:keys [demoted tab]}]
                       (merge {:sname (:sname tab) :tab (:name tab)
                               :demoted (mapv #(assoc % :formula (get (:originals tab) (:addr %)))
                                              demoted)}
                              (:report tab)))
                     @built)}
      (finally (doseq [{:keys [sh]} @built] (sheet/close! sh))))))
