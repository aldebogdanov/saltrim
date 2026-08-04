(ns uno.michelada.saltrim.xlformula
  "SaltRim formula source -> an Excel formula string, so an exported workbook
   RECALCULATES in Excel instead of being a frozen snapshot of numbers.

   The inverse of `xlsx`'s importer, and deliberately built on the same two
   tables so the two directions cannot drift: `stdlib/excel-name` for the ~213
   borrowed functions, `excel/exposed-names` for everything reachable as `xl/`.
   A handful of names we map by hand, because the importer chose different
   semantics on the way in (`MIN` -> `xmin`, `ROUND` -> `xround`) and the way
   back has to undo exactly that choice.

   The Excel text itself comes from `rechentafel.unparse`, which the importer
   already parses with — so precedence, parenthesisation, string escaping and
   `$`-absolute refs are its problem, not ours. We only build the AST.

   Not everything can go. A formula calling a user `def` chunk, a dynamic ref
   `$(expr)`, or any Clojure the vocabulary has no Excel name for throws
   ::unsupported, and `export` falls back to writing the computed VALUE for that
   cell — which is what every cell used to get. So this is strictly additive:
   worst case is the old behaviour, cell by cell.

   One asymmetry is by design. `formula/parse` EXPANDS `$A1:A3` into a
   `(vector …)` of per-cell refs at read time and never round-trips it back, so
   `refs->range` folds an exact rectangle's row-major expansion into a range
   node. The equality check makes that fold exact rather than a guess — and it
   matters, because `SUM(A1,A2,…,A500)` would blow Excel's 8192-character
   formula limit where `SUM(A1:A500)` is nine."
  (:require [clojure.string :as str]
            [rechentafel.unparse :as xlu]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.stdlib :as lib]))

(def ^:private REF   :uno.michelada.saltrim.formula/ref)
(def ^:private RANGE :uno.michelada.saltrim.formula/range)

(defn- unsupported! [why]
  (throw (ex-info (str "unsupported: " why) {::unsupported why})))

(def ^:private op-node
  "Clojure head symbol -> rechentafel binary-operator keyword."
  '{+ :plus - :minus * :mul / :div pow :pow
    = :eq not= :ne < :lt <= :le > :gt >= :ge})

(def ^:private fn-name
  "Hand-mapped heads: the ones the importer did NOT translate mechanically, so
   the way back cannot be mechanical either. `xmin`/`xmax` skip blanks where
   `MIN`/`MAX` do, `xround` is Excel's 2-arg ROUND, `xdate` its DATE."
  '{sum "SUM" mean "AVERAGE" median "MEDIAN" xmin "MIN" xmax "MAX"
    abs "ABS" sqrt "SQRT" exp "EXP" ln "LN" log10 "LOG10" sign "SIGN"
    xround "ROUND" today "TODAY" xdate "DATE" upper "UPPER" lower "LOWER"
    trim "TRIM" and "AND" or "OR" not "NOT" join "CONCATENATE"})

(def ^:private splice
  "Heads the importer's `coll-arg` wraps aggregate arguments in; they are
   plumbing, not functions, and their contents are the real argument list."
  '#{vector flatten})

(def ^:private xl-names (delay (set excel/exposed-names)))

(defn- ref-addr [x] (when (and (seq? x) (= REF (first x))) (second x)))

(defn- refs->range
  "A run of ref markers that is EXACTLY some rectangle's row-major expansion ->
   [top-left bottom-right], else nil. `formula/parse` expands `$A1:A3` to a
   `(vector …)` of refs and documents that it never comes back, so this is the
   only route home. The equality check against `addr/range-cells` is what makes
   it a fold rather than a guess: a gappy `(vector $A1 $A3)` correctly declines."
  [items]
  (let [as (mapv ref-addr items)]
    (when (and (seq as) (every? some? as))
      (let [ps (mapv addr/parse as)
            tl (addr/make (apply min (map :ci ps)) (apply min (map :ri ps)))
            br (addr/make (apply max (map :ci ps)) (apply max (map :ri ps)))]
        (when (= as (vec (addr/range-cells tl br))) [tl br])))))

(defn- area->range
  "The `#area` counterpart of `refs->range`: a run of ROW vectors of refs that
   together tile a rectangle -> [top-left bottom-right]. Without this an area
   argument would splice into one range PER ROW, so `TRANSPOSE(#area A1:B2)`
   would come out as `TRANSPOSE(A1:B1,A2:B2)` — a two-argument call to a
   one-argument function."
  [items]
  (when (and (seq items)
             (every? #(and (seq? %) (= 'vector (first %))
                           (seq (rest %)) (every? ref-addr (rest %)))
                     items))
    (let [grid (mapv #(mapv ref-addr (rest %)) items)
          tl   (ffirst grid)
          br   (peek (peek grid))]
      (when (= (apply concat grid) (addr/range-cells tl br)) [tl br]))))

(declare form->ast)

(defn- ref-node [a]
  (let [{:keys [ci ri]} (addr/parse a)] {:op :ref :col ci :row ri}))

(defn- range-node [tl br]
  {:op :range :left (ref-node tl) :right (ref-node br)})

(defn- args->ast
  "Argument forms -> AST nodes, unwrapping `coll-arg`'s `(vector …)`/`(flatten …)`
   and folding an exact rectangle back into a single range node."
  [args scope]
  (vec (mapcat (fn [a]
                 (if (and (seq? a) (splice (first a)))
                   (let [items (rest a)]
                     (if-let [[tl br] (or (refs->range items) (area->range items))]
                       [(range-node tl br)]
                       (args->ast items scope)))
                   [(form->ast a scope)]))
               args)))

(defn- unthunk
  "`if-error`'s first argument is a `(fn [] …)` — the importer emits that shape
   so the guarded expression stays lazy. Excel's IFERROR is lazy by itself."
  [g]
  (if (and (seq? g) (= 'fn (first g)) (vector? (second g))) (nth g 2) g))

(defn- count-idiom
  "`LEN`/`COUNT`/`COUNTA` have no single Clojure equivalent, so the importer
   expands them — `LEN(x)` into `(count (str x))`, `COUNT(r)` into
   `(count (filter number? r))`, `COUNTA(r)` into `(count (remove nil? r))`.
   Recognising the three shapes is what lets an imported sheet export back with
   its own function names instead of refusing on a bare `count`.

   The arities are checked, not assumed: `COUNT()` with no argument is not a
   formula Excel accepts, and a formula Excel rejects costs the whole FILE
   rather than the one cell. Returns [excel-name args] or nil."
  [args]
  (let [a (first args)]
    (when (and (= 1 (count args)) (seq? a))
      (let [[h & as] a
            n (count as)]
        (cond
          (and (= 'str h)    (= 1 n))                        ["LEN"    as]
          (and (= 'filter h) (= 2 n) (= 'number? (first as))) ["COUNT"  (rest as)]
          (and (= 'remove h) (= 2 n) (= 'nil? (first as)))    ["COUNTA" (rest as)])))))

(defn form->ast
  "One SaltRim marker form -> a rechentafel AST node. `scope` maps the symbols a
   surrounding `let` bound to the names Excel will see. Throws (::unsupported in
   ex-data) on anything with no Excel spelling."
  ([x] (form->ast x {}))
  ([x scope]
   (let [go #(form->ast % scope)]
     (cond
       (boolean? x) {:op :bool :value x}
       (number? x)  {:op :num :value (double x)}
       (string? x)  {:op :str :value x}
       (vector? x)  {:op :array :rows (if (every? vector? x)
                                        (mapv #(mapv go %) x)
                                        [(mapv go x)])}
       (ref-addr x) (ref-node (ref-addr x))
       (symbol? x)  (if-let [nm (scope x)]
                      {:op :name :value nm}
                      (unsupported! (str "symbol " x)))

       (and (seq? x) (= RANGE (first x))) (range-node (nth x 1) (nth x 2))

       (seq? x)
       (let [[h & args] x
             n (count args)]
         (cond
           (and (op-node h) (= 2 n))
           {:op :binop :sym (op-node h) :left (go (first args)) :right (go (second args))}

           (and (= '- h) (= 1 n)) {:op :unop :sym :minus :arg (go (first args))}

           ;; `excel-truthy` only exists to give a condition that came FROM Excel
           ;; its number-truthiness back; going the other way it is the identity.
           ;; A 1-arg `str` is the same kind of plumbing — the importer's
           ;; coercion around LEN/TRIM/UPPER args, not a concatenation.
           (= 'excel-truthy h)             (go (first args))
           (and (= 'str h) (= 1 n))        (go (first args))
           (= 'str h) {:op :call :name "CONCATENATE" :args (args->ast args scope)}

           (and (= 'if h) (= 3 n)) {:op :call :name "IF" :args (args->ast args scope)}

           (and (= 'if-error h) (= 2 n))
           {:op :call :name "IFERROR"
            :args [(go (unthunk (first args))) (go (second args))]}

           (and (= 'let h) (= 2 n) (vector? (first args)))
           (let [[scope' bindings]
                 (reduce (fn [[sc bs] [k v]]
                           ;; each binding sees the ones before it, not itself
                           [(assoc sc k (str k)) (conj bs [(str k) (form->ast v sc)])])
                         [scope []] (partition 2 (first args)))]
             {:op :let :bindings bindings :body (form->ast (second args) scope')})

           (and (= 'count h) (count-idiom args))
           (let [[nm as] (count-idiom args)]
             {:op :call :name nm :args (args->ast as scope)})

           (fn-name h)        {:op :call :name (fn-name h) :args (args->ast args scope)}
           (lib/excel-name h) {:op :call :name (lib/excel-name h)
                               :args (args->ast args scope)}

           (and (qualified-symbol? h) (= "xl" (namespace h)) (@xl-names (name h)))
           {:op :call :name (name h) :args (args->ast args scope)}

           :else (unsupported! (str "function " h))))

       :else (unsupported! (str "form " (pr-str x)))))))

(defn source->excel
  "SaltRim cell source (with or without the leading `=`) -> an Excel formula
   string WITHOUT a leading `=`, which is what POI's `setCellFormula` wants.
   Throws (::unsupported in ex-data) when the formula has no Excel spelling."
  [src]
  (let [s (str/trim (str src))
        s (if (str/starts-with? s "=") (subs s 1) s)]
    (xlu/unparse (form->ast (:form (formula/parse s nil))))))

(defn try-excel
  "`source->excel`, or nil when the formula cannot cross the boundary. Export
   wants the fallback, not the exception — a cell that will not translate is a
   cell that gets its computed value, exactly as before."
  [src]
  (try (source->excel src) (catch Exception _ nil)))
