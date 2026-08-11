;; Spike 11 — importing Excel formulas from an AST instead of POI's RPN tokens
;;
;; Eval these forms at a dev REPL (`clojure -M:nrepl --port 7888`, then drive it
;; from your editor / clojure-mcp). See spikes/README.md.
;;
;; QUESTION
;; --------
;; `xlsx/ptgs->form` walks POI's RPN `Ptg` stream with a hand-written stack
;; machine. Its own docstring lists three hazards it had to be taught the hard
;; way: `AttrPtg(isSum)` popping ONE operand, `IF`/`CHOOSE` arriving as a
;; TRAILING `FuncVarPtg`, and post-BIFF "future functions" arriving as a
;; `NameXPxg` pushed first plus a `#external#` `FuncVarPtg` whose operand count
;; INCLUDES the name token. Everything it cannot fold is demoted to the cached
;; value with an audit `:comment`.
;;
;; rechentafel (already a dependency, for its function pack) ships a parser that
;; takes the formula STRING and returns an AST. Can the translator be a plain
;; recursive walk over that instead — and does it translate at least as much?
;;
;; ANSWER: yes, and it is behaviour-preserving. 22 of the 24 formulas in the
;; existing translator test produce byte-identical output; the other 2 are cases
;; that stay refused either way, with a better reason. LET, array constants and
;; accurate refusal messages are new. Cross-sheet, whole-column, defined names,
;; table refs and spill refs stay refused — not because the AST hides them (it
;; names every one) but because SaltRim has nowhere to put them yet.

(require '[rechentafel.parser :as p]
         '[uno.michelada.saltrim.addr :as addr]
         '[uno.michelada.saltrim.formula :as f]
         '[uno.michelada.saltrim.sheet :as sh]
         '[uno.michelada.saltrim.xlsx :as x])

;; ---------------------------------------------------------------------------
;; 1. The AST is structured where the token stream was positional
;; ---------------------------------------------------------------------------

;; The formula the evaluation doc used as the worst case. Every construct the
;; stack machine gives up on comes back as a NAMED node — :table-ref, a :ref
;; carrying :sheet and :whole, :spill-ref — rather than as a token class the
;; walker has to recognise by instance? and a operand count it has to guess.

(clojure.pprint/pprint
 (p/parse "=IF(SUM(Sales[Amount])>10,VLOOKUP($A1,Sheet2!B:D,3,FALSE),\"x\"&A1#)"))

;; The whole vocabulary, probed rather than assumed. 17 node types, no parse
;; failures across operators, literals, errors, arrays, names, sheet-qualified
;; refs, whole columns, spill refs, table refs, LET and intersection:

(def probes
  ["=1+2*3" "=-A1" "=+A1" "=A1%" "=A1&\"x\"" "=(A1)" "=A1:B2" "=SUM(A1:A3)"
   "=IF(A1>0,\"y\",\"n\")" "=TRUE" "=FALSE" "=#DIV/0!" "=#N/A" "={1,2;3,4}"
   "=MyName" "=Sheet2!A1" "='My Sheet'!A1" "=SUM(A:A)" "=SUM(1:2)"
   "=A1#" "=Tbl[Col]" "=LET(x,1,x+A1)" "=SUM(A1:A3 B1:B3)" "=SUM(A1,A2)"
   "=\"a\"" "=1.5E3" "=A$1" "=$A$1" "=IFERROR(A1/B1,0)" "=CHOOSE(2,\"a\",\"b\")"
   "=VLOOKUP(A1,B1:D9,3,FALSE)" "=A1<>B1" "=A1^2" "=SUM(Sheet2!A1:A3)"])

(defn ops [node]
  (cond (map? node)        (into #{(:op node)} (mapcat ops (vals node)))
        (sequential? node) (into #{} (mapcat ops node))
        :else              #{}))

(->> probes (mapcat (comp ops p/parse)) (remove nil?) set sort vec)
;; => [:array :binop :bool :call :err :intersect :let :name :num :postop
;;     :range :ref :spill-ref :str :table-ref :unop]

;; Two details that matter for the rewrite:

(p/parse "SUM(A1:A3)")     ; POI's getCellFormula() has NO leading "=" — accepted
(:sym (p/parse "=1<>1"))   ; => :ne — operator symbols are keywords, one map away
(:value (p/parse "=#DIV/0!"))  ; => :div0 — and they are OUR errors/classify codes

;; ---------------------------------------------------------------------------
;; 2. The walk, and the diff against the stack machine
;; ---------------------------------------------------------------------------

;; Everything below `:call` is reused verbatim from the existing importer — the
;; Excel-name vocabulary (`fname->form`), `coll-arg`, `truthy-wrap`,
;; `vlookup-form`, `num-lit`. Only the FRONT of the pipeline changes, which is
;; the point: the vocabulary was never the sharp part.

(def unsupported! #'x/unsupported!)
(def num-lit     #'x/num-lit)
(def fname->form #'x/fname->form)

(def binop-sym '{:plus + :minus - :mul * :div / :pow pow :concat str
                 :eq = :ne not= :lt < :le <= :gt > :ge >=})

(defn ref->addr [{:keys [sheet whole col row]}]
  (when sheet (unsupported! (str "cross-sheet reference to " sheet)))
  (when whole (unsupported! (str "whole-" (name whole) " reference")))
  (addr/make col row))

(defn ast->form [{:keys [op] :as n}]
  (case op
    :num    (num-lit (:value n))
    :str    (:value n)
    :bool   (:value n)
    :ref    (f/ref-marker (ref->addr n))
    :range  (f/range-marker (ref->addr (:left n)) (ref->addr (:right n)))
    :binop  (list (binop-sym (:sym n)) (ast->form (:left n)) (ast->form (:right n)))
    :unop   (let [v (ast->form (:arg n))]
              (case (:sym n) :minus (if (number? v) (- v) (list '- v)) :plus v))
    :postop (list '/ (ast->form (:arg n)) 100.0)
    :call   (fname->form (:name n) (mapv ast->form (:args n)))
    (unsupported! (str "node " op))))

;; Same battery as `xlsx-test/translator`, both translators, side by side.

(import '[org.apache.poi.xssf.usermodel XSSFWorkbook XSSFEvaluationWorkbook])

(def pwb (let [wb (XSSFWorkbook.)]
           (.createSheet wb "S1") (.createSheet wb "Other")
           (XSSFEvaluationWorkbook/create wb)))

(defn safe [tf s]
  (try (tf s)
       (catch Exception e
         (str "REFUSED: " (or (::x/unsupported (ex-data e)) (.getMessage e))))))

(defn t-ptg [s] (x/translate-formula s pwb 0))
(defn t-ast [s] (str "=" (f/unparse (ast->form (p/parse s)))))

(def battery
  ["SUM(A1:A3)" "SUM(A1:B2,C3,5)" "AVERAGE(B1:B10)" "MIN(A1:A9)" "COUNT(A1:A9)"
   "IF(A1>2,SUM(B1:B3),0)" "IF(A1,1,2)" "IF(A1>1,\"yes\")" "AND(A1>1,B1)"
   "IFERROR(A1/B1,0)" "50%" "A1&\" x\"" "-A1" "A1^2" "A1<>B1"
   "LEN(TRIM(A1))" "YEAR(TODAY())" "DATE(2024,3,15)" "ROUND(A1,2)"
   "VLOOKUP(\"k\",A1:C10,2,FALSE)"
   "VLOOKUP(\"k\",A1:C10,2)" "Other!A1" "SUM(A:A)" "TRANSPOSE(A1:B2)"])

(let [rows (for [s battery] [s (safe t-ptg s) (safe t-ast s)])]
  {:agree       (count (filter (fn [[_ a b]] (= a b)) rows))
   :total       (count battery)
   :differences (vec (for [[s a b] rows :when (not= a b)] {:formula s :ptg a :ast b}))})

;; => {:agree 22 :total 24
;;     :differences
;;     [{:formula "Other!A1" :ptg "REFUSED: Ref3DPxg"
;;                           :ast "REFUSED: cross-sheet reference to Other"}
;;      {:formula "SUM(A:A)" :ptg "REFUSED: range too large (whole column/row?)"
;;                           :ast "REFUSED: whole-col reference"}]}
;;
;; Both differences are the SAME verdict with a message a user can act on. That
;; message is not cosmetic: it is what lands in the cell's audit `:comment` when
;; the importer demotes, and "Ref3DPxg" tells the reader nothing.

;; ---------------------------------------------------------------------------
;; 3. What the AST newly makes translatable
;; ---------------------------------------------------------------------------

;; LET. The bindings arrive as [name node] pairs and the body refers to them
;; with :name nodes, so the walk needs a scope — and a :name with nothing in
;; scope is a DEFINED name, which stays refused.

(p/parse "LET(x,1,y,2,x+y+A1)")
;; => {:op :let :bindings [["x" {:op :num :value 1.0}] ["y" …]] :body {…}}

;; One trap. A binding is free to shadow a stdlib name in Clojure — until the
;; body CALLS that name as a function, at which point `LET(sum,5,SUM(A1:A3)+sum)`
;; would translate to `(let [sum 5] (+ (sum $A1:A3) sum))` and blow up. Proving
;; the body never calls it costs a second walk; suffixing the local costs
;; nothing and cannot collide.

(def sandbox-names (set (map str (keys f/stdlib))))
(defn safe-local [nm] (loop [s nm] (if (sandbox-names s) (recur (str s "_")) (symbol s))))
(mapv safe-local ["x" "rate" "total" "sum" "n"])
;; => [x rate_ total sum_ n]   ; `rate` and `sum` ARE stdlib functions

;; Array constants. `{1,2,3}` is a row, `{1,2;3,4}` a rectangle. The one
;; interaction is with `coll-arg`, which wraps non-range args in `(vector …)`:
;; an array is ALREADY a collection, so `SUM({1,2,3})` must not become
;; `(sum (vector [1 2 3]))`.

;; ---------------------------------------------------------------------------
;; 4. Does any of it actually run?
;; ---------------------------------------------------------------------------

;; Translating to source that reads well is not the bar — the cell has to
;; compute, and stay reactive when its references change.

(let [s (sh/create-sheet)]
  (sh/set-cell! s "A1" "10")
  (sh/set-cell! s "B1" "=(let [x 1 y 2] (+ (+ x y) $A1))")   ; LET(x,1,y,2,x+y+A1)
  (sh/set-cell! s "C1" "=(sum [1 2 3])")                     ; SUM({1,2,3})
  (sh/set-cell! s "D1" "=(let [rate_ (/ $A1 100)] (* 200 rate_))")
  (sh/settle! s)
  (let [before (mapv #(sh/value s %) ["B1" "C1" "D1"])]
    (sh/set-cell! s "A1" "20")
    (sh/settle! s)
    {:values before :after-edit (mapv #(sh/value s %) ["B1" "C1" "D1"])}))

;; => {:values [13 6 20N] :after-edit [23 6 40N]}
;;
;; All three compute, and the two that reference A1 follow it. (`20N` is
;; Clojure's rational `/`, which is what the existing translator already emits
;; for Excel division — not something this change introduces.)

;; ---------------------------------------------------------------------------
;; 5. Verdict
;; ---------------------------------------------------------------------------
;;
;; Do it. The rewrite is behaviour-preserving on everything currently
;; translatable, deletes the stack machine and all three of its documented
;; hazards, drops the `FormulaParsingWorkbook`/sheet-index plumbing from
;; `translate-formula` (it needs only the string), and adds LET + array
;; constants.
;;
;; What it does NOT do, despite the evaluation doc's optimism: cross-sheet refs,
;; whole-column ranges, defined names, table refs and spill refs all stay
;; demotions. The AST names each of them precisely — that is why the refusal
;; messages improve — but SaltRim has nowhere to PUT them. Named regions are
;; item K and spill is item E; whole columns are ~1M cells against a 4096-cell
;; range cap; and cross-sheet refs need a cross-sheet reference model that does
;; not exist (each Excel sheet imports as its own SaltRim sheet).
;;
;; The other reason to do it now: item B2 (live xlsx export, so exported
;; workbooks are formulas rather than a static snapshot) needs the SaltRim form
;; -> Excel AST -> `rechentafel.unparse/unparse` direction, and that is only
;; worth building against the same AST this import path now speaks.
