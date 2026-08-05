(ns uno.michelada.saltrim.xlsx
  "Import an .xlsx workbook as SaltRim sheets — the reverse of `export`, but
   LIVE: Excel formulas are TRANSLATED to Clojure/SCI source, not snapshotted.

   Translation is a recursive walk over rechentafel's formula AST
   (`rechentafel.parser/parse` takes the formula STRING that POI hands us and
   returns structured nodes), folding it into a SaltRim marker form
   (`formula/ref-marker` / `formula/range-marker`) that `formula/unparse` prints
   as `=(…)` source. The vocabulary maps Excel functions onto the stdlib
   (SUM→sum, AVERAGE→mean, IF→if + `excel-truthy`, IFERROR→`if-error`,
   MIN/MAX→`xmin`/`xmax`, …).

   It used to walk POI's RPN `Ptg` stream with a stack machine instead, and the
   three hazards that cost the most to learn are worth recording, since they are
   what an AST buys you: a single-range SUM never arrived as a function at all
   (it was `AttrPtg(isSum)`, popping ONE operand), `IF`/`CHOOSE` arrived as a
   TRAILING `FuncVarPtg`, and post-BIFF \"future functions\" like `IFERROR`
   arrived as a `NameXPxg` pushed first plus a `#external#` `FuncVarPtg` whose
   operand count INCLUDED the name token. None of that survives the move: an
   AST node knows its own name and its own arguments. See
   `spikes/11-excel-ast-import.clj` for the side-by-side.

   The function vocabulary has three tiers. A couple of dozen names are mapped by
   HAND, because we chose different semantics (`MIN`->`xmin` skips blanks,
   `IF`->`if` + `excel-truthy`, `VLOOKUP`->`xvlookup` exact-match only). Below
   that, ~213 more are the ones `stdlib` borrowed from Excel, reached through
   `stdlib/excel-name`. Below THAT, ~414 are reached verbatim as `xl/NAME` —
   which is what the `xl/` namespace was for: an imported formula whose function
   we lack stays LIVE instead of demoting to a dead cached number. Only the first
   tier is a decision; the other two are one table lookup each, and
   `demote-verify!` checks every result against Excel's own cached value anyway.

   Anything still untranslatable falls back to the cell's CACHED value from the
   file, with the original formula kept as an audit `:comment`. The AST names each
   refusal precisely — `cross-sheet reference to Sheet2`, `whole-col reference`,
   `defined name Tax_Rate`, `structured table reference` — which matters because
   that reason is what the audit comment shows a user. They stay refusals
   because SaltRim has nowhere to put them, not because the parser hides them:
   named regions and table refs are a roadmap item, spill refs another, a whole
   column is ~1M cells against `max-range-cells`, and each Excel sheet imports
   as its OWN SaltRim sheet so a cross-sheet ref has no target. After the sheet
   is built, a demote-and-verify pass compares every translated cell against
   Excel's cached value and demotes mismatches the same way — imported sheets
   are 100% correct-or-commented.

   Values: dates become ISO yyyy-MM-dd strings (the stdlib date fns' format);
   integral doubles narrow to longs; text that SaltRim would misread (leading
   `=`/`'`, number-looking) is apostrophe-escaped. Styles map onto the five
   cell props + the `:format` mask (kept only when fmt.clj understands it);
   column/row sizes and the sheet defaults carry over."
  (:require [clojure.string :as str]
            [rechentafel.parser :as xlp]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.stdlib :as lib]
            [uno.michelada.saltrim.store :as store])
  (:import [java.io InputStream]
           [org.apache.poi.ss.usermodel CellType DateUtil FillPatternType HorizontalAlignment]
           [org.apache.poi.xssf.usermodel XSSFCell XSSFColor XSSFFont
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

;; --- Excel AST -> marker form ----------------------------------------------

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

(def ^:private bin-op
  "rechentafel's binary operator keywords -> the SaltRim/Clojure head symbol."
  '{:plus + :minus - :mul * :div / :pow pow :concat str
    :eq = :ne not= :lt < :le <= :gt > :ge >=})

;; The translation CONTEXT: what a formula needs to know beyond its own text.
;;
;;   {:tab    "Data"          the sheet being imported, so a reference that
;;                            names it is local rather than cross-sheet
;;    :names  {"Tax_Rate" "Data!$B$1"}   the workbook's defined names
;;    :seen   #{"Tax_Rate"}}  names already being resolved, so a name defined
;;                            in terms of itself refuses instead of recurring
;;
;; nil means "no workbook" — `translate-formula` on a bare string still works,
;; and every name is then unresolvable, exactly as before.

(defn- local-sheet?
  "Does this reference's sheet prefix name the tab we are importing?

   Cell formulas rarely qualify a local reference, but a DEFINED NAME always
   does — `refersToFormula` comes back as `Data!$B$1` even for a name used only
   within Data. Without this every resolved name would refuse itself as
   cross-sheet."
  [sheet ctx]
  (and sheet (:tab ctx) (= sheet (:tab ctx))))

(defn- ref->addr
  "One `:ref` node as an A1 address, or a refusal naming what it actually was.
   The AST knows; the caller only needs the reason to put in the audit comment."
  ([node] (ref->addr node nil))
  ([{:keys [sheet whole col row]} ctx]
   (when (and sheet (not (local-sheet? sheet ctx)))
     (unsupported! (str "cross-sheet reference to " sheet)))
   (when whole (unsupported! (str "whole-" (name whole) " reference")))
   (formula/ref-marker (addr/make col row))))

(defn- range-form
  "A `:range` node as range sugar, refused past `max-range-cells` — a whole
   column is ~1M cells and ranges expand statically to one ref per cell."
  ([node] (range-form node nil))
  ([{:keys [left right]} ctx]
   (let [a (second (ref->addr left ctx)) b (second (ref->addr right ctx))
         {ca :ci ra :ri} (addr/parse a)
         {cb :ci rb :ri} (addr/parse b)
         n (* (inc (abs (- ca cb))) (inc (abs (- ra rb))))]
     (when (> n max-range-cells)
       (unsupported! (str "range covers " n " cells (max " max-range-cells ")")))
     (formula/range-marker a b))))

(defn- coll-arg
  "One collection form from Excel aggregate args: a single range (or array
   constant, which is already a collection) stays as it is; scalars become a
   vector; a mix flattens ranges into the rest.

   A NAME is flattened whatever it is. `SUM(Sales)` cannot know here whether
   `Sales` labels one cell or nine — that is a fact about the sheet, settled
   when the formula is parsed against it — and the two want opposite treatment:
   a scalar must be wrapped to be summable, a range must not be wrapped or
   `COUNT` answers 1. `(flatten (vector …))` is right for both."
  [args]
  (cond
    (and (= 1 (count args))
         (or (formula/range-ref? (first args)) (vector? (first args)))) (first args)
    (or (some formula/range-ref? args) (some formula/name-ref? args))
    (list 'flatten (apply list 'vector args))
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

(def ^:private from-excel-name
  "Excel name -> the stdlib symbol that borrowed it (`stdlib/excel-name`
   inverted). Date-shaped functions are absent by construction — see there."
  (delay (into {} (for [[sym n] lib/excel-name] [n sym]))))

(def ^:private xl-names
  "`excel/exposed-names` as a set — it is a sorted vector there, for the help
   catalog's ordering."
  (delay (set excel/exposed-names)))

(defn- areafy
  "Hand a true RECTANGLE to an Excel function as a rectangle, not a flat list.

   `excel/->rv` turns a flat collection into a COLUMN, so `TRANSPOSE(A1:B2)`
   translated with `$A1:B2` transposes a 4x1 and answers `[1 2 3 4]` where Excel
   says `[1 3 2 4]` — silently, and for every shape-sensitive function
   (`INDEX`, `MMULT`, `MINVERSE`, the `LINEST` family). `#area` keeps the shape.

   Only ranges wider than one column AND taller than one row are converted: a
   1xN or Nx1 has no shape to lose, and leaving those flat keeps the common
   column-aggregate case exactly as it was. This applies to the MECHANICAL tiers
   only — the hand-written mappings above are OUR semantics over flat
   collections (`sum` and friends filter with `number?`, which a nested vector
   would defeat)."
  [args]
  (mapv (fn [a]
          (if (formula/range-ref? a)
            (let [[_ p q] a
                  {ca :ci ra :ri} (addr/parse p)
                  {cb :ci rb :ri} (addr/parse q)]
              (if (and (not= ca cb) (not= ra rb)) (formula/area-marker p q) a))
            a))
        args))

(defn- borrowed-or-xl
  "An Excel function with no hand-written mapping, translated anyway.

   Two fallbacks, in order. The stdlib borrowed ~213 of Excel's functions under
   Clojure names, so `PMT(A1,10,-1000)` can simply become `(pmt $A1 10 -1000)`.
   Failing that, `excel/exposed-names` reaches ~414 of them verbatim, so
   `TRANSPOSE(A1:B2)` becomes `(xl/TRANSPOSE $A1:B2)` — which is what the `xl/`
   namespace was FOR: an imported formula whose function we lack stays LIVE
   instead of demoting to a dead cached number. Only the hand-written cases
   above are chosen semantics; these two are mechanical, and `demote-verify!`
   still checks every one against Excel's own cached value, so a translation
   that computes something else degrades to exactly the old behaviour."
  [n args]
  (let [args (areafy args)]
    (cond
      (@from-excel-name n)   (apply list (@from-excel-name n) args)
      (@xl-names n)          (apply list (symbol "xl" n) args)
      :else                  (unsupported! (str "function " n)))))

(def hand-mapped
  "The Excel names `fname->form` translates BY HAND, below — tier one, the only
   tier that is a decision. Kept as data next to the `case` because the ƒ panel
   needs to know which of Excel's functions already have a Clojure spelling: with
   the ~238 borrowed ones these cover 267 of the 411 that `xl/` exposes, and
   listing all 411 as \"the long tail\" made the panel look like a wholesale
   duplicate of the stdlib above it.

   `xlsx-test` pins it against the `case`, so the two cannot drift."
  #{"SUM" "AVERAGE" "MEDIAN" "MIN" "MAX" "COUNT" "COUNTA" "IF" "AND" "OR" "NOT"
    "ABS" "SQRT" "EXP" "LN" "LOG10" "SIGN" "POWER" "ROUND" "CONCATENATE" "CONCAT"
    "LEN" "UPPER" "LOWER" "TRIM" "TODAY" "YEAR" "MONTH" "DAY" "DATE" "IFERROR"
    "TRUE" "FALSE" "VLOOKUP" "MMULT" "TRANSPOSE"})

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
      ;; ours, not borrowed — and they need the rectangle, which only the
      ;; mechanical tiers areafy automatically
      "MMULT"     (apply list 'matmul (areafy args))
      "TRANSPOSE" (apply list 'transpose (areafy args))
      (borrowed-or-xl n args))))

(def ^:private sandbox-names
  "Every name a translated formula can call. A `LET` local that shadows one is
   renamed rather than refused (see `safe-local`)."
  (delay (set (map str (keys formula/stdlib)))))

(defn- safe-local
  "An Excel `LET` variable as a symbol that cannot shadow a callable name.
   Shadowing is legal Clojure right up until the body CALLS the name —
   `LET(sum,5,SUM(A1:A3)+sum)` would translate to `(let [sum 5] (+ (sum …) sum))`
   and blow up. Proving the body never calls it needs a second walk; a suffix
   needs none, and `rate`/`sum`/`text` really are stdlib functions."
  [nm]
  (loop [x nm] (if (@sandbox-names x) (recur (str x "_")) (symbol x))))

(declare ast->form)

(defn- resolve-name
  "A workbook DEFINED NAME as the thing it refers to.

   Excel stores a name's target as a formula string of its own — `Tax_Rate` is
   `Data!$B$1`, `Sales` is `Data!$B$2:$B$10`, and a name may even be an
   expression (`=Data!$B$1*2`). So resolution is the translator calling itself:
   parse what the name refers to, translate THAT, and splice it in. Every
   refusal the ordinary path can make — cross-sheet, whole-column, over the
   range cap — applies unchanged, and a name pointing at another name resolves
   for free.

   The name itself does not survive into the formula. Excel resolves a name to
   an address at parse time too, and keeping it would mean a runtime indirection
   (`$(…)`) that costs a structural rebuild on every edit. What SaltRim offers
   instead, for names you write yourself, is `(def sales \"B2:B10\")` in the
   sheet's definitions library plus `$(sales)` — the same idea, opted into per
   formula rather than imposed on every imported one."
  [nm scope ctx]
  (let [target (get (:names ctx) nm)]
    (cond
      (nil? target)             (unsupported! (str "defined name " nm))
      ((:seen ctx #{}) nm)      (unsupported! (str "defined name " nm " refers to itself"))
      ;; the name is becoming a LABEL on the cells it points at, so keep it:
      ;; `=A1*Tax_Rate` arrives as `=(* $A1 $Tax_Rate)` and still reads like the
      ;; workbook it came from
      ((:labelled ctx #{}) nm)  (formula/name-marker nm)
      :else
      (let [ast (try (xlp/parse target)
                     (catch Exception _
                       (unsupported! (str "defined name " nm " refers to " target))))]
        (ast->form ast scope (update ctx :seen (fnil conj #{}) nm))))))

(defn ast->form
  "One rechentafel AST node -> a SaltRim marker form. `scope` maps the Excel
   `LET` names currently in scope to the symbols they were translated as, and
   `ctx` carries the workbook around it (see the context comment above).

   Throws (ex-info ::unsupported) on anything outside the vocabulary — the
   caller falls back to the cell's cached value. Every refusal names the
   construct it refused, because that string becomes the cell's audit comment."
  ([node] (ast->form node {} nil))
  ([node scope] (ast->form node scope nil))
  ([{:keys [op] :as n} scope ctx]
   (let [go #(ast->form % scope ctx)]
     (case op
       :num    (num-lit (:value n))
       :str    (:value n)
       :bool   (:value n)
       :ref    (ref->addr n ctx)
       :range  (range-form n ctx)
       :binop  (list (bin-op (:sym n)) (go (:left n)) (go (:right n)))
       :unop   (let [v (go (:arg n))]
                 (case (:sym n)
                   :minus (if (number? v) (- v) (list '- v))
                   :plus  v))
       :postop (case (:sym n)
                 :percent (list '/ (go (:arg n)) 100.0)
                 (unsupported! (str "postfix " (name (:sym n)))))
       :call   (fname->form (:name n) (mapv go (:args n)))
       ;; `{1,2,3}` is a row and `{1,2;3,4}` a rectangle; a single row flattens
       ;; so `SUM({1,2,3})` reads as `(sum [1 2 3])`
       :array  (let [rows (mapv #(mapv go %) (:rows n))]
                 (if (= 1 (count rows)) (first rows) rows))
       :let    (let [[scope' pairs]
                     (reduce (fn [[sc ps] [nm node]]
                               (let [sym (safe-local nm)]
                                 ;; each binding sees the ones before it, not itself
                                 [(assoc sc nm sym) (conj ps sym (ast->form node sc ctx))]))
                             [scope []] (:bindings n))]
                 (list 'let (vec pairs) (ast->form (:body n) scope' ctx)))
       ;; a bare name is a LET local if one is in scope, else one of the
       ;; workbook's DEFINED NAMES, resolved to what it refers to
       :name   (or (scope (:value n))
                   (resolve-name (:value n) scope ctx))
       :err       (unsupported! (str "error literal " (:text n "")))
       :table-ref (unsupported! (str "structured table reference to " (:table n)))
       :spill-ref (unsupported! "spill reference (A1#)")
       :intersect (unsupported! "range intersection")
       (unsupported! (str "node " op))))))

(defn translate-formula
  "Excel formula string -> SaltRim source \"=(…)\". Throws (::unsupported in
   ex-data) when it can't be translated.

   The AST parser needs no workbook, which is why the sheet index and
   `FormulaParsingWorkbook` this used to require are gone. `ctx` is optional and
   carries only what the TEXT cannot say: which tab this is, and what the
   workbook's defined names point at."
  ([fstr] (translate-formula fstr nil))
  ([fstr ctx] (str "=" (formula/unparse (ast->form (xlp/parse fstr) {} ctx)))))

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
  ([c] (read-cell c nil))
  ([^XSSFCell c ctx]
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
          (assoc base :value (translate-formula fstr ctx)
                 :cached cv :original fstr)
          (catch Exception e
            (-> base
                (assoc :value (let [s (value->src (when-not (= ::error cv) cv))]
                                (when-not (str/blank? s) s))
                       :fallback {:formula fstr
                                  :reason (or (::unsupported (ex-data e)) (.getMessage e))})
                (assoc-in [:style :comment] (str "XLSX: =" fstr))))))
      (assoc base :value nil)))))

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

(defn- defined-names
  "The workbook's defined names, as {name refers-to-formula}, for the sheet at
   `idx`.

   A name is either GLOBAL (`getSheetIndex` -1) or scoped to one sheet, and a
   sheet-scoped name shadows a global of the same name — so the global map is
   built first and the local one merged over it. Skipped: POI's built-in names
   (print areas, `_xlnm.*`), function names, and anything whose target is
   missing, since none of those can appear in a cell formula."
  [^XSSFWorkbook wb idx]
  (let [ns' (try (.getAllNames wb) (catch Exception _ nil))
        keep? (fn [^org.apache.poi.ss.usermodel.Name n]
                (and (not (.isFunctionName n))
                     (not (.isDeleted n))
                     (some? (.getNameName n))
                     (not (str/starts-with? (str (.getNameName n)) "_xlnm"))
                     (some? (try (.getRefersToFormula n) (catch Exception _ nil)))))
        entries (fn [pred]
                  (into {} (for [^org.apache.poi.ss.usermodel.Name n ns'
                                 :when (and (keep? n) (pred (.getSheetIndex n)))]
                             [(.getNameName n) (.getRefersToFormula n)])))]
    (merge (entries #(= -1 %)) (entries #(= idx %)))))

(defn- name-labels
  "{name [addr …]} for the defined names that point straight at cells ON THIS
   TAB, so the name can become their `:label` and survive into the formulas.

   SaltRim labels a cell, and a labelled cell is referenced as `$label` — which
   is what a defined name IS. So `Tax_Rate` becomes the label of the cell it
   names, `Sales` the label of every cell in its range (the same label on
   several cells is exactly a named range), and `=A1*Tax_Rate` imports as
   `=(* $A1 $Tax_Rate)` rather than having the name resolved away.

   Only names that resolve to a plain reference or range qualify. A name defined
   as an EXPRESSION (`=Data!$B$1*2`) has no cell to sit on and is still inlined,
   as is anything the translator refuses."
  [ctx]
  (into {}
        (for [[nm target] (:names ctx)
              :let [addrs (try
                            (let [{:keys [op] :as ast} (xlp/parse target)]
                              (case op
                                :ref   [(second (ref->addr ast ctx))]
                                :range (let [[_ a b] (range-form ast ctx)]
                                         (addr/range-cells a b))
                                nil))
                            (catch Exception _ nil))]
              :when (and (seq addrs) (<= (count addrs) formula/MAX-RANGE-CELLS))]
          [nm (vec addrs)])))

(defn- read-tab [^XSSFWorkbook wb idx]
  (let [^XSSFSheet s (.getSheetAt wb idx)
        base  {:tab (.getSheetName wb idx) :names (defined-names wb idx)}
        lbls  (name-labels base)
        ctx   (assoc base :labelled (set (keys lbls)))
        ;; addr -> the name to label it with. Two names on one cell is legal in
        ;; Excel and a cell has one label, so the last one wins; both still
        ;; resolve, since the loser is inlined by `resolve-name`.
        label-of (into {} (for [[nm addrs] lbls, a addrs] [a nm]))
        cells (vec (for [^XSSFRow row (seq s), ^XSSFCell c (seq row)
                         :let [m (read-cell c ctx)
                               m (cond-> m
                                   (label-of (:addr m))
                                   (assoc-in [:style :label] (label-of (:addr m))))]
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
    (let [tabs (mapv #(read-tab wb %) (range (.getNumberOfSheets wb)))
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
