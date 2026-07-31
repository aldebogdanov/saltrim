(ns uno.michelada.saltrim.stdlib
  "The functions every formula can call bare — SaltRim's standard library.

   Two sources, one map. Most of it is BORROWED: Excel's library (via the
   `excel` ns) has decades of careful numerics we are not going to rewrite, so
   the entries below delegate to it. But borrowing the implementation is not
   the same as importing Excel — each borrowed function is TRANSLATED, and the
   translation is the point of this namespace:

     - **Clojure names.** Kebab-cased terms of art, as the domain says them:
       `pmt`, `irr`, `npv`, `norm-dist`, `eomonth`, `percentile`, `stdev-p`.
       Excel's dots become dashes (`STDEV.P` -> `stdev-p`, `T.DIST.2T` ->
       `t-dist-2t`); the handful whose Excel name would collide with
       clojure.core get a prefix (`FIND` -> `str-find`).
     - **ISO dates, not 1900 serial numbers.** `(eomonth \"2026-07-29\" 1)` takes
       and returns `yyyy-MM-dd` strings like the rest of SaltRim; the serial
       conversion happens here, at the boundary, in both directions.
     - **Curated, not dumped.** Roughly 230 of Excel's ~410 are here. Left out:
       what Clojure already does better (`SORT`, `UNIQUE`, `FILTER`, `IF`,
       `CHOOSE`-as-`nth`), what needs 2D ranges we do not have yet (`MMULT`,
       `TRANSPOSE`, `LINEST`), the text-coercion variants (`AVERAGEA` and
       friends), the database `D*` family (Clojure has `filter`), and the
       legacy duplicates Excel keeps for compatibility (`NORMDIST` alongside
       `NORM.DIST`). Anything omitted is still reachable as `xl/NAME`.
     - **Nothing changes meaning.** Names already in use keep their semantics:
       `round` stays 1-arg `Math/round` (Excel's 2-arg half-away-from-zero is
       `xround`), `ceil`/`floor` stay 1-arg (Excel's round-to-a-multiple is
       `ceiling-math`/`floor-math`/`mround`), and `min`/`max` stay
       clojure.core's (the blank-skipping pair is `xmin`/`xmax`). Formulas
       already saved in people's sheets must not quietly start computing
       something else.

   Errors behave like the rest of the sandbox: a domain failure THROWS, named
   the way a spreadsheet user knows it (`#NUM!`, `#DIV/0!`), and the sheet layer
   renders that as the cell's `{:error …}`."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.errors :as errors]
            [uno.michelada.saltrim.excel :as excel]))

;; --- hand-written ----------------------------------------------------------
;; The functions whose semantics we chose rather than inherited: the aggregates
;; that skip blanks the Clojure way, the ISO date helpers, the excel-compat
;; shims the .xlsx importer targets, and the sandbox's I/O refusals.

(defn- ld ^java.time.LocalDate [s] (java.time.LocalDate/parse (str s)))

;; SCI's core exposes the print/read family, but its *out*/*in* are unbound, so
;; calling them crashes with an opaque cast (SciUnbound -> Writer). They're also
;; meaningless here: a formula is PURE and recomputes reactively (no console, and
;; it would re-fire on every dependency change). Override them to fail clearly.
(defn- no-io [& _]
  (throw (ex-info "I/O isn't available in formulas — the sandbox is pure (no console)" {})))

(defn- nums
  "Keep only the numbers in a cell collection, so aggregates IGNORE blank cells
   (which resolve to nil) — matching a spreadsheet's SUM/AVERAGE-skip-blanks."
  [c] (filter number? c))

(defn- mean* [c] (let [c (nums c)] (if (seq c) (/ (double (reduce + 0 c)) (count c)) 0)))
(defn- var* [c]
  (let [c (nums c) n (count c)]
    (if (zero? n) 0
        (let [m (/ (double (reduce + 0 c)) n)]
          (/ (reduce + (map #(let [d (- % m)] (* d d)) c)) n)))))

;; --- error branching -------------------------------------------------------
;; Excel's IFERROR / IFNA / ERROR.TYPE, as macros so the guarded expression is
;; not evaluated before it can be guarded. They expand to a call on the host fn
;; OBJECT below rather than a name, so the sandbox gains no helper vars — and to
;; a host `try`, because SCI's own `catch` cannot resolve a class name.
;;
;; WHAT THEY CATCH — and this is the sharp edge: a failure raised while
;; evaluating the expression ITSELF. `(if-error (/ $A1 $B1) 0)` catches the
;; division. `(if-error $A1 0)` where A1 is already `#NUM!` does NOT: cell
;; references are hoisted out of the body and awaited before it runs (the CPS
;; breakpoints have to be literal — see SPEC), so a referenced cell's error
;; arrives before any guard here can see it. Excel propagates errors as VALUES
;; through every operator, which is what would be needed to close that gap; see
;; TECHDEBT.

(defn- catch-error [thunk fallback]
  (try (thunk) (catch Throwable _ (fallback))))

(defn- catch-na [thunk fallback]
  (try (thunk)
       (catch Throwable e
         (if (= :na (errors/classify e)) (fallback) (throw e)))))

(defn- caught-code [thunk]
  (try (thunk) nil (catch Throwable e (errors/classify e))))

(defn- unthunk
  "The .xlsx importer emits `(if-error (fn [] EXPR) fallback)` — it had to, back
   when `if-error` was an ordinary function. Those formulas are saved in real
   sheets, so a zero-arg `fn` wrapper is unwrapped rather than double-wrapped."
  [expr]
  (if (and (seq? expr) (= 'fn (first expr))
           (vector? (second expr)) (empty? (second expr)))
    (cons 'do (drop 2 expr))
    expr))

(defn- if-error-macro [_&form _&env expr fallback]
  (list catch-error (list 'fn [] (unthunk expr)) (list 'fn [] fallback)))

(defn- if-na-macro [_&form _&env expr fallback]
  (list catch-na (list 'fn [] (unthunk expr)) (list 'fn [] fallback)))

(defn- error-type-macro [_&form _&env expr]
  (list caught-code (list 'fn [] (unthunk expr))))

(defn- error?-macro [_&form _&env expr]
  (list 'some? (list caught-code (list 'fn [] (unthunk expr)))))

(def hand-written
  "The functions SaltRim implements itself — the ones whose semantics we chose
   rather than inherited. Everything else comes from `derived` below."
  {;; math
   'abs abs
   'ceil    (fn [x] (long (Math/ceil (double x))))
   'floor   (fn [x] (long (Math/floor (double x))))
   'round   (fn [x] (Math/round (double x)))
   'sqrt    (fn [x] (Math/sqrt (double x)))
   'pow     (fn [b e] (Math/pow (double b) (double e)))
   'exp     (fn [x] (Math/exp (double x)))
   'ln      (fn [x] (Math/log (double x)))
   'log10   (fn [x] (Math/log10 (double x)))
   'sign    (fn [x] (long (Math/signum (double x))))
   'sum     (fn [c] (reduce + 0 (nums c)))
   'product (fn [c] (reduce * 1 (nums c)))
   ;; stats — all skip blank (nil) cells, like a spreadsheet
   'mean   mean*
   'avg    mean*
   'median (fn [c] (let [s (vec (sort (nums c))) n (count s)]
                     (cond (zero? n) 0
                           (odd? n)  (nth s (quot n 2))
                           :else (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))
   'variance var*
   'stdev    (fn [c] (Math/sqrt (double (var* c))))
   ;; text
   'upper        str/upper-case
   'lower        str/lower-case
   'trim         str/trim
   'join         (fn ([c] (str/join c)) ([sep c] (str/join sep c)))
   'split        (fn [s sep] (vec (str/split (str s) (re-pattern (java.util.regex.Pattern/quote (str sep))))))
   'str-replace  (fn [s a b] (str/replace (str s) (str a) (str b)))
   'starts-with? (fn [s p] (str/starts-with? (str s) (str p)))
   'ends-with?   (fn [s p] (str/ends-with? (str s) (str p)))
   'includes?    (fn [s p] (str/includes? (str s) (str p)))
   'blank?       (fn [s] (str/blank? (str s)))
   ;; date (ISO yyyy-MM-dd strings)
   'today        (fn [] (str (java.time.LocalDate/now)))
   'year         (fn [s] (.getYear (ld s)))
   'month        (fn [s] (.getMonthValue (ld s)))
   'day          (fn [s] (.getDayOfMonth (ld s)))
   'days-between (fn [a b] (.between java.time.temporal.ChronoUnit/DAYS (ld a) (ld b)))
   ;; excel-compat — Excel-semantics helpers the .xlsx importer targets, and
   ;; useful on their own. `xmin`/`xmax` skip blank (nil) cells like the other
   ;; aggregates (core min/max would throw); `excel-truthy` is Excel's 0=false;
   ;; `xround` rounds half AWAY FROM ZERO like Excel's ROUND (Math/round would
   ;; give -2.5 -> -2, Excel says -3); `xvlookup` is an exact-match VLOOKUP
   ;; over one of our row-major flat ranges (`w` = the table width in columns).
   'if-error     (with-meta if-error-macro {:sci/macro true})
   'if-na        (with-meta if-na-macro {:sci/macro true})
   'error-type   (with-meta error-type-macro {:sci/macro true})
   'error?       (with-meta error?-macro {:sci/macro true})
   'excel-truthy (fn [x] (cond (nil? x)     false
                               (number? x)  (not (zero? x))
                               (boolean? x) x
                               :else        true))
   'xmin  (fn [c] (let [n (nums c)] (when (seq n) (apply min n))))
   'xmax  (fn [c] (let [n (nums c)] (when (seq n) (apply max n))))
   'xround (fn [x n]
             (let [r (.setScale (java.math.BigDecimal. (str (double x))) (int n)
                                java.math.RoundingMode/HALF_UP)]
               (if (pos? (int n)) (double r) (long (.longValueExact (.setScale r 0))))))
   'xdate (fn [y m d] (format "%04d-%02d-%02d" (long y) (long m) (long d)))
   'xvlookup (fn [k table w col]
               (some (fn [row] (when (= k (first row)) (nth row (dec (long col)))))
                     (partition (long w) table)))
   ;; (`#(...)` support is added by `formula`, which owns the desugaring.)
   ;; what a reference is rewritten to when the row/column it pointed at is
   ;; deleted (see `delete-shift`) — always throws, naming what was lost
   'deleted-ref (fn [what]
                  (throw (ex-info (str "#REF! — " what " was deleted") {:ref what})))
   ;; I/O (see no-io): clear "not available" instead of an opaque cast crash
   'println no-io 'print no-io 'prn no-io 'pr no-io 'printf no-io
   'newline no-io 'flush no-io 'read no-io 'read-line no-io})

;; --- borrowed from Excel ---------------------------------------------------

(def ^:private renamed
  "Excel names that can't be mechanically kebab-cased: either clojure.core
   already owns the name, or Excel's own spelling is an artifact (`MODE.SNGL`
   is just `MODE` once the legacy array form is gone)."
  {"FIND"      'str-find
   "SEARCH"    'str-search
   "MODE.SNGL" 'mode})

(defn- kebab
  "Excel name -> the symbol we expose it as. `NORM.S.DIST` -> `norm-s-dist`."
  [xl-name]
  (or (renamed xl-name)
      (symbol (str/lower-case (str/replace xl-name "." "-")))))

(def ^:private date-shape
  "Which arguments of a borrowed function are DATES, and whether its result is
   one. Everything else about a borrowed function passes straight through;
   dates are the one place SaltRim and Excel disagree about representation, so
   this table is the translation. Positions are 0-based and may be optional
   (`WORKDAY`'s holidays) or collections (`XIRR`'s date column) — `to-serial`
   handles both."
  {"DATE"          {:result :date}
   "EDATE"         {:dates #{0} :result :date}
   "EOMONTH"       {:dates #{0} :result :date}
   "WORKDAY"       {:dates #{0 2} :result :date}
   "WORKDAY.INTL"  {:dates #{0 3} :result :date}
   "WEEKDAY"       {:dates #{0}}
   "WEEKNUM"       {:dates #{0}}
   "ISOWEEKNUM"    {:dates #{0}}
   "DAYS360"       {:dates #{0 1}}
   "YEARFRAC"      {:dates #{0 1}}
   "DATEDIF"       {:dates #{0 1}}
   "NETWORKDAYS"   {:dates #{0 1 2}}
   "XNPV"          {:dates #{2}}
   "XIRR"          {:dates #{1}}
   "DISC"          {:dates #{0 1}}
   "INTRATE"       {:dates #{0 1}}
   "RECEIVED"      {:dates #{0 1}}
   "TBILLEQ"       {:dates #{0 1}}
   "TBILLPRICE"    {:dates #{0 1}}
   "TBILLYIELD"    {:dates #{0 1}}
   "ACCRINTM"      {:dates #{0 1}}})

(defn- to-serial
  "ISO date string -> Excel serial, recursing into a collection (a date column
   or a holiday list). Numbers pass through, so a caller who already has a
   serial — or an `xl/` user mixing layers — is not second-guessed."
  [v]
  (cond
    (string? v)     (excel/date->serial v)
    (sequential? v) (mapv to-serial v)
    :else           v))

(defn- borrow
  "Wrap one Excel function as a SaltRim stdlib fn: convert any date arguments
   on the way in, and a date result on the way out."
  [xl-name]
  (if-let [{:keys [dates result]} (date-shape xl-name)]
    (fn [& args]
      (let [args (reduce (fn [a i] (cond-> a (< i (count a)) (update i to-serial)))
                         (vec args) dates)
            out  (excel/call xl-name args)]
        (if (= :date result) (excel/serial->date out) out)))
    (fn [& args] (excel/call xl-name args))))

(def catalog
  "The borrowed set, grouped for the help panel. Excel names; `kebab` gives the
   name a formula actually uses. Curated by hand — see the ns docstring for
   what was left out and why."
  [["math"
    ["SIN" "COS" "TAN" "ASIN" "ACOS" "ATAN" "ATAN2" "SINH" "COSH" "TANH"
     "ASINH" "ACOSH" "ATANH" "SEC" "SECH" "CSC" "CSCH" "COT" "COTH"
     "DEGREES" "RADIANS" "GAUSS" "PHI" "LOG" "MROUND" "ROUNDUP" "ROUNDDOWN"
     "TRUNC" "CEILING.MATH" "FLOOR.MATH" "GCD" "LCM" "FACT" "FACTDOUBLE"
     "COMBIN" "COMBINA" "PERMUT" "PERMUTATIONA" "MULTINOMIAL" "SQRTPI"
     "SERIESSUM" "SUMSQ" "SUMPRODUCT" "SUMX2MY2" "SUMX2PY2" "SUMXMY2"
     "ARABIC" "ROMAN"]]
   ["stats"
    ["AVERAGEIF" "AVERAGEIFS" "SUMIF" "SUMIFS" "COUNTIF" "COUNTIFS" "COUNTA"
     "COUNTBLANK" "MAXIFS" "MINIFS" "LARGE" "SMALL" "RANK" "RANK.AVG"
     "PERCENTILE" "PERCENTILE.EXC" "PERCENTRANK" "PERCENTRANK.EXC"
     "QUARTILE" "QUARTILE.EXC" "MODE.SNGL" "STDEV.P" "STDEV.S" "VAR.P"
     "VAR.S" "DEVSQ" "AVEDEV" "GEOMEAN" "HARMEAN" "KURT" "SKEW" "SKEW.P"
     "TRIMMEAN" "STANDARDIZE" "FREQUENCY" "PROB" "CORREL" "COVARIANCE.P"
     "COVARIANCE.S" "PEARSON" "RSQ" "SLOPE" "INTERCEPT" "STEYX" "FORECAST"
     "CONFIDENCE.NORM" "NORM.DIST" "NORM.INV" "NORM.S.DIST" "NORM.S.INV"
     "LOGNORM.DIST" "LOGNORM.INV" "T.DIST" "T.DIST.2T" "T.DIST.RT" "T.INV"
     "T.INV.2T" "T.TEST" "CHISQ.DIST" "CHISQ.DIST.RT" "CHISQ.INV"
     "CHISQ.INV.RT" "CHISQ.TEST" "F.DIST" "F.DIST.RT" "F.INV" "F.INV.RT"
     "F.TEST" "BINOM.DIST" "BINOM.INV" "NEGBINOM.DIST" "HYPGEOM.DIST"
     "POISSON.DIST" "EXPON.DIST" "WEIBULL.DIST" "GAMMA" "GAMMA.DIST"
     "GAMMA.INV" "GAMMALN" "BETA.DIST" "BETA.INV" "FISHER" "FISHERINV"
     "Z.TEST"]]
   ["financial"
    ["PMT" "PV" "FV" "NPER" "RATE" "IPMT" "PPMT" "CUMIPMT" "CUMPRINC" "ISPMT"
     "NPV" "IRR" "MIRR" "XNPV" "XIRR" "SLN" "SYD" "DB" "DDB" "VDB" "EFFECT"
     "NOMINAL" "DISC" "INTRATE" "RECEIVED" "TBILLEQ" "TBILLPRICE"
     "TBILLYIELD" "ACCRINTM" "FVSCHEDULE" "DOLLARDE" "DOLLARFR"]]
   ["text"
    ["LEN" "LEFT" "RIGHT" "MID" "REPT" "PROPER" "EXACT" "SUBSTITUTE"
     "TEXTJOIN" "TEXTBEFORE" "TEXTAFTER" "TEXTSPLIT" "NUMBERVALUE" "VALUE"
     "TEXT" "CLEAN" "FIXED" "DOLLAR" "CODE" "UNICHAR" "UNICODE" "FIND"
     "SEARCH"]]
   ["date"
    ["DATE" "EDATE" "EOMONTH" "WEEKDAY" "WEEKNUM" "ISOWEEKNUM" "DAYS360"
     "YEARFRAC" "DATEDIF" "NETWORKDAYS" "WORKDAY" "WORKDAY.INTL"]]
   ["lookup"
    ["VLOOKUP" "HLOOKUP" "XLOOKUP" "MATCH" "XMATCH" "INDEX" "LOOKUP" "CHOOSE"]]
   ["engineering"
    ["CONVERT" "DELTA" "GESTEP" "ERF" "ERFC" "BESSELI" "BESSELJ" "BESSELK"
     "BESSELY" "BITAND" "BITOR" "BITXOR" "BITLSHIFT" "BITRSHIFT" "BIN2DEC"
     "BIN2HEX" "BIN2OCT" "DEC2BIN" "DEC2HEX" "DEC2OCT" "HEX2BIN" "HEX2DEC"
     "HEX2OCT" "OCT2BIN" "OCT2DEC" "OCT2HEX"]]
   ["logical" ["XOR"]]])

(def borrowed-names
  "Every Excel name this namespace re-exposes, flat."
  (vec (mapcat second catalog)))

(def catalog-syms
  "`catalog` as the symbols a formula actually writes — what the help panel
   lists, so the reference can never drift from what is installed."
  (mapv (fn [[cat names]] [cat (mapv kebab names)]) catalog))

(def borrowed-syms
  "The same set, flat."
  (vec (mapcat second catalog-syms)))

(def ^:private derived
  (into {} (for [n borrowed-names] [(kebab n) (borrow n)])))

(def stdlib
  "What every formula sandbox gets merged into clojure.core. Hand-written last:
   if a borrowed name ever collided with one of ours, ours is the one already
   in people's saved formulas and must win."
  (merge derived
         {;; Excel wants a rectangle where a SaltRim range is a flat row-major
          ;; vector, so reshaping is part of the language until ranges carry a
          ;; shape of their own: (vlookup $A1 (as-rows 2 $B1:C9) 2 false).
          'as-rows excel/as-rows
          'pi      Math/PI}
         hand-written))
