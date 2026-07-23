(ns uno.michelada.saltrim.formula
  "Formula = a Clojure expression, evaluated by SCI in a sandbox. Cell refs via
   reader tags:
     #cell  A1       -> current value of A1
     #cells A1:A3    -> vector of current values [A1 A2 A3]      (column)
     #cells A1:C1    -> [A1 B1 C1]                               (row)
     #cells A1:B2    -> [A1 B1 A2 B2]  ROW-MAJOR rectangle       (block)
   Any inclusive rectangle works (for map/reduce); ranges expand at read time.

   Terse sugar: a bare `$A1` reads like `#cell A1`, and `$A3:D8` like
   `#cells A3:D8` — `$` is just a compact cell-ref sigil (shifts on paste like the
   reader tags). See `dollar-ref`.

   Relative sugar: `$<col><row>` names a cell by OFFSET from the owner — each of
   col/row is `_` (same index), `+N`, or `-N`. e.g. in B3, `$_-1` -> B2 and
   `$-2_` -> the cell two columns left. Resolved against the owner at parse time,
   so it is copy-invariant (re-resolves per destination — `=(inc $_-1)` copied
   down fills a running counter). See `rel-ref`.

   Why SCI: the old path host-`eval`d the body under a symbol whitelist, which
   could not allow `let`/`fn` (the user's own binder names aren't in the list).
   SCI gives real lexical scope, fn literals, destructuring, etc., in a sandbox
   (no host interop) — see spikes/03-sci-formula-eval.clj.

   How it composes with the reactive engine: every cell is a Spin, refs use
   `await`. After the await-lift the user body is PURE — every `await` sits in
   the `let` bindings (our infra); the body just computes over already-resolved
   values. So SCI never sees `spin`/`await`/`track`; it compiles only the user
   expression to a host-callable fn of the cell values, and the spin wrapper
   stays host-compiled and closes over it:

     (fn [uf] (spin (let [c1 (await (lookup A1)) ...] (uf c1 ...))))   ; host
     uf = (sci/eval '(fn [c1 ...] <user-body>))                        ; sandbox

   Each DISTINCT referenced cell is awaited once (de-dup; awaiting the same spin
   twice in a body glitches on recompute). `await`/`lookup` appear literally in
   the spin body (CPS breakpoints), never inside a nested fn.

   Pipeline:
     parse    : string -> {:form <marker-form> :deps #{addr}}
     compile  : marker-form -> Spin (SCI-compile body, lift refs, eval spin)."
  (:refer-clojure :exclude [compile await])
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sci.core :as sci]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.runtime :as rt]))

;; --- parse --------------------------------------------------------------

(defn ref-marker
  "The parsed representation of a cell reference: (::ref \"A1\")."
  [addr] (list ::ref addr))

(defn ref?
  "Is `x` a parsed cell-ref marker?"
  [x] (and (seq? x) (= ::ref (first x))))

(defn dynref-marker
  "The parsed representation of a DYNAMIC cell/range reference `$(expr)`:
   (::dynref <expr-form>). The expression computes an address string at
   runtime (\"A5\" or \"A1:B3\") — resolved and guarded by `rt/resolve-dyn` /
   `rt/lookup-dyn` inside the compiled spin body."
  [form] (list ::dynref form))

(defn dynref?
  "Is `x` a parsed dynamic-ref marker?"
  [x] (and (seq? x) (= ::dynref (first x))))

(defn dynamic?
  "Does `form` contain any dynamic ref?"
  [form]
  (let [acc (volatile! false)]
    (walk/postwalk (fn [x] (when (dynref? x) (vreset! acc true)) x) form)
    @acc))

(def MAX-RANGE-CELLS
  "Cap on how many cells a STATIC range (`$A1:D9`, `#cells A1:D9`) may expand to
   at read time. Ranges expand eagerly into one ref marker per cell, so a typo'd
   `$A1:ZZ99999` would build millions of markers (and that many `await`s in one
   body) before anything could reject it. Mirrors `rt/MAX-DYN-RANGE`, which caps
   the dynamic `$(…)` path the same way."
  10000)

(defn- expand-range
  "`(vector <ref> …)` for the inclusive rectangle `a`..`b`, refusing anything
   over `MAX-RANGE-CELLS`. The size is computed from the corner INDICES first —
   counting after materializing the addresses would already have paid the cost
   we're trying to refuse."
  [a b]
  (let [{ca :ci ra :ri} (addr/parse a)
        {cb :ci rb :ri} (addr/parse b)
        n (* (inc (- (max ca cb) (min ca cb)))
             (inc (- (max ra rb) (min ra rb))))]
    (when (> n MAX-RANGE-CELLS)
      (throw (ex-info (str "range " a ":" b " covers " n " cells (max "
                           MAX-RANGE-CELLS ") — reference a smaller rectangle")
                      {:range [a b] :cells n})))
    (cons 'vector (map ref-marker (addr/range-cells a b)))))

(def ^:private readers
  {;; #cell A1 -> (::ref "A1")
   'cell  (fn [sym] (ref-marker (str sym)))
   ;; #cells A1:A3 -> (vector (::ref "A1") (::ref "A2") (::ref "A3"))
   ;; #cells A1:C1 -> (vector (::ref "A1") (::ref "B1") (::ref "C1"))  (rectangle)
   'cells (fn [sym]
            (let [[a b] (str/split (str sym) #":")]
              (expand-range a b)))})

(defn deps
  "Cell addresses referenced by a marker form."
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk (fn [x] (when (ref? x) (vswap! acc conj (second x))) x) form)
    @acc))

;; A bare `$`-prefixed A1 address is terse sugar for a reader tag:
;;   $A1     <=> #cell  A1
;;   $A3:D8  <=> #cells A3:D8
;; These read as ordinary symbols (`$A1`, `$A3:D8`) so we rewrite them on the
;; PARSED form (not the string) — that way a `$A1` inside a string literal is
;; untouched. `$` is a terse cell ref here (relative, shifts on paste like the
;; reader tags), NOT an Excel-style absolute marker.
(def ^:private dollar-ref-re #"\$([A-Za-z]+[0-9]+)(?::([A-Za-z]+[0-9]+))?")

(defn- dollar-ref
  "Marker form for a `$`-cell symbol (`$A1` -> a ref; `$A3:D8` -> a vector of
   refs like #cells), or nil if `x` isn't one."
  [x]
  (when (symbol? x)
    (when-let [[_ a b] (re-matches dollar-ref-re (name x))]
      (if b
        (expand-range a b)
        (ref-marker a)))))

;; A RELATIVE `$`-ref names a cell by OFFSET from the owner cell, so it survives
;; copy/paste unchanged (re-resolved per destination) — the inverse of the
;; absolute `$A1` sugar above. Syntax `$<col><row>` where each part is `_` (same
;; index), `+N`, or `-N`. e.g. in B3: `$_-1` -> B2, `$-2_` -> the cell two cols
;; left, `$+1-1` -> one col right & one row up. Disjoint from `$A1` (those start
;; with a column LETTER). `shift-refs` deliberately leaves these alone.
(def ^:private rel-ref-re #"\$(_|[-+]\d+)(_|[-+]\d+)")

(defn- rel-coord [part base]
  (if (= "_" part) base (+ base (Long/parseLong part))))   ; parseLong accepts a leading +

(defn- rel-ref
  "Marker for a relative `$`-ref symbol resolved against owner `self`, or nil if
   `x` isn't one. Throws when the offset lands off the grid (negative col/row)."
  [x self]
  (when (and self (symbol? x))
    (when-let [[_ c r] (re-matches rel-ref-re (name x))]
      (let [{:keys [ci ri]} (addr/parse self)
            nc (rel-coord c ci)
            nr (rel-coord r ri)]
        (if (and (>= nc 0) (>= nr 0))
          (ref-marker (addr/make nc nr))
          (throw (ex-info (str "relative reference $" c r " off the grid from " self)
                          {:self self})))))))

;; `$(expr)` is a DYNAMIC ref: the expression's runtime value names the cell
;; (or range) to read. The reader sees it as TWO forms — the symbol `$`
;; followed by a list — so a structural pass fuses adjacent `$` + list pairs
;; into dynref markers bottom-up (inner `$(…)` fuse before the level above,
;; so nesting works). Runs on parsed forms: `$(` inside a string is untouched.
(defn- fuse-adjacent
  "Fuse `$`+list pairs among already-recursed sibling `elems`."
  [elems]
  (loop [es (seq elems) out []]
    (if es
      (let [e (first es) nxt (second es)]
        (if (= '$ e)
          (if (seq? nxt)
            (recur (nnext es) (conj out (dynref-marker nxt)))
            (throw (ex-info "dangling $ — write $(expression)" {:next nxt})))
          (recur (next es) (conj out e))))
      out)))

(defn- fuse-dynrefs [x]
  (cond
    (seq? x)    (apply list (fuse-adjacent (map fuse-dynrefs x)))
    (vector? x) (vec (fuse-adjacent (map fuse-dynrefs x)))
    (map? x)    (into {} (map (fn [[k v]] [(fuse-dynrefs k) (fuse-dynrefs v)]) x))
    (set? x)    (into #{} (map fuse-dynrefs x))
    :else       x))

(defn parse
  "Formula string (without leading =) -> {:form :deps}.

   Bare `$A1` / `$A3:D8` symbols are terse sugar for `#cell A1` / `#cells A3:D8`
   (see `dollar-ref`) — usable in any formula. `$(expr)` is a DYNAMIC ref
   (see `dynref-marker`); refs inside its expression are ordinary static deps
   (they drive re-resolution), the computed target is not.

   The source is read wrapped in parens (so a top-level `$(…)` — two reader
   forms — survives), fused, and must then be EXACTLY ONE form. That also
   rejects trailing junk edn/read-string used to ignore silently.

   With `self` (the owner address, e.g. for a STYLE/FORMAT property), the bare
   symbol `$val` rewrites to a ref on the owner's own value — sugar for
   `#cell <self>`. So a style reads the cell's current value reactively (an
   `await` edge) without retyping the address. `$val` is only meaningful where
   an owner exists; in a plain value formula it stays an unknown symbol and SCI
   rejects it at compile."
  ([s] (parse s nil))
  ([s self]
   (let [forms (fuse-dynrefs (edn/read-string {:readers readers}
                                              (str "(" s "\n)")))
         _     (walk/postwalk
                (fn [x] (if (= '$ x)
                          (throw (ex-info "dangling $ — write $(expression)" {}))
                          x))
                forms)
         _     (when (not= 1 (count forms))
                 (throw (ex-info "malformed formula (expected one expression)"
                                 {:forms (count forms)})))
         form  (walk/postwalk
                (fn [x]
                  (cond
                    (and self (= '$val x)) (ref-marker self)
                    :else                  (or (rel-ref x self) (dollar-ref x) x)))
                (first forms))]
     {:form form :deps (deps form)})))

;; --- unparse (inverse of parse) ------------------------------------------

(defn range-marker
  "Marker for a rectangular range, used when BUILDING forms for `unparse`
   (e.g. by an importer): renders as `$A1:B2`. `parse` never emits this — it
   expands ranges to a (vector …) of refs at read time — so unparsing a range
   marker and re-parsing yields the EXPANDED vector form (asymmetric by
   design)."
  [a b] (list ::range a b))

(defn range-ref?
  "Is `x` an unparse-side range marker?"
  [x] (and (seq? x) (= ::range (first x))))

(defn- vector-range
  "When `x` is a (vector …) seq of ≥2 ref markers whose addresses form a FULL
   row-major rectangle (exactly what a `$A1:B2` range parses to), the
   [top-left bottom-right] corner pair — else nil."
  [x]
  (when (and (seq? x) (= 'vector (first x)))
    (let [ms (rest x)]
      (when (and (<= 2 (count ms)) (every? ref? ms))
        (let [as (mapv second ms)
              ps (mapv addr/parse as)
              tl (addr/make (reduce min (map :ci ps)) (reduce min (map :ri ps)))
              br (addr/make (reduce max (map :ci ps)) (reduce max (map :ri ps)))]
          (when (= as (addr/range-cells tl br))
            [tl br]))))))

(defn unparse
  "Marker form -> formula source WITHOUT the leading `=` — the inverse of
   `parse`. Cell refs render as the terse `$` sugar: (::ref \"A1\") -> $A1; a
   (vector …) of refs forming a full rectangle re-collapses to $A1:B2; a range
   marker (see `range-marker`) renders as $A1:B2 directly. Everything else
   prints as EDN, so for any form in the image of `parse`:
     (= form (:form (parse (unparse form))))."
  [form]
  (cond
    (ref? form)       (str "$" (second form))
    (dynref? form)    (str "$" (unparse (second form)))
    (range-ref? form) (str "$" (nth form 1) ":" (nth form 2))
    (seq? form)       (if-let [[tl br] (vector-range form)]
                        (str "$" tl ":" br)
                        (str "(" (str/join " " (map unparse form)) ")"))
    (vector? form)    (str "[" (str/join " " (map unparse form)) "]")
    (map? form)       (str "{" (str/join ", " (map (fn [[k v]]
                                                     (str (unparse k) " " (unparse v)))
                                                   form)) "}")
    (set? form)       (str "#{" (str/join " " (map unparse form)) "}")
    :else             (pr-str form)))

;; --- inline (flatten a formula over its dependencies) --------------------

(defn- all-syms
  "Every symbol occurring anywhere in `form`."
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (vswap! acc conj x)) x) form)
    @acc))

(defn- destructured-syms
  "All symbols inside one binding FORM (a symbol, or a destructuring
   vector/map) — the names it binds."
  [b]
  (let [acc (volatile! #{})]
    (walk/postwalk (fn [x] (when (and (symbol? x) (not= '& x)) (vswap! acc conj x)) x) b)
    @acc))

(defn- binding-vec-syms
  "Binder symbols of a let/loop/for-style binding VECTOR [b v b v …], incl.
   destructuring and for/doseq `:let [b v …]` sub-vectors (other keyword
   modifiers bind nothing)."
  [bv]
  (reduce (fn [acc [b v]]
            (cond
              (= :let b)   (into acc (binding-vec-syms v))
              (keyword? b) acc                       ; :when / :while / …
              :else        (into acc (destructured-syms b))))
          #{} (partition 2 bv)))

(defn- fn-param-syms
  "Binder symbols of a (fn …) form: optional name + params of every arity."
  [x]
  (reduce (fn [acc el]
            (cond
              (symbol? el) (conj acc el)
              (vector? el) (into acc (destructured-syms el))
              (and (seq? el) (vector? (first el))) (into acc (destructured-syms (first el)))
              :else acc))
          #{} (rest x)))

(defn direct-binders
  "Symbols BOUND by form `x` itself (not by nested forms): let/loop/for/doseq/
   binding vectors, fn params/name, letfn fns, catch. Nil when `x` binds
   nothing. Shared with the simplifier (its rules must not fire on locally
   shadowed operator names)."
  [x]
  (when (and (seq? x) (symbol? (first x)))
    (let [h (name (first x))]
      (cond
        (and (#{"let" "let*" "loop" "loop*" "for" "doseq" "binding"} h)
             (vector? (second x)))
        (binding-vec-syms (second x))

        (#{"fn" "fn*"} h)
        (fn-param-syms x)

        (and (= "letfn" h) (vector? (second x)))
        (reduce (fn [acc f] (if (seq? f) (into acc (fn-param-syms (cons 'fn f))) acc))
                #{} (second x))

        (and (= "catch" h) (symbol? (nth x 2 nil)))
        #{(nth x 2)}))))

(def max-inline-nodes
  "Refuse to inline past this many nodes — a flattened formula bigger than this
   isn't readable anyway, and the cap also bounds the walk defensively."
  5000)

(defn- inline*
  [x form-of scope n]
  (when (< max-inline-nodes (vswap! n inc))
    (throw (ex-info "too large to flatten" {:size @n})))
  (cond
    ;; a dynamic ref is opaque: its target is a runtime value, nothing to
    ;; inline (its ADDRESS expression rides along verbatim)
    (dynref? x) x

    (ref? x)
    (if-let [body (form-of (second x))]
      (let [clash (set/intersection scope (all-syms body))]
        (if (seq clash)
          (throw (ex-info (str "flatten would capture " (str/join ", " (sort (map str clash)))
                               " — bound in an enclosing formula and used by "
                               (second x))
                          {:collisions clash :addr (second x)}))
          (inline* body form-of scope n)))
      x)

    (seq? x)
    (let [scope (into scope (direct-binders x))]
      (apply list (map #(inline* % form-of scope n) x)))

    (vector? x) (mapv #(inline* % form-of scope n) x)
    (map? x)    (into {} (map (fn [[k v]] [(inline* k form-of scope n)
                                           (inline* v form-of scope n)]) x))
    (set? x)    (into #{} (map #(inline* % form-of scope n) x))
    :else x))

(defn inline
  "Recursively substitute every ref marker whose target is a FORMULA cell with
   that cell's parsed form; refs to literal/blank cells stay leaf markers.
   `form-of` maps addr -> marker form for formula cells, nil otherwise. Sheets
   are DAGs (cycles are rejected at install), so the substitution terminates —
   and `max-inline-nodes` bounds it defensively anyway.

   Hygiene: refuses (ex-info :collisions) when an inlined body would land under
   a binding form that binds one of the body's symbols. The check is
   conservative — it also refuses when the body binds that name itself, or when
   the binder only shadows it in a sibling position — a refusal never corrupts,
   it just asks the user to rename the binder."
  [form form-of]
  (let [fo (memoize form-of)]
    (inline* form fo #{} (volatile! 0))))

;; --- reference shifting (clipboard paste) -------------------------------

(defn- shift-addr [a dc dr]
  (let [{:keys [ci ri]} (addr/parse a)]
    (addr/make (max 0 (+ ci (long dc))) (max 0 (+ ri (long dr))))))

(defn shift-refs
  "Shift every cell reference in formula `src` by (dc, dr) cols/rows, clamped at
   A1 — so clipboard paste is RELATIVE: copy =(+ #cell A1 1) from B1 to B2 pastes
   =(+ #cell A2 1). Handles both the reader tags (#cell/#cells) and the terse
   `$A1`/`$A3:D8` sugar. Non-formula text is returned as-is; a zero shift is a
   no-op."
  [src dc dr]
  (if (or (nil? src) (and (zero? dc) (zero? dr)))
    src
    (-> src
        (str/replace #"#cells\s+([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                     (fn [[_ a b]] (str "#cells " (shift-addr a dc dr) ":" (shift-addr b dc dr))))
        (str/replace #"#cell\s+([A-Za-z]+[0-9]+)"
                     (fn [[_ a]] (str "#cell " (shift-addr a dc dr))))
        ;; $-sugar: range first, then a lone $A1 (the (?!:) keeps the single
        ;; pass from re-shifting the already-shifted left half of a $A3:D8 range)
        (str/replace #"\$([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                     (fn [[_ a b]] (str "$" (shift-addr a dc dr) ":" (shift-addr b dc dr))))
        (str/replace #"\$([A-Za-z]+[0-9]+)(?!:)"
                     (fn [[_ a]] (str "$" (shift-addr a dc dr)))))))

(defn- bump-addr
  "Shift `a`'s coordinate on `axis` (:row|:col) by `delta` IFF that coordinate is
   `>= at` (clamped ≥ 0); other coordinates / refs untouched. The conditional
   shift behind inserting/deleting a line."
  [a axis at delta]
  (let [{:keys [ci ri]} (addr/parse a)]
    (cond
      (and (= axis :col) (>= ci at)) (addr/make (max 0 (+ ci (long delta))) ri)
      (and (= axis :row) (>= ri at)) (addr/make ci (max 0 (+ ri (long delta))))
      :else a)))

(defn insert-shift
  "Rewrite cell references in formula `src` for a structural row/col change:
   insert a blank line (`delta` +1) or remove one (`delta` -1) at index `at` on
   `axis` (:row|:col). A ref endpoint whose coordinate on that axis is `>= at`
   moves by `delta`; the rest stay. Each `#cells` endpoint is rewritten
   independently, so a range straddling the line grows/shrinks. Handles the reader
   tags and the `$A1`/`$A3:D8` sugar; non-formula text / nil pass through.
   (delete's handling assumes the removed line carries no references TO it — true
   when undoing an insert of a blank line.)"
  [src axis at delta]
  (if (nil? src)
    src
    (let [b (fn [a] (bump-addr a axis at delta))]
      (-> src
          (str/replace #"#cells\s+([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a c]] (str "#cells " (b a) ":" (b c))))
          (str/replace #"#cell\s+([A-Za-z]+[0-9]+)"
                       (fn [[_ a]] (str "#cell " (b a))))
          (str/replace #"\$([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a c]] (str "$" (b a) ":" (b c))))
          (str/replace #"\$([A-Za-z]+[0-9]+)(?!:)"
                       (fn [[_ a]] (str "$" (b a))))))))

;; A DELETE is not an insert with a negative delta. Inserting a line can only
;; move the cell a reference points at; deleting one can DESTROY it, and the two
;; call for opposite treatment of a reference whose coordinate is exactly the
;; deleted line:
;;
;;   coord  < at   the target is before the line          -> unchanged
;;   coord == at   the target is GONE                     -> see below
;;   coord  > at   the target slid back one               -> shift by -1
;;
;; For a scalar ref the answer is the spreadsheet's oldest error: `#REF!`. Left
;; to `insert-shift`'s `>= at` rule it would instead shift to `at - 1` and
;; silently read a DIFFERENT cell — a formula that still computes and is quietly
;; wrong, which is the worst outcome available.
;;
;; A RANGE just gets shorter, and its two ends are NOT symmetric:
;;
;;   start  s -> s   when s <= at, else s-1   (a start on the line stays put; it
;;                                             now names the cell that slid in)
;;   end    e -> e-1 when e >= at, else e     (an end on the line steps back; the
;;                                             range's last real cell is at-1)
;;
;; So A1:A3 minus row 3 is A1:A2, while A3:A5 minus row 3 is A3:A4 — each loses
;; exactly the one cell that was deleted. When the new end lands before the new
;; start the range covered nothing but the deleted line, and it is `#REF!` too.
;; Endpoints are ordered before the rule is applied and put back in the order
;; they were written, so a range typed "backwards" (B5:A1) still works.

(defn- ref-error
  "The source text a destroyed reference is rewritten to. A call, so it parses
   and compiles like anything else and raises at evaluation with the address it
   lost — the cell shows #ERR and the toast names what was deleted."
  [what]
  (str "(deleted-ref \"" what "\")"))

(defn- coord-of [a axis]
  (let [{:keys [ci ri]} (addr/parse a)] (if (= axis :col) ci ri)))

(defn- with-coord
  "`a` with its `axis` coordinate replaced by `n`."
  [a axis n]
  (let [{:keys [ci ri]} (addr/parse a)]
    (if (= axis :col) (addr/make (max 0 (long n)) ri) (addr/make ci (max 0 (long n))))))

(defn delete-shift
  "Rewrite cell references in formula `src` for the DELETION of the line at index
   `at` on `axis` (:row|:col). References to cells that no longer exist become
   `#REF!` errors rather than silently re-pointing at their neighbours; ranges
   shrink by exactly the one cell removed. Non-formula text / nil pass through.
   See the section note above."
  [src axis at]
  (if (nil? src)
    src
    (let [at    (long at)
          range-rw
          (fn [tag a b]
            (let [ca (coord-of a axis) cb (coord-of b axis)
                  lo (min ca cb)       hi (max ca cb)
                  lo' (if (<= lo at) lo (dec lo))
                  hi' (if (>= hi at) (dec hi) hi)]
              (if (< hi' lo')
                (ref-error (str a ":" b))          ; the range was only that line
                (let [c->n (fn [c] (if (= c lo) lo' hi'))]
                  (str tag (with-coord a axis (c->n ca))
                       ":" (with-coord b axis (c->n cb)))))))
          cell-rw
          (fn [tag a]
            (let [c (coord-of a axis)]
              (cond
                (= c at) (ref-error a)              ; the cell itself is gone
                (> c at) (str tag (with-coord a axis (dec c)))
                :else    (str tag a))))]
      (-> src
          (str/replace #"#cells\s+([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a b]] (range-rw "#cells " a b)))
          (str/replace #"#cell\s+([A-Za-z]+[0-9]+)"
                       (fn [[_ a]] (cell-rw "#cell " a)))
          (str/replace #"\$([A-Za-z]+[0-9]+):([A-Za-z]+[0-9]+)"
                       (fn [[_ a b]] (range-rw "$" a b)))
          (str/replace #"\$([A-Za-z]+[0-9]+)(?!:)"
                       (fn [[_ a]] (cell-rw "$" a)))))))

;; --- SCI sandbox + stdlib ----------------------------------------------
;; SCI runs the user expression in a curated, side-effect-free subset of
;; clojure.core (real lexical scope, NO host interop the user can reach). On top
;; we merge a spreadsheet stdlib (math / stats / text / date): plain host fns
;; exposed by name, callable bare from any formula. Names are chosen NOT to
;; shadow clojure.core (so map/reduce/str/… keep their meaning). Each sheet gets
;; its OWN context (isolation) built from this base plus the sheet's user `defs`
;; — see `new-ctx` (ROADMAP item 2: per-sheet namespace + functions).

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

(def stdlib
  "Predefined functions merged into clojure.core for every formula sandbox.
   Grouped by category; all pure, none shadowing a clojure.core name."
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
   'if-error     (fn [thunk fallback] (try (thunk) (catch Throwable _ fallback)))
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
   ;; what a reference is rewritten to when the row/column it pointed at is
   ;; deleted (see `delete-shift`) — always throws, naming what was lost
   'deleted-ref (fn [what]
                  (throw (ex-info (str "#REF! — " what " was deleted") {:ref what})))
   ;; I/O (see no-io): clear "not available" instead of an opaque cast crash
   'println no-io 'print no-io 'prn no-io 'pr no-io 'printf no-io
   'newline no-io 'flush no-io 'read no-io 'read-line no-io})

(defn new-ctx
  "A fresh per-sheet SCI context: the stdlib merged into clojure.core, then the
   sheet's user `defs` (a string of top-level forms, e.g. (defn …)) evaluated
   into its namespace, so cells in that sheet can call them. Throws if `defs`
   doesn't evaluate — the caller surfaces it and leaves the sheet unchanged.
   `defs` may be nil/blank (just the stdlib)."
  [defs]
  (let [ctx (sci/init {:namespaces {'clojure.core stdlib}})]
    (when-not (str/blank? defs)
      (sci/eval-string* ctx defs))
    ctx))

(defn- sci-fn
  "Compile the pure user `body` (markers already replaced by the value `syms`)
   to a host-callable fn of those values, sandboxed by SCI `ctx`."
  [ctx syms body]
  (sci/eval-form (sci/fork ctx) (list 'fn (vec syms) body)))

;; --- compile ------------------------------------------------------------

(defn- dyn-bindings
  "Emitted let-binding pairs for ONE dynamic ref site: compute the address
   string (SCI), resolve/validate it, then a loop awaiting each resolved cell —
   one iteration per cell, threading `vm` (addr -> value awaited so far) so a
   collision is served by `rt/lookup-dyn` as a fresh const-spin instead of a
   second await of a shared node. `d` unwraps to a scalar when the string named
   a single cell (\"A5\"), stays a row-major vector for a range (\"A1:B3\") —
   mirroring `$A5` vs `$A5:A5`. Returns [binding-pairs d-sym]; `vmap` (the
   running addr->value map) is read and re-shadowed by each site in turn."
  [self k afn-args {:keys [sym]}]
  (let [vm  (gensym "vm_") a (gensym "a_") res (gensym "res_")
        raw (gensym "raw_") as (gensym "as_") acc (gensym "acc_")
        v   (gensym "v_")]
    [[a    (list* (list 'nth 'afns k) afn-args)
      res  (list 'uno.michelada.saltrim.runtime/resolve-dyn a)
      raw  (list 'loop [as (list :addrs res) vm 'vmap acc []]
                 (list 'if (list 'seq as)
                       (list 'let [v (list 'await
                                           (list 'uno.michelada.saltrim.runtime/lookup-dyn
                                                 self (list 'first as) vm))]
                             (list 'recur (list 'rest as)
                                   (list 'assoc vm (list 'first as) v)
                                   (list 'conj acc v)))
                       acc))
      sym  (list 'if (list :single? res) (list 'nth raw 0) raw)
      'vmap (list 'merge 'vmap (list 'zipmap (list :addrs res) raw))]
     sym]))

(defn compile
  "Marker form -> Spin, using the sheet's SCI `ctx` (stdlib + user defs). SCI-
   compiles the user body over resolved cell values; the spin awaits each
   distinct referenced cell once (de-dup) and calls the SCI fn. SCI never sees
   spin/await/track/lookup.

   `self` (the owner address) is required when the form contains dynamic
   `$(…)` refs: each site's address expression is SCI-compiled over the static
   values (plus earlier dynamic results — postwalk order is innermost-first,
   so nesting works), and the emitted body resolves + awaits its cells via
   `rt/lookup-dyn`, which cycle-checks and records the dynamic edges under
   `self`. The 2-arity form (no owner) rejects dynamic refs — style/format
   formulas compile through it."
  ([ctx form]
   (when (dynamic? form)
     (throw (ex-info "dynamic $(…) refs aren't supported in style/format formulas"
                     {})))
   (compile ctx form nil))
  ([ctx form self]
   (let [addrs (vec (deps form))
         syms  (mapv (fn [_] (gensym "c_")) addrs)
         a->s  (zipmap addrs syms)
         dyns* (volatile! [])
         body  (walk/postwalk
                (fn [x]
                  (cond
                    (ref? x)    (a->s (second x))
                    (dynref? x) (let [ds (gensym "d_")]
                                  (vswap! dyns* conj {:sym ds :inner (second x)})
                                  ds)
                    :else x))
                form)
         dyns  @dyns*
         bnds  (vec (mapcat (fn [a s]
                              [s (list 'await (list 'uno.michelada.saltrim.runtime/lookup a))])
                            addrs syms))]
     (if (empty? dyns)
       ;; static-only: exactly the pre-dynref shape
       (let [user-fn (sci-fn ctx syms body)
             ;; eval a factory (fn [uf] (spin (let [<awaits>] (uf <syms>)))) in
             ;; this ns so spin/await resolve and CPS sees the effects; then
             ;; close over uf.
             factory (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
                       (eval (list 'fn ['uf]
                                   (list 'spin (list 'let bnds (list* 'uf syms))))))]
         (factory user-fn))
       (let [_ (when-not self
                 (throw (ex-info "dynamic $(…) ref needs an owner cell" {})))
             ;; afn k computes site k's address string from the static values
             ;; and the dynamic results of earlier (inner) sites
             afns  (mapv (fn [k {:keys [inner]}]
                           (sci-fn ctx (into syms (map :sym (take k dyns))) inner))
                         (range) dyns)
             all   (into syms (map :sym dyns))
             user-fn (sci-fn ctx all body)
             dbnds (loop [k 0, args (vec syms), out ['vmap (zipmap addrs syms)]]
                     (if-let [d (nth dyns k nil)]
                       (let [[pairs dsym] (dyn-bindings self k args d)]
                         (recur (inc k) (conj args dsym) (into out pairs)))
                       out))
             factory (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
                       (eval (list 'fn ['uf 'afns]
                                   (list 'spin (list 'let (into bnds dbnds)
                                                     (list* 'uf all))))))]
         (factory user-fn afns))))))

(defn compile-literal-wrapper
  "Spin exposing a literal cell's editable signal as a public awaitable node:
   (spin (deref (track (lookup-val addr)))). Pure infra — host-compiled, no SCI."
  [addr]
  (binding [*ns* (find-ns 'uno.michelada.saltrim.formula)]
    (eval (list 'spin (list 'deref (list 'track
                                         (list 'uno.michelada.saltrim.runtime/lookup-val addr)))))))
