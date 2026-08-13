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
     - **Curated, not dumped.** Roughly 235 of Excel's ~410 are here. Left out:
       what Clojure already does better (`SORT`, `UNIQUE`, `FILTER`, `IF`,
       `CHOOSE`-as-`nth`), the text-coercion variants (`AVERAGEA` and friends),
       the database `D*` family (Clojure has `filter`), and the legacy
       duplicates Excel keeps for compatibility (`NORMDIST` alongside
       `NORM.DIST`). Anything omitted is still reachable as `xl/NAME`.
     - **Matrices are in now.** `MMULT`/`TRANSPOSE`/`LINEST` used to be excluded
       for want of 2D ranges; `#area` supplied them, so `transpose`, `matmul`,
       `det`, `inverse`, `linest` and `trend` are ordinary bare names. Nobody
       should have to reach for `xl/MMULT` to multiply two matrices.
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
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [uno.michelada.saltrim.errors :as errors]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.xlsource :as xlsource]))

;; --- hand-written ----------------------------------------------------------
;; The functions whose semantics we chose rather than inherited: the aggregates
;; that skip blanks the Clojure way, the ISO date helpers, the excel-compat
;; shims the .xlsx importer targets, and the sandbox's I/O refusals.

(def ^:private helper-src
  "Source of the private helpers a stdlib function may lean on, so `source-for`
   can hand one over with everything it needs to run outside SaltRim."
  (atom {}))

(defmacro ^:private defsrc
  "`defn-`, remembering the source. One form defines the helper AND records what
   to paste, so the two cannot disagree."
  [name args & body]
  `(do (defn ~(vary-meta name assoc :private true) ~args ~@body)
       (swap! helper-src assoc '~name '~(concat (list 'defn name args) body))
       (var ~name)))

(defsrc ld [s] (java.time.LocalDate/parse (str s)))

;; SCI's core exposes the print/read family, but its *out*/*in* are unbound, so
;; calling them crashes with an opaque cast (SciUnbound -> Writer). They're also
;; meaningless here: a formula is PURE and recomputes reactively (no console, and
;; it would re-fire on every dependency change). Override them to fail clearly.
(defn- no-io [& _]
  (throw (ex-info "I/O isn't available in formulas — the sandbox is pure (no console)" {})))

(defsrc nums
  "Keep only the numbers in a cell collection, so aggregates IGNORE blank cells
   (which resolve to nil) — matching a spreadsheet's SUM/AVERAGE-skip-blanks.

   FLATTENS first, so a 2D `#area A1:B2` aggregates over its cells rather than
   its rows. Without that every aggregate here answered 0 (or nil) for an area,
   silently: `filter number?` over `[[1 2] [3 4]]` keeps nothing. Excel sums a
   rectangle's cells too, and a `sum` that depends on which of two spellings of
   the same block you used is a wrong answer, not a design.

   This is the line between the two halves of the stdlib: OUR aggregates take a
   collection of cells and do not care about its shape, while `clojure.core`
   stays Clojure — `(reduce + #area A1:B2)` still adds ROWS and still throws,
   because that is what reducing + over vectors means."
  [c] (filter number? (flatten c)))

(defsrc mean* [c] (let [c (nums c)] (if (seq c) (/ (double (reduce + 0 c)) (count c)) 0)))

(defsrc var* [c]
  (let [c (nums c) n (count c)]
    (if (zero? n) 0
        (let [m (/ (double (reduce + 0 c)) n)]
          (/ (reduce + (map (fn [x] (let [d (- x m)] (* d d))) c)) n)))))

;; --- matrices --------------------------------------------------------------
;; `transpose` and `matmul` are OURS rather than borrowed, for one reason: they
;; are four lines of Clojure, and going through `excel/call` would convert every
;; element to a tagged value and back to answer a question Clojure answers
;; directly. `det` and `inverse` ARE borrowed — pivoting and conditioning are
;; exactly the "decades of careful numerics" this namespace exists to inherit.

(defsrc transpose* [m]
  (when-not (and (sequential? m) (seq m) (every? sequential? m))
    (throw (ex-info "transpose needs a rectangle — write #area A1:B2, not $A1:B2" {})))
  (apply mapv vector m))

(defsrc matmul* [a b]
  (when-not (and (sequential? a) (seq a) (every? sequential? a)
                 (sequential? b) (seq b) (every? sequential? b))
    (throw (ex-info "matmul needs two rectangles — write #area A1:B2, not $A1:B2" {})))
  (when-not (= (count (first a)) (count b))
    (throw (ex-info (str "matmul shape mismatch: " (count a) "x" (count (first a))
                         " by " (count b) "x" (count (first b)))
                    {})))
  (let [bt (apply mapv vector b)]
    (mapv (fn [row] (mapv (fn [col] (reduce + 0 (map * row col))) bt)) a)))

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

(defmacro ^:private def-hand-written
  "Define `hand-written` AND `hand-written-src` from ONE map literal: the
   installed functions and the source the ƒ panel hands out are the same text,
   so no amount of editing can make the copy button lie.

   The keys in that literal are written `'sum`, which quotes to `(quote sum)` —
   unwrapped here so the source map is keyed by the plain symbol."
  [docstring m]
  (let [unquote-key (fn [k] (if (and (seq? k) (= 'quote (first k))) (second k) k))
        src         (into {} (for [[k v] m] [(unquote-key k) v]))]
    `(do (def ~'hand-written ~docstring ~m)
         (def ~'hand-written-src '~src))))

(def-hand-written
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
   ;; matrices — take and return #area rectangles
   'transpose transpose*
   'matmul    matmul*
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
   ;; `=` on two computed decimals is a trap, and it is not one the engine can
   ;; fix: 0.1 + 0.2 is not 0.3 in binary floating point, and a borrowed
   ;; financial function is entitled by `java.lang.Math`'s own contract to
   ;; differ in the last ulp between one machine and another. So a sheet that
   ;; branches `(if (= $A1 $B1) …)` over two computed columns eventually says
   ;; "different" about numbers that are the same to fifteen digits.
   ;;
   ;; Excel has no answer to this — you write ABS(a-b)<0.001 and hope you
   ;; picked the right epsilon. `≈` is the answer: two arguments asks "the
   ;; same number, allowing for floating-point noise" (relative, so it holds
   ;; at 1e-6 and at 1e9 alike); a third makes the tolerance yours and
   ;; ABSOLUTE, which is what "within a cent" means.
   ;;
   ;; The default is 1e-12 RELATIVE, which is four orders of magnitude looser
   ;; than the ~1e-16 that float noise actually costs and still tight enough
   ;; that two distinct integers stay distinct until about 1e12. At 1e-9 it was
   ;; not: `(≈ 1000000000 1000000001)` came out true, which is a lie about two
   ;; numbers a user can see are different. Anyone who wants exactly-equal back
   ;; can ask for it — `(≈ a b 0)` is `=`.
   ;;
   ;; Not variadic, deliberately: `(≈ a b c)` would be indistinguishable from
   ;; a tolerance, and guessing which one a user meant is worse than not
   ;; offering it. Non-numbers fall through to plain `=`, so comparing text or
   ;; two blanks still does the obvious thing.
   '≈ (fn
        ([a b]
         (if (and (number? a) (number? b))
           (let [x (double a) y (double b)]
             (or (= x y)
                 (and (Double/isFinite x) (Double/isFinite y)
                      (<= (Math/abs (- x y))
                          (* 1e-12 (Math/max 1.0 (Math/max (Math/abs x) (Math/abs y))))))))
           (= a b)))
        ([a b tol]
         (if (and (number? a) (number? b) (number? tol))
           (<= (Math/abs (- (double a) (double b))) (Math/abs (double tol)))
           (= a b))))
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
   "MODE.SNGL" 'mode
   ;; Excel's M-for-matrix prefix is its own convention, not a term of art
   "MDETERM"   'det
   "MINVERSE"  'inverse})

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
   ["logical" ["XOR"]]
   ["matrix" ["MDETERM" "MINVERSE" "LINEST" "TREND"]]])

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

(def excel-name
  "SaltRim symbol -> the Excel name it was borrowed from, for the .xlsx boundary
   in BOTH directions: the importer translates an Excel call it has no hand-
   written mapping for, and the exporter turns the result back into a formula
   Excel will recompute. `kebab` already owns this correspondence; this is only
   it, inverted and made public, so neither side can invent a second table.

   Date-shaped functions are LEFT OUT on purpose. `stdlib` takes and returns ISO
   date strings while Excel wants a 1900 serial, and `borrow` does that
   conversion — so the two names denote the same function but not the same
   signature, and swapping one for the other across the boundary would silently
   change what the formula means. `xl/EOMONTH` is still reachable and still
   speaks serials; it is the honest spelling for that side."
  (into {} (for [n borrowed-names :when (not (date-shape n))] [(kebab n) n])))

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

;; --- reference copy for the ƒ panel ----------------------------------------
;;
;; The panel used to list bare names, which tells a user that `xround` exists
;; and nothing about what it does or how it differs from `round`. These are the
;; functions whose semantics we CHOSE, so nobody else's documentation covers
;; them — the borrowed half can point at Excel's name and be understood, this
;; half cannot. `docs-for` falls back to a generated entry for those, so every
;; name in the panel has something to say.

(def ^:private hand-docs
  "{sym {:desc one line :eg a runnable example}} for the hand-written half."
  '{;; math
    abs      {:desc "Absolute value."                          :eg "(abs $A1)"}
    ceil     {:desc "Round UP to a whole number. Excel's round-to-a-multiple is `ceiling-math`."
              :eg "(ceil $A1)"}
    floor    {:desc "Round DOWN to a whole number. Excel's round-to-a-multiple is `floor-math`."
              :eg "(floor $A1)"}
    round    {:desc "Round to the nearest whole number. For decimal places use `xround`."
              :eg "(round $A1)"}
    sqrt     {:desc "Square root."                             :eg "(sqrt $A1)"}
    pow      {:desc "Raise to a power."                        :eg "(pow $A1 2)"}
    exp      {:desc "e raised to a power."                     :eg "(exp $A1)"}
    ln       {:desc "Natural logarithm."                       :eg "(ln $A1)"}
    log10    {:desc "Base-10 logarithm."                       :eg "(log10 $A1)"}
    sign     {:desc "-1, 0 or 1, matching the sign of the number." :eg "(sign $A1)"}
    sum      {:desc "Adds the numbers in a range. Blank cells and text are skipped."
              :eg "(sum $A1:A9)"}
    product  {:desc "Multiplies the numbers in a range, skipping blanks."
              :eg "(product $A1:A9)"}
    pi       {:desc "The constant π. A value, not a function — write it bare."
              :eg "(* pi (pow $A1 2))"}
    ;; matrices
    transpose {:desc "Flips a rectangle over its diagonal. Needs an #area, not a flat range."
               :eg "(transpose #area A1:C2)"}
    matmul    {:desc "Matrix product. Both sides are #area rectangles; the result is one too."
               :eg "(matmul #area A1:C2 #area E1:F3)"}
    ;; stats
    mean     {:desc "Arithmetic mean, skipping blanks. `avg` is the same function."
              :eg "(mean $A1:A9)"}
    avg      {:desc "Arithmetic mean, skipping blanks. Alias of `mean`."
              :eg "(avg $A1:A9)"}
    median   {:desc "Middle value, skipping blanks."           :eg "(median $A1:A9)"}
    variance {:desc "Population variance, skipping blanks."    :eg "(variance $A1:A9)"}
    stdev    {:desc "Population standard deviation, skipping blanks."
              :eg "(stdev $A1:A9)"}
    xmin     {:desc "Smallest number in a range, skipping blanks. Plain `min` is clojure.core's and is not blank-safe."
              :eg "(xmin $A1:A9)"}
    xmax     {:desc "Largest number in a range, skipping blanks."
              :eg "(xmax $A1:A9)"}
    ;; text
    upper    {:desc "Upper-case a string."                     :eg "(upper $A1)"}
    lower    {:desc "Lower-case a string."                     :eg "(lower $A1)"}
    trim     {:desc "Strip leading and trailing whitespace."   :eg "(trim $A1)"}
    join     {:desc "Join a range into one string with a separator."
              :eg "(join \", \" $A1:A9)"}
    split    {:desc "Split a string on a separator, giving a vector of parts."
              :eg "(split $A1 \",\")"}
    str-replace {:desc "Replace every occurrence of a substring."
                 :eg "(str-replace $A1 \"old\" \"new\")"}
    starts-with? {:desc "Does the string begin with this prefix?"
                  :eg "(starts-with? $A1 \"INV-\")"}
    ends-with?   {:desc "Does the string end with this suffix?"
                  :eg "(ends-with? $A1 \".pdf\")"}
    includes?    {:desc "Does the string contain this substring?"
                  :eg "(includes? $A1 \"urgent\")"}
    blank?       {:desc "True for an empty cell or a string of only whitespace."
                  :eg "(if (blank? $A1) 0 $A1)"}
    ;; dates — ISO yyyy-MM-dd strings throughout
    today        {:desc "Today's date as an ISO string. Recomputes when the sheet does, not on a clock."
                  :eg "(today)"}
    year         {:desc "Year of an ISO date string."          :eg "(year $A1)"}
    month        {:desc "Month (1-12) of an ISO date string."  :eg "(month $A1)"}
    day          {:desc "Day of the month of an ISO date string." :eg "(day $A1)"}
    days-between {:desc "Whole days from the first date to the second."
                  :eg "(days-between $A1 $B1)"}
    ;; excel-compat — what the .xlsx importer targets
    xround   {:desc "Excel's ROUND: round to N decimal places, half away from zero."
              :eg "(xround $A1 2)"}
    ≈        {:desc "Same number, allowing for floating-point noise? Use it instead of = whenever both sides are COMPUTED — 0.1+0.2 is not 0.3 in binary. A third argument is your own absolute tolerance; 0 means exactly equal."
              :eg "(if (≈ $A1 $B1) \"match\" \"differ\")"}
    xdate    {:desc "Excel's DATE: build an ISO date string from year, month, day."
              :eg "(xdate 2026 3 15)"}
    xvlookup {:desc "Excel's VLOOKUP, EXACT match only. Table width is explicit; column is 1-based."
              :eg "(xvlookup $A1 $B1:D9 3 2)"}
    excel-truthy {:desc "Excel's truthiness: 0 is false, other numbers are true. The importer wraps conditions in this."
                  :eg "(if (excel-truthy $A1) 1 0)"}
    as-rows  {:desc "Reshape a flat range into rows of N. `#area` is usually what you want instead."
              :eg "(as-rows 2 $A1:B4)"}
    ;; errors
    if-error {:desc "Value of the expression, or the fallback if it fails. Guards the EXPRESSION, not an error arriving from a referenced cell."
              :eg "(if-error (/ $A1 $B1) 0)"}
    if-na    {:desc "Like `if-error`, but only catches #N/A."
              :eg "(if-na (xvlookup $A1 $B1:C9 2 2) \"not found\")"}
    error-type {:desc "The error an expression raises as a keyword (:div0 :na :value :ref :name :num), or nil if it succeeds."
                :eg "(error-type (/ $A1 $B1))"}
    error?     {:desc "Does this expression fail?"             :eg "(error? (/ $A1 $B1))"}})

(defn- arity-phrase [xl-name]
  (let [[mn mx] (excel/arity xl-name)]
    (cond (and mn mx (= mn mx)) (str mn " argument" (when (not= 1 mn) "s"))
          (and mn mx)           (str mn "-" mx " arguments")
          mn                    (str mn " or more arguments")
          :else                 nil)))

(def ^:private borrowed-origin
  "sym -> the Excel name it was borrowed from, for EVERY borrowed function.
   `excel-name` deliberately omits the date-shaped ones (their signatures differ
   across the .xlsx boundary); documentation has no such problem and wants them
   all."
  (into {} (for [n borrowed-names] [(kebab n) n])))

(defn- placeholders
  "`n` runnable argument slots. Real cell refs rather than `…`, because the
   panel's copy button puts this straight into a cell and a template that errors
   on paste is a worse first impression than one you have to re-point."
  [n]
  (str/join " " (for [i (range n)] (str "$" (char (+ (int \A) i)) "1"))))

(declare source-for)

(defn docs-for
  "{:desc :eg} for a stdlib name, plus how the panel gets at its source.
   Hand-written entries are curated; a borrowed name generates one from the
   Excel function it is, plus upstream's arity — which is honest, and is the
   thing a spreadsheet user actually wants to know (that `stdev-p` IS
   `STDEV.P`) rather than prose invented here.

   A hand-written function carries its `:src` INLINE, because it is a few lines.
   A borrowed one is marked `:fetch` instead and served on demand: its source is
   the real upstream implementation with the helpers it needs, ~5KB each and
   1.2MB across the panel, which is not something to put in every page load for
   the one function somebody eventually copies."
  [sym]
  (or (some-> (hand-docs sym) (assoc :src (source-for sym)))
      (when-let [xl (borrowed-origin sym)]
        (let [n (min 4 (max 1 (or (first (excel/arity xl)) 1)))]
          {:desc  (str "Excel's " xl
                       (when-let [a (arity-phrase xl)] (str " — " a))
                       (when (date-shape xl)
                         ". Dates are ISO yyyy-MM-dd strings here, not serials"))
           :eg    (str "(" sym " " (placeholders n) ")")
           :fetch true}))))

;; --- handing a function over as standalone Clojure -------------------------
;;
;; The scenario this exists for: you import a workbook, or flatten a formula,
;; and end up with one large expression that calls `sum`, `xround`, `xvlookup`.
;; Then you want to run that calculation in an ordinary Clojure application,
;; where none of those names exist. So the ƒ panel hands you the source.
;;
;; It has to be RUNNABLE, which means more than the one `defn`: `sum` is nothing
;; without `nums`, and `median` and `stdev` lean on the same helper. `source-for`
;; walks the body for helpers it recorded and emits them first, then the
;; namespace requires the result actually uses.

(def ^:private alias-note
  "Requires a pasted definition may need, keyed by the alias its source mentions."
  {"str" "[clojure.string :as str]"})

(defn- used-helpers
  "Helper names appearing anywhere in `form`, transitively — `stdev` uses `var*`,
   which uses `nums`."
  [form]
  (let [helpers @helper-src]
    (loop [pending [form] seen #{}]
      (if-let [f (first pending)]
        (let [found (->> (tree-seq coll? seq f)
                         (filter symbol?)
                         (filter helpers)
                         (remove seen))]
          (recur (into (rest pending) (map helpers found))
                 (into seen found)))
        seen))))

(defn- ordered-helpers
  "Helper definitions sorted so each comes AFTER the ones it calls — `mean*`
   needs `nums` above it, and Clojure will not read forward. Alphabetical order
   put `mean*` first and the paste did not compile."
  [names]
  (let [src @helper-src]
    (loop [pending (set names) out []]
      (if (empty? pending)
        out
        (let [ready (filter (fn [n]
                              (let [used (->> (tree-seq coll? seq (src n))
                                              (filter symbol?) set)]
                                (empty? (disj (set/intersection used pending) n))))
                            pending)
              ;; a cycle among helpers would loop forever; there is none today,
              ;; and emitting the rest in any order beats hanging
              ready (if (seq ready) ready [(first pending)])]
          (recur (apply disj pending ready) (into out ready)))))))

(defn- as-defn
  "The recorded value of a stdlib entry, as a top-level definition named `sym`.
   `(fn [c] …)` becomes `(defn sum [c] …)`; a private helper like `mean*` becomes
   a `def`, which is what it is.

   A name that is just clojure.core's gets a NOTE instead: `(def abs abs)` binds
   the new var to itself (the RHS resolves to the var being defined, not to
   core's), so the paste compiles and then throws `unbound fn`."
  [sym v]
  (cond
    (and (seq? v) (= 'fn (first v)) (vector? (second v)))
    (concat (list 'defn sym (second v)) (drop 2 v))

    (and (seq? v) (= 'fn (first v)))            ; multi-arity: (fn ([c] …) ([s c] …))
    (concat (list 'defn sym) (rest v))

    :else (list 'def sym v)))

(defn- core-alias
  "The clojure.core name this entry simply IS, if that is all it is."
  [v]
  (when (symbol? v)
    (when-let [r (ns-resolve 'uno.michelada.saltrim.stdlib v)]
      (when (= (find-ns 'clojure.core) (:ns (meta r)))
        (symbol "clojure.core" (name (:name (meta r))))))))

(defn source-for
  "Standalone Clojure source for a stdlib function: the helpers it needs, the
   definition itself, and the `require`s to make it compile. nil when the
   implementation is not ours to hand over.

   A BORROWED function is Excel's, implemented upstream, so what comes back is
   SaltRim's own one-line delegation plus the dependency you would need — honest
   about where the work happens rather than pretending to be self-contained.
   A macro (`if-error` and friends) has no source worth pasting: the point of it
   is laziness inside SaltRim's sandbox, and plain Clojure already has `try`."
  [sym]
  (let [v (hand-written-src sym)]
    (cond
      ;; macros: the sandbox needs them, an application does not
      (and (seq? v) (= 'with-meta (first v))) nil

      (core-alias v)
      (str ";; `" sym "` IS " (core-alias v) " — you already have it.")

      (some? v)
      (let [helpers (used-helpers v)
            strip   (fn [f] (if (and (= 'defn (first f)) (string? (nth f 2)))
                              (concat (take 2 f) (drop 3 f))    ; our docstring is not their problem
                              f))
            forms   (concat (map (comp strip @helper-src) (ordered-helpers helpers))
                            [(as-defn sym v)])
            text    (str/join "\n\n" (map pr-str forms))
            reqs    (keep (fn [[a r]] (when (re-find (re-pattern (str "\\b" a "/")) text) r))
                          alias-note)]
        (str/join "\n" (concat (when (seq reqs)
                                 [(str "(require '" (str/join "\n         '" reqs) ")") ""])
                               [text])))

      ;; A borrowed function is implemented upstream, and saying so was all this
      ;; used to do — a comment plus `(defn erfc [& args] (excel/call "ERFC" args))`,
      ;; which is unrunnable without the very dependency you were trying to
      ;; leave behind and shows you nothing about what ERFC computes. So the
      ;; implementation is fetched out of rechentafel's own shipped source.
      (borrowed-origin sym)
      (let [xl (borrowed-origin sym)]
        (or (xlsource/source-for sym xl (date-shape xl))
            (str ";; `" sym "` is Excel's " xl ", implemented by rechentafel, and its\n"
                 ";; source could not be located in this build.\n"
                 ";; deps.edn  org.replikativ/rechentafel {:mvn/version \"0.1.5\"}\n"
                 "(defn " sym " [& args] (excel/call \"" xl "\" args))")))

      :else nil)))
