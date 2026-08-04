(ns uno.michelada.saltrim.excel
  "Excel's function library, behind an `xl/` interop namespace.

   SaltRim's formula language is Clojure — that is the whole point of the
   project, and Excel is a BOUNDARY, not a second language. So nothing here is
   callable bare: every function is reached as `(xl/PMT 0.08 10 -1000)`, and a
   formula you write yourself gets Clojure and only Clojure. The `xl/` prefix
   exists for two jobs:

     - **import** — an .xlsx formula whose function we have no native
       equivalent for stays LIVE as an `xl/…` call instead of being demoted to
       a dead cached number (what `xlsx.clj` has to do today);
     - **export** — a cell carrying `xl/PMT` maps back to a real Excel formula
       exactly, which a static value cannot.

   Reading a cell and seeing `xl/` is the point: it says \"this came from a
   spreadsheet\". The functions worth having in SaltRim get proper Clojure
   names in the native stdlib instead; this namespace is the long tail and the
   escape hatch.

   We embed **rechentafel** (`org.replikativ/rechentafel`, Apache-2.0) — a pure
   Clojure Excel interpreter — but use ONLY its function pack. Its evaluator is
   a pull-based dirty-set/topological recalc, the exact opposite of SaltRim's
   push-based Spindel graph, so `rechentafel.eval` is never called: every
   function we expose is a `(fn [args] value)` that runs standalone, with no
   workbook, no dependency graph and no recalc. See
   `doc/rechentafel-evaluation.md` for the full evaluation.

   The seam is the VALUE MODEL. rechentafel speaks tagged maps with Excel's
   coercion rules (`{:t :num :v 30.0}`, `{:t :blank}`, `{:t :err :v :div0}`);
   SaltRim formulas speak plain Clojure (a number is a number, a blank cell is
   `nil`). `->rv` / `<-rv` translate, and `call` wraps one invocation:

     (call \"PMT\" [0.08 10 -1000])   ;=> 149.02948869707532
     (call \"MOD\" [1 0])             ;=> throws \"#DIV/0!\"

   Excel errors come back as VALUES, not stack traces, so we turn them into an
   `ex-info` carrying the Excel display name — the sheet layer renders that as
   the cell's `{:error …}`, which reads far better than a Java message. (Typed
   error values that formulas could branch on are the natural follow-up; see
   TECHDEBT.)

   Naming: inside `xl/`, every function keeps Excel's own uppercase name
   (`xl/SUM`, `xl/XIRR`, `xl/NORM.DIST`) — this layer is a faithful mirror, so
   a translated formula reads like its source and maps back on export. SCI
   resolves the dotted names fine.

   RANGES. A SaltRim range is a FLAT row-major vector — its shape is lost at
   read time. So a flat argument is handed over as a single COLUMN (the common
   case: `(xl/SUM $A1:A9)`, `(xl/SORT $A1:A9)`), and anything needing a
   rectangle takes an explicit reshape:
   `(xl/VLOOKUP \"b\" (xl/as-rows 2 $A1:B9) 2 false)`. Nested vectors pass
   through as a real 2D area. Giving ranges a real shape is the proper fix and
   is deliberately out of scope here (TECHDEBT).

   DATES. rechentafel follows Excel: a date IS a number (the 1900 serial).
   SaltRim dates are ISO `yyyy-MM-dd` strings. Rather than guess, the bridge is
   explicit — `xl/date->serial` / `xl/serial->date` — and feeding a date string
   straight to `xl/YEAR` fails loudly with `#VALUE!` instead of silently lying.
   (The native stdlib takes ISO strings directly; only this layer speaks
   serials.)

   NOT exposed (see `excluded`): functions that need the evaluator (`IF`,
   `IFERROR`, `INDIRECT`, `LAMBDA` helpers — SaltRim has all of these natively
   in Clojure), Excel's VOLATILE functions, and upstream's `#N/A` stubs."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rechentafel.functions :as rf]
            [rechentafel.value :as rv]
            [rechentafel.functions.all]))

;; --- value translation ----------------------------------------------------

(def error-display
  "Excel error code -> the name a spreadsheet user knows it by."
  {:null "#NULL!"  :div0 "#DIV/0!" :value "#VALUE!" :ref  "#REF!"
   :name "#NAME?"  :num  "#NUM!"   :na    "#N/A"    :spill "#SPILL!"
   :calc "#CALC!"  :getting-data "#GETTING_DATA"})

(declare ->rv)

(defn- area
  "A vector of rows of ALREADY-translated values -> an rechentafel `:area`.
   Short rows are padded with blanks so the rectangle is well-formed."
  [rows]
  (let [w    (reduce max 1 (map count rows))
        rows (mapv #(into % (repeat (- w (count %)) rv/BLANK)) rows)]
    {:t :area :r0 0 :c0 0 :r1 (dec (count rows)) :c1 (dec w) :values rows}))

(defn ->rv
  "SaltRim value -> rechentafel tagged value. A blank cell (`nil`) is Excel's
   blank, not 0 — the functions apply Excel's own blank rules from there. A
   flat collection becomes a single COLUMN; a collection of collections becomes
   a 2D area (see the ns docstring on ranges)."
  [x]
  (cond
    (nil? x)     rv/BLANK
    (number? x)  (rv/number x)
    (string? x)  (rv/string x)
    (boolean? x) (rv/boolean-v x)
    (sequential? x)
    (area (if (some sequential? x)
            (mapv (fn [r] (if (sequential? r) (mapv ->rv r) [(->rv r)])) x)
            (mapv (fn [c] [(->rv c)]) x)))
    :else
    (throw (ex-info (str "an Excel function can't take " (pr-str x)) {:value x}))))

(defn- num-out
  "Excel numbers are all doubles; narrow the integral ones back to Long so a
   count reads as `3`, not `3.0` (same rule as the .xlsx importer)."
  [d]
  (let [d (double d)]
    (if (and (Double/isFinite d) (== d (Math/rint d))
             (<= -9.007199254740992E15 d 9.007199254740992E15))
      (long d)
      d)))

(defn <-rv
  "rechentafel tagged value -> SaltRim value. An error value THROWS, named the
   way a spreadsheet user knows it.

   An area comes back with its SHAPE: a genuine rectangle (more than one row AND
   more than one column) becomes a vector of ROW vectors, exactly what `#area`
   reads as, while a single row or column flattens — it has no shape to keep,
   and flattening it is what every range-shaped result has always done.

   The rectangle case is the one that matters: `TRANSPOSE`, `MINVERSE`, `MMULT`
   and the `LINEST` family all RETURN matrices, and flattening those made them
   unusable — you could not feed one into the next, and `[1 3 2 4]` cannot say
   which of three rectangles it is. Same argument as `#area` on the way in."
  [t]
  (case (:t t)
    :num   (num-out (:v t))
    :str   (:v t)
    :bool  (:v t)
    :blank nil
    :err   (throw (ex-info (get error-display (:v t) (str "#" (name (:v t))))
                           {:excel-error (:v t)}))
    :area  (let [rows (:values t)]
             (if (and (> (count rows) 1) (> (count (first rows)) 1))
               (mapv (fn [r] (mapv <-rv r)) rows)
               (mapv <-rv (apply concat rows))))
    (throw (ex-info (str "unexpected Excel result " (pr-str t)) {:value t}))))

;; --- calling --------------------------------------------------------------

(defn- arity-ok? [[mn mx] n]
  (and (or (nil? mn) (>= n mn)) (or (nil? mx) (<= n mx))))

(defn- arity-msg [fname [mn mx] n]
  (str fname " takes "
       (cond (and mn mx (= mn mx)) (str mn " argument" (when (not= 1 mn) "s"))
             (and mn mx)           (str mn "-" mx " arguments")
             mn                    (str mn " or more arguments")
             :else                 "fewer arguments")
       ", got " n))

(defn call
  "Invoke Excel function `fname` with plain SaltRim `args`. Throws with a
   readable message on a bad arity or an Excel error — upstream reports both as
   `#VALUE!` values, which tells the user nothing about which of the two it was."
  [fname args]
  (let [{:keys [arity]} (rf/lookup fname)]
    (when-not (arity-ok? arity (count args))
      (throw (ex-info (arity-msg fname arity (count args)) {:fn fname})))
    (<-rv (rf/call fname (mapv ->rv args)))))

;; --- bridges the flat/ISO SaltRim conventions need -------------------------

(defn as-rows
  "Reshape a flat row-major range into `w`-wide rows, for the functions that
   want a rectangle: `(VLOOKUP \"b\" (as-rows 2 $A1:B9) 2 false)`."
  [w coll]
  (mapv vec (partition-all (long w) coll)))

;; Excel's serial epoch is 1899-12-30 — EXCEPT that Excel believes 1900 was a
;; leap year, so serials 1..59 (1900-01-01 .. 1900-02-28) sit one day later and
;; serial 60 is a date that never existed. Same two-epoch rule rechentafel uses
;; internally, so the two agree on every serial they can both represent.
(def ^:private epoch-normal   "1899-12-30")
(def ^:private epoch-pre-leap "1899-12-31")

(defn serial->date
  "Excel serial number -> ISO `yyyy-MM-dd` string (SaltRim's date convention)."
  [serial]
  (let [n (long (Math/floor (double serial)))]
    (str (.plusDays (java.time.LocalDate/parse
                     (if (< n 60) epoch-pre-leap epoch-normal))
                    n))))

(defn date->serial
  "ISO `yyyy-MM-dd` string -> Excel serial number, so SaltRim dates can feed
   Excel's date functions: `(YEAR (date->serial $A1))`."
  [s]
  (let [d (java.time.LocalDate/parse (str s))
        n (.between java.time.temporal.ChronoUnit/DAYS
                    (java.time.LocalDate/parse epoch-normal) d)]
    (if (>= n 60)
      n
      (.between java.time.temporal.ChronoUnit/DAYS
                (java.time.LocalDate/parse epoch-pre-leap) d))))

;; --- which functions we expose --------------------------------------------

(defn- stub?
  "Upstream registers the functions Apache POI itself leaves unimplemented —
   bond math (PRICE/YIELD/COUP*/DURATION), the Excel 4 macro sheet vocabulary
   (CALL/EXEC/GET.CELL/…), CUBE* — as constant `#N/A` for POI parity. That is
   right for an Excel-compatible engine and wrong for us: a user calling PRICE
   deserves \"no such function\", not a silent #N/A. They all come from one
   `na-stub` factory per module, so the closure's class names them. The set is
   pinned by a test, so an upstream change surfaces as a failure rather than
   as stubs leaking back in."
  [fname]
  (str/includes? (.getName (class (:fn (rf/lookup fname)))) "na_stub"))

(defn- lazy?
  "Needs the evaluator context (unevaluated AST args): IF / IFERROR / IFNA /
   IFS / SWITCH / CHOOSE-likes, the IS* predicates, INDIRECT / OFFSET / CELL,
   and the LAMBDA helpers MAP / REDUCE / SCAN / BYROW / BYCOL / MAKEARRAY.
   SaltRim has every one of these natively — they are `if`, `try`, `cond`,
   `map`, `reduce` and dynamic refs — so there is nothing to bridge."
  [fname]
  (boolean (:lazy? (rf/lookup fname))))

(defn- volatile-fn?
  "Excel re-evaluates NOW / TODAY / RAND / RANDARRAY / RANDBETWEEN on every
   recalc. SaltRim has no recalc sweep — a cell recomputes when something it
   DEPENDS on changes, and these depend on nothing, so the value would freeze
   at whatever it was when the cell was last structurally rebuilt and then
   silently differ across branches, merges and as-of views. Excluded until
   there is a volatility policy. (SaltRim's own `today` has the same latent
   bug — tracked separately.)"
  [fname]
  (boolean (:volatile? (rf/lookup fname))))

(def excluded
  "Registered Excel names we deliberately do NOT expose, by reason."
  (let [names (rf/registered-names)]
    {:lazy     (set (filter lazy? names))
     :volatile (set (filter volatile-fn? names))
     :stub     (set (filter stub? names))}))

(def exposed-names
  "Every Excel function SaltRim offers: registered, strict, non-volatile, not
   an upstream #N/A stub."
  (let [out (apply set/union (vals excluded))]
    (vec (sort (remove out (rf/registered-names))))))

(defn arity
  "[min max] argument counts for an Excel function, from upstream's registry —
   the one piece of per-function metadata rechentafel carries. nil if unknown."
  [xl-name]
  (:arity (rf/lookup xl-name)))

(def catalog
  "`exposed-names` grouped for the help modal, in rechentafel's own module
   order. Written out rather than derived: the grouping is not recoverable at
   runtime (registration records no category), and a literal listing means a
   version bump that adds or drops a function fails a test instead of quietly
   changing the reference. Keep in sync with `exposed-names` — `excel-test`
   asserts they cover exactly the same set."
  [["math"
    ["ABS" "ACOS" "ACOSH" "ACOT" "ACOTH" "AGGREGATE" "ARABIC" "ASIN"
     "ASINH" "ATAN" "ATAN2" "ATANH" "AVEDEV" "CEILING" "CEILING.MATH"
     "CEILING.PRECISE" "COMBIN" "COMBINA" "COS" "COSH" "COT" "COTH" "CSC"
     "CSCH" "DEGREES" "EVEN" "EXP" "FACT" "FACTDOUBLE" "FLOOR" "FLOOR.MATH"
     "FLOOR.PRECISE" "FREQUENCY" "GAUSS" "GCD" "INT" "ISO.CEILING" "LCM"
     "LN" "LOG" "LOG10" "MDETERM" "MINVERSE" "MMULT" "MOD" "MROUND"
     "MULTINOMIAL" "MUNIT" "ODD" "PERMUT" "PERMUTATIONA" "PHI" "PI" "POWER"
     "PRODUCT" "QUOTIENT" "RADIANS" "ROMAN" "ROUND" "ROUNDDOWN" "ROUNDUP"
     "SEC" "SECH" "SERIESSUM" "SIGN" "SIN" "SINH" "SQRT" "SQRTPI"
     "SUBTOTAL" "SUM" "SUMPRODUCT" "SUMSQ" "SUMX2MY2" "SUMX2PY2" "SUMXMY2"
     "TAN" "TANH" "TRANSPOSE" "TRUNC"]]
   ["stats"
    ["AVERAGE" "AVERAGEA" "AVERAGEIF" "AVERAGEIFS" "BETA.DIST" "BETA.INV"
     "BETADIST" "BETAINV" "BINOM.DIST" "BINOM.INV" "BINOMDIST" "CHIDIST"
     "CHIINV" "CHISQ.DIST" "CHISQ.DIST.RT" "CHISQ.INV" "CHISQ.INV.RT"
     "CHISQ.TEST" "CHITEST" "CONFIDENCE" "CONFIDENCE.NORM" "CORREL" "COUNT"
     "COUNTA" "COUNTBLANK" "COUNTIF" "COUNTIFS" "COVAR" "COVARIANCE.P"
     "COVARIANCE.S" "CRITBINOM" "DEVSQ" "EXPON.DIST" "EXPONDIST" "F.DIST"
     "F.DIST.RT" "F.INV" "F.INV.RT" "F.TEST" "FDIST" "FINV" "FISHER"
     "FISHERINV" "FORECAST" "FORECAST.LINEAR" "FTEST" "GAMMA" "GAMMA.DIST"
     "GAMMA.INV" "GAMMADIST" "GAMMAINV" "GAMMALN" "GAMMALN.PRECISE"
     "GEOMEAN" "GROWTH" "HARMEAN" "HYPGEOM.DIST" "HYPGEOMDIST" "INTERCEPT"
     "KURT" "LARGE" "LINEST" "LOGEST" "LOGINV" "LOGNORM.DIST" "LOGNORM.INV"
     "LOGNORMDIST" "MAX" "MAXA" "MAXIFS" "MEDIAN" "MIN" "MINA" "MINIFS"
     "MODE" "MODE.SNGL" "NEGBINOM.DIST" "NEGBINOMDIST" "NORM.DIST"
     "NORM.INV" "NORM.S.DIST" "NORM.S.INV" "NORMDIST" "NORMINV" "NORMSDIST"
     "NORMSINV" "PEARSON" "PERCENTILE" "PERCENTILE.EXC" "PERCENTILE.INC"
     "PERCENTRANK" "PERCENTRANK.EXC" "PERCENTRANK.INC" "POISSON"
     "POISSON.DIST" "PROB" "QUARTILE" "QUARTILE.EXC" "QUARTILE.INC" "RANK"
     "RANK.AVG" "RANK.EQ" "RSQ" "SKEW" "SKEW.P" "SLOPE" "SMALL"
     "STANDARDIZE" "STDEV" "STDEV.P" "STDEV.S" "STDEVA" "STDEVP" "STDEVPA"
     "STEYX" "SUMIF" "SUMIFS" "T.DIST" "T.DIST.2T" "T.DIST.RT" "T.INV"
     "T.INV.2T" "T.TEST" "TDIST" "TINV" "TREND" "TRIMMEAN" "TTEST" "VAR"
     "VAR.P" "VAR.S" "VARA" "VARP" "VARPA" "WEIBULL" "WEIBULL.DIST"
     "Z.TEST" "ZTEST"]]
   ["text"
    ["ASC" "CHAR" "CLEAN" "CODE" "CONCAT" "CONCATENATE" "DBCS" "DOLLAR"
     "EXACT" "FIND" "FINDB" "FIXED" "JIS" "LEFT" "LEFTB" "LEN" "LENB"
     "LOWER" "MID" "MIDB" "N" "NUMBERVALUE" "PROPER" "REPLACE" "REPLACEB"
     "REPT" "RIGHT" "RIGHTB" "SEARCH" "SEARCHB" "SUBSTITUTE" "T" "TEXT"
     "TEXTAFTER" "TEXTBEFORE" "TEXTJOIN" "TEXTSPLIT" "TRIM" "UNICHAR"
     "UNICODE" "UPPER" "VALUE"]]
   ["datetime"
    ["DATE" "DATEDIF" "DATEVALUE" "DAY" "DAYS" "DAYS360" "EDATE" "EOMONTH"
     "HOUR" "ISOWEEKNUM" "MINUTE" "MONTH" "NETWORKDAYS" "SECOND" "TIME"
     "TIMEVALUE" "WEEKDAY" "WEEKNUM" "WORKDAY" "WORKDAY.INTL" "YEAR"
     "YEARFRAC"]]
   ["financial"
    ["ACCRINTM" "CUMIPMT" "CUMPRINC" "DB" "DDB" "DISC" "EFFECT" "FV"
     "INTRATE" "IPMT" "IRR" "ISPMT" "MIRR" "NOMINAL" "NPER" "NPV" "PMT"
     "PPMT" "PV" "RATE" "RECEIVED" "SLN" "SYD" "TBILLEQ" "TBILLPRICE"
     "TBILLYIELD" "VDB" "XIRR" "XNPV"]]
   ["engineering"
    ["BESSELI" "BESSELJ" "BESSELK" "BESSELY" "BIN2DEC" "BIN2HEX" "BIN2OCT"
     "BITAND" "BITLSHIFT" "BITOR" "BITRSHIFT" "BITXOR" "COMPLEX" "CONVERT"
     "DEC2BIN" "DEC2HEX" "DEC2OCT" "DELTA" "ERF" "ERF.PRECISE" "ERFC"
     "ERFC.PRECISE" "GESTEP" "HEX2BIN" "HEX2DEC" "HEX2OCT" "IMABS"
     "IMAGINARY" "IMARGUMENT" "IMCONJUGATE" "IMCOS" "IMDIV" "IMEXP" "IMLN"
     "IMLOG10" "IMLOG2" "IMPOWER" "IMPRODUCT" "IMREAL" "IMSIN" "IMSQRT"
     "IMSUB" "IMSUM" "OCT2BIN" "OCT2DEC" "OCT2HEX"]]
   ["lookup"
    ["GETPIVOTDATA" "HLOOKUP" "HYPERLINK" "INDEX" "LOOKUP" "MATCH"
     "VLOOKUP" "XLOOKUP" "XMATCH"]]
   ["logical" ["AND" "CHOOSE" "FALSE" "NOT" "OR" "TRUE" "XOR"]]
   ["database"
    ["DAVERAGE" "DCOUNT" "DCOUNTA" "DGET" "DMAX" "DMIN" "DPRODUCT" "DSTDEV"
     "DSTDEVP" "DSUM" "DVAR" "DVARP"]]
   ["array"
    ["CHOOSECOLS" "CHOOSEROWS" "DROP" "EXPAND" "FILTER" "HSTACK" "SEQUENCE"
     "SORT" "SORTBY" "TAKE" "TOCOL" "TOROW" "UNIQUE" "VSTACK" "WRAPCOLS"
     "WRAPROWS"]]
   ["info" ["AREAS" "COLUMNS" "ISOMITTED" "NA" "ROWS" "SINGLE"]]
   ["misc" ["ADDRESS" "DOLLARDE" "DOLLARFR" "FVSCHEDULE"]]])

(def sci-ns
  "The `xl` namespace handed to every sheet's SCI context: each exposed Excel
   function under its own uppercase name, plus the bridges the flat-range /
   serial-date conventions need. Reached as `xl/PMT`, `xl/as-rows`, … — never
   bare, so the language you write in stays Clojure."
  (into {'as-rows      as-rows
         'date->serial date->serial
         'serial->date serial->date}
        (for [n exposed-names]
          [(symbol n) (fn [& args] (call n args))])))
