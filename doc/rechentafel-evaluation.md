# rechentafel → SaltRim: what to bring over

Evaluation of [`replikativ/rechentafel`](https://github.com/replikativ/rechentafel)
(Christian Weilbach, Apache-2.0, `org.replikativ/rechentafel` on Clojars) as a
source of ideas and code for SaltRim. Everything numeric below was measured
against a local clone at `c4b77e2`, not read off the README.

---

## 1. What rechentafel actually is

A **pure-Clojure Excel formula interpreter**, `.cljc` throughout (JVM + browser),
with no POI or LibreOffice at runtime. ~19.5k lines of source.

| ns | role |
|---|---|
| `lexer` (542) + `parser` (530) | Excel formula text → AST (Pratt parser) |
| `unparse` (256) | AST → canonical Excel formula text |
| `rc` (142) | R1C1 ↔ A1 normalization, for interning shared formulas |
| `address` (107) / `cell` | A1 ↔ [row col]; `(sheet,row,col)` packed into one long |
| `mtv` (244) | multi-typed **column** store per sheet |
| `eval` (1326) | dep graph, dirty set, topo recalc, spill/dynamic arrays |
| `value` (138) | tagged value model + Excel error codes |
| `functions` (529) | function registry + coercion/broadcast helpers |
| `fn/*` (~6.6k) | the function implementations, 12 category modules |
| `poi` / `poi_writer` (optional alias) | xlsx read / write, incl. dynamic-array metadata |

Verified facts (I ran these):

- **506 registered function names** (README says ~270; the extra are the
  `NORM.DIST`/`NORMDIST` style dotted/legacy aliases, each a real entry).
- **473 of those are "strict"** — plain `(fn [args] value)`, callable with no
  evaluator, no workbook, no dependency graph.
- **33 are "lazy"** (need the evaluator context): `IF IFS IFERROR IFNA SWITCH
  INDIRECT OFFSET CELL INFO SHEET SHEETS ROW COLUMN TYPE ERROR.TYPE FORMULATEXT
  IS*` predicates, and the LAMBDA helpers `MAP REDUCE SCAN BYROW BYCOL MAKEARRAY`.
  Nearly all of these SaltRim already has natively in Clojure/SCI.
- **9 are volatile**: `NOW TODAY RAND RANDARRAY RANDBETWEEN INDIRECT OFFSET CELL INFO`.
- Module coupling is clean: only `fn/array` and `fn/info` require the evaluator.
  `math stats text datetime financial engineering lookup logical database misc`
  depend on `value` + `functions` + `platform` (+ `rng`) only.
- Language features beyond the function list: `LET`, `LAMBDA` (incl. recursive
  named UDFs), dynamic arrays with **spill** (`#SPILL!` blocked detection,
  shape-shrink cleanup, dirty-anchor propagation), structured table refs
  (`Sales[Amount]`, `[#Headers]`/`[@col]`), 3D refs (`Sheet1:Sheet3!A1:B5`),
  `@` implicit intersection, `A1#` anchor-array, array broadcasting in binops,
  defined names.
- Testing rigour worth noting: a **POI parity oracle** (`test/rechentafel/oracle/`,
  ~1.4k lines) that runs each formula through both engines and diffs, plus a
  benchmark suite comparing against POI and LibreOffice headless on six workbook
  shapes, on both JVM and cljs.

### Where it does *not* fit SaltRim

Three deep mismatches — these decide the whole strategy:

1. **Recalc model.** rechentafel is pull-based: edit marks dirty, `recalc` does a
   topo sweep. SaltRim is push-based on Spindel: every cell is a Spin, edits
   propagate through the reactive graph asynchronously. These are not
   reconcilable, and SaltRim's model is the differentiating one (it's what makes
   live collaboration and per-cell push work).
2. **Value model.** rechentafel values are tagged maps `{:t :num :v 30.0}` with
   Excel coercion rules (blank→0, "TRUE"→bool, error propagation). SaltRim
   formulas are plain Clojure values — a number is a number, a blank is `nil`.
3. **Surface language.** rechentafel's user language *is* Excel. SaltRim's is
   Clojure. SaltRim should not become Excel-flavoured.

**Conclusion: don't take the engine. Take the libraries around it.** The
function pack, the parser/unparser, the error taxonomy and the test harnesses are
all usable without adopting a single line of `eval.cljc`.

---

## 2. Ranked list of what to bring over

### A. The function library, via a ~30-line adapter — **biggest win, lowest risk**

SaltRim's stdlib is ~55 functions (`formula/stdlib`). rechentafel gives 473
standalone-callable ones with Excel-verified semantics, including everything
SaltRim conspicuously lacks: the whole financial pack (`PV FV PMT NPER RATE NPV
IRR MIRR XIRR XNPV IPMT PPMT CUMIPMT SLN SYD DB DDB VDB EFFECT NOMINAL`), the
statistical distributions (`NORM.* T.* F.* CHISQ.* BINOM.* POISSON`, rank,
percentile, correlation, regression), engineering (`CONVERT ERF BESSEL`, complex
numbers, bit ops, `BIN2DEC` family), full text (`TEXTJOIN TEXTBEFORE TEXTAFTER
SUBSTITUTE NUMBERVALUE UNICHAR`), lookup (`VLOOKUP HLOOKUP XLOOKUP XMATCH INDEX
MATCH CHOOSE`), the `*IF`/`*IFS` aggregates, `SUMPRODUCT SUBTOTAL AGGREGATE`,
matrix (`MMULT MINVERSE TRANSPOSE`), and the database `D*` functions.

**This works today.** I ran it — plain-Clojure values in, plain-Clojure values
out, through a trivial adapter:

```clojure
(defn ->t [x] ...)   ; nil→{:t :blank}, number→{:t :num}, string, boolean,
                     ; sequential→{:t :area …}
(defn <-t [t] ...)   ; :num→long-if-integral, :err→throw, :area→flatten
(defn xl [nm & args] (<-t (f/call nm (mapv ->t args))))
```

Results from the spike:

```
registered: 506
XIRR:      0.5600590211284158
PMT:       149.02948869707532        ; (PMT 0.08 10 -1000)
NORM.DIST: 0.9087887181301249
TEXTJOIN:  "a, b, c"
CONVERT:   0.45359237                ; 1 lbm → kg
PERCENTILE: 9.1
SUMPRODUCT: 32
SQRT(-1) → #num   ; error codes surface as data, not a stack trace
NOPE(1)  → #name
VLOOKUP over a 2D area → 2.0
PMT × 100 000 calls: 109 ms  (≈1.1 µs/call — irrelevant next to a Spin hop)
```

**Integration sketch.** Generate stdlib entries at namespace load:

```clojure
(def excel-fns
  (into {} (for [nm (f/registered-names)
                 :when (not (:lazy? (f/lookup nm)))]
             [(symbol nm) (fn [& args] (xl nm args))])))
```

Merged into `formula/stdlib`, so `(NORM.DIST $A1 40 1.5 true)` just works in a
cell. Two things I checked so this doesn't bite:

- **SCI handles dotted uppercase symbols fine** — I verified `NORM.DIST`,
  `XLOOKUP`, `SUM` all resolve and call correctly inside a `sci/init` namespace
  map. No renaming needed.
- **No name collisions with SaltRim's stdlib**, which is entirely lowercase.
  `SUM`/`sum`, `MEDIAN`/`median` coexist — and that's arguably good: `sum` keeps
  SaltRim's blank-skipping Clojure semantics, `SUM` gets Excel's.

**Open decisions:**
- Uppercase-only (Excel names verbatim) is my recommendation — it makes the
  Excel-compat surface visually distinct from the native stdlib, and it's exactly
  what an importer or an Excel-literate user types.
- Errors: adapter currently throws (→ `#ERR`). See item **C** — returning typed
  error values is strictly better and this is the moment to decide.
- Autocomplete/help: the registry knows arity but has **no docstrings**. Worth
  asking upstream for `:doc`/`:args` metadata (see §5) — SaltRim's `ƒ`/help
  modals would use it directly.

**Effort:** small (one PR). **Risk:** low. The only real work is the adapter's
edge cases (dates — rechentafel uses Excel serial numbers, SaltRim uses ISO
`yyyy-MM-dd` strings; see item **F**).

### B. The Excel formula parser + unparser — replaces the POI RPN hack, unlocks live xlsx export

`xlsx.clj`'s importer currently walks **POI's RPN `Ptg` token stream** with a
hand-written stack machine, and its own docstring documents how sharp that is:
`AttrPtg(isSum)` popping one operand, `IF`/`CHOOSE` arriving as trailing
`FuncVarPtg`, "future functions" arriving as `NameXPxg` + a `#external#`
`FuncVarPtg` whose operand count includes the name token. Whole-column ranges,
cross-sheet refs and named ranges are all `unsupported!` → demoted to cached
values with an audit `:comment`.

rechentafel's `parser/parse` takes the **formula string** and returns a clean
AST. I parsed a deliberately nasty one:

```clojure
(p/parse "=IF(SUM(Sales[Amount])>10,VLOOKUP($A1,Sheet2!B:D,3,FALSE),\"x\"&A1#)")
```

and got structured nodes for the table ref, the absolute `$A1`, the whole-column
cross-sheet range `Sheet2!B:D`, and the `A1#` spill ref — all the cases the RPN
walker gives up on. Rewriting the translator as an AST walk instead of a stack
machine would be shorter, more readable, and would let those cases become real
translations instead of demotions.

Two further things this buys:

1. **"Paste an Excel formula" input mode.** Type/paste `=VLOOKUP(A1,B:D,3,FALSE)`
   into a SaltRim cell and get the Clojure equivalent. Sharp onboarding feature
   for spreadsheet users, and reuses the exact same translator as the importer.
2. **Live xlsx export.** SaltRim's export is a static computed-values snapshot —
   the documented #1 limitation (`export.clj`, warned about in the help + README).
   The reverse translation (SaltRim marker form → Excel AST → `unparse` → POI
   `setCellFormula`) makes exported workbooks *live in Excel*. `unparse` already
   emits canonical Excel text (I ran it: `(u/unparse (p/parse "=let(x,1,x+A1)"))`
   → `LET(x,1,x+A1)`), and `poi_writer.clj` is a worked example of writing
   formulas — including the `cm="1"` + `xl/metadata.xml` trick needed for Excel
   365 to accept dynamic arrays rather than CSE arrays.

   > **Update — B2 shipped** as the `xlformula` ns. `unparse` turned out to
   > accept hand-built AST maps, not just ones it parsed, so precedence,
   > parenthesisation, string escaping and `$`-absolute refs all came free. Two
   > things the estimate did not anticipate:
   >
   > - **`formula/parse` expands `$A1:A3` into per-cell refs and documents that
   >   it never comes back**, so export has to re-fold the rectangle itself
   >   (`refs->range`, checked exactly against `addr/range-cells`). That is not
   >   cosmetic — `SUM(A1,…,A500)` breaches Excel's 8192-character formula limit,
   >   and a formula Excel rejects costs the whole FILE, not the cell.
   > - **An erroring cell must NOT export live even when it translates.** Excel
   >   may well compute a different answer from the same formula, and an export
   >   that silently disagrees with the sheet it came from is worse than one that
   >   does not recalculate.
   >
   > The round trip is pinned rather than assumed: 29 formulas go out through the
   > exporter and back through the importer and must come back identical, and the
   > written file is handed to POI's own Excel formula engine, which must agree
   > with SaltRim cell for cell.

Only the mappable subset round-trips, of course — a cell calling a user `def`
chunk can't become an Excel formula and must still demote to its value. But the
mappable subset is most of a typical imported sheet.

**Effort:** medium (import rewrite), medium-large (export). **Risk:** medium —
this is the item where SaltRim's own `formula/unparse` and rechentafel's AST have
to meet in the middle, and it should be spiked first.

> **Update — B1 shipped** (spike: `spikes/11-excel-ast-import.clj`). The rewrite
> is behaviour-identical on all 22 translatable formulas in the existing test
> battery, deletes the stack machine and its three token-order hazards, drops
> the `FormulaParsingWorkbook`/sheet-index plumbing, and adds `LET` and array
> constants as new translations.
>
> **On the "fewer demotions" claim.** The AST rewrite by itself did not reduce
> demotions at all — a REFERENCE it cannot place is still a reference it cannot
> place. Cross-sheet refs, whole-column ranges, defined names, table refs and
> spill refs all still demote: named regions are item K, spill is item E, a whole
> column is ~1M cells against a 4096-cell range cap, and a cross-sheet ref has no
> target because each Excel sheet imports as its own SaltRim sheet.
>
> What DID cut demotions, in the following PR, was FUNCTIONS — and the AST is
> what made the gap obvious. The importer knew ~30 Excel functions by hand while
> the stdlib had borrowed 213 and `xl/` exposed 414, and `xl/` had been
> documented from the start as the reason an imported formula stays live instead
> of demoting to a dead cached number. The importer simply never reached for it.
> Two table lookups later, a workbook of `PMT` / `SUMIF` / `STDEV.P` /
> `GEOMEAN` / `SUMPRODUCT` / `TRANSPOSE` imports with zero demotions where every
> cell used to demote.
>
> The AST rewrite's own wins are the deleted stack machine, the accurate refusal
> reasons, and B2 becoming reachable.

### C. Excel's error taxonomy as values, not exceptions

`value.cljc` defines `:null :div0 :value :ref :name :num :na :getting-data`,
plus the modern `:spill` and `:calc`. Coercion (`to-num`/`to-str`/`to-bool`) and
the `call` dispatcher propagate errors first-arg-wins, which is what makes
`IFERROR(A1,…)` catch A1's error rather than swallow the formula.

SaltRim currently throws and renders a single opaque `#ERR`. Adopting even a
subset (`#DIV/0! #VALUE! #REF! #NAME? #NUM! #N/A`) gives:

- a cell display that says *which* failure it is,
- a real `IFNA`/`IFERROR` distinction (`if-error` today catches everything,
  including genuine bugs),
- something for **cell assertions** to test against — an assertion that a cell
  isn't `#N/A` is more useful than one that it "didn't throw",
- a natural fit with the existing `deleted-ref` → `#REF!` behaviour, which is
  already reaching for this taxonomy informally.

**Effort:** small-medium. **Risk:** low, but it touches the sheet layer's
error handling and the renderer. Worth doing *with* item A so the adapter maps
error values instead of throwing.

### D. Ranges should carry their shape (2D), not flatten

This is a structural finding rather than a copy-paste item, and it gates how far
A and B can go.

SaltRim ranges expand statically to `(vector #cell … #cell …)` — a **flat
row-major vector**, shape lost at runtime. That's why `xvlookup` needs the table
width passed in as an explicit `w` argument. rechentafel's `:area` value carries
`{:r0 :c0 :r1 :c1 :values [[…] […]]}`, which is what lets `VLOOKUP INDEX MATCH
MMULT TRANSPOSE SUMPRODUCT` and every array function work naturally, and what
makes binop broadcasting (`A1:A3 * B1:B3`) definable at all.

Recommendation: give SaltRim's range values a shape — either a vector-of-rows or
a flat vector with `{:rows :cols}` metadata (metadata is cheaper and preserves
existing seq-based stdlib call sites; but it's lost through `map`/`filter`, so
vector-of-rows with a `flat` helper is probably the honest choice). Do this
*before* wiring the Excel function pack, or you'll wire it twice.

**Effort:** medium. **Risk:** medium — touches `formula` range expansion, the
stdlib aggregates, `xlsx.clj`, and any user formula that assumed a flat seq. It
is a breaking change to saved formulas' runtime shape, so it needs a considered
migration (probably: keep flat as the default and add a `#area`/2D form).

> **Update — D shipped**, as exactly the additive migration this paragraph
> guessed at: `$A1:B2` stays flat row-major and `#area A1:B2` is new. Nothing
> saved changed meaning and no stdlib aggregate was touched.
>
> Two corrections to the analysis above. First, **`excel/->rv` already turned a
> collection-of-collections into a 2D area** — the adapter was never the missing
> piece, the LANGUAGE was: there was no way to write a nested range, so you had
> to spell `(vector $A1:B1 $A2:B2)` row by row or call `xl/as-rows`.
>
> Second, this was not only an enabler for future array functions, it was a live
> CORRECTNESS bug, and the preceding PR had just widened it. Making all ~414
> `xl/` functions mechanically reachable meant `TRANSPOSE`, `INDEX`, `MDETERM`,
> `MINVERSE` and the `LINEST` family were all reachable with a flat column,
> where they answer for the wrong rectangle without erroring. Imports were
> protected — `demote-verify!` compares against Excel's cache and demoted them —
> but hand-written formulas had no such oracle. `INDEX(A1:B2,2,1)` and
> `MDETERM(A1:B2)` now import LIVE and correct instead of demoting.

### E. Dynamic arrays / spill — a ready-made blueprint for a future feature

`eval.cljc` implements the Excel-365 spill model: `wb[:spills]` maps
anchor→rectangle; `spill-blocked?` detects collisions and yields `#SPILL!`;
shape shrink cleans up the vacated cells; an edit anywhere inside a spill
rectangle re-dirties the anchor so a previously-blocked spill can re-attempt;
`A1#` reads the live shape. Function-side, `fn/array.cljc` (552 lines) has the
whole 365 array set — `SEQUENCE RANDARRAY UNIQUE SORT SORTBY FILTER HSTACK VSTACK
CHOOSEROWS CHOOSECOLS DROP TAKE EXPAND TOROW TOCOL WRAPROWS WRAPCOLS MUNIT`.

SaltRim has a natural precedent: **merged cells** already model
anchor + rectangle + hidden covered cells, with the span riding the ordinary
cellprop plumbing. Spill is the same geometry with a *computed* rather than
stored extent. It also composes well with SaltRim's reactivity — a spill anchor
whose formula's result shrinks is exactly a structural rebuild, which
`set-cell!` already knows how to do for dynamic refs.

Complications SaltRim would have to solve that rechentafel doesn't: spilled
cells are derived, so they must not persist as datoms (they'd fight branch/merge/
as-of); collaboration must broadcast the whole rectangle; and the window
renderer must treat a spill like a merge block.

**Effort:** large. **Risk:** high. But it's a roadmap-grade feature with a
reference implementation to read, and dynamic arrays are the single biggest
"modern spreadsheet" capability SaltRim lacks.

### F. Volatile functions — an unnoticed gap in SaltRim

rechentafel flags 9 functions volatile and re-dirties them on every recalc.
SaltRim's `today` is in the stdlib but **nothing re-fires it** — a sheet left
open across midnight, or reloaded from the db months later, will show whatever
`today` evaluated to when the cell was last structurally rebuilt. That's a
correctness bug waiting to be reported, not a missing feature.

The fix in a push engine isn't rechentafel's (there is no recalc sweep to hook),
but its taxonomy is the useful part: mark volatile functions, then decide a
policy — recompute volatile cells on sheet load, on a coarse timer, or refuse
`today`/`now` in favour of an explicit "as of" input cell. Related: rechentafel's
`rng.cljc` is a seedable xorshift64* (`(assoc wb :rng-seed 42)`) that produces
the *same sequence on JVM and cljs*. If SaltRim ever adds `RAND`, that design —
determinism as an opt-in workbook property — is the right one, and matters more
for SaltRim than for rechentafel because SaltRim persists, branches and merges:
a non-deterministic cell makes 3-way merge meaningless.

**Effort:** small (audit + policy). **Risk:** low. Worth an issue now.

### G. The POI parity oracle — test method worth copying

`test/rechentafel/oracle/` runs every formula under test through **both**
rechentafel and POI's `FormulaEvaluator` and diffs with a tolerance. That's how
they justify claiming Excel semantics rather than asserting it.

SaltRim already does a per-import **demote-and-verify** pass (translated cell vs
Excel's cached value), which is the same idea applied at runtime. The missing
piece is the *offline* version: a test suite that pins SaltRim's `excel-truthy`,
`xround`, `xvlookup`, `xmin`/`xmax` and the future Excel function pack against
POI on a corpus of formulas. rechentafel also bundles Apache POI's own
`FormulaEvalTestData.xls` fixtures (with proper NOTICE attribution) — that corpus
is reusable directly.

If item A lands, this becomes near-free: the functions are POI-cross-validated
upstream already, so SaltRim's oracle only needs to cover the *adapter*
(value conversion, blanks, dates, ranges), which is where the bugs will be.

**Effort:** small-medium. **Risk:** low. POI is already a SaltRim dependency.

### H. A benchmark harness for the engine

`bench/` defines six shapes — `chain` (long dep chain), `wide` (N independent),
`aggregate` (one big SUM), `star` (N readers of one anchor), `spill`,
`lambda-rec` — and runs them on JVM and cljs, with comparators against POI and
LibreOffice.

SaltRim has **no** performance benchmark for the Spindel engine, and its cost
profile is very different (a Spin per cell, async drain, `settle!` barrier). The
shapes are directly reusable as a fixture vocabulary; `star` and `chain` in
particular would answer questions SaltRim can't currently answer — e.g. what a
20k-cell import (the `max-cells` limit) actually costs to build and to settle
after a single edit, and how badly `set-cell!`'s structural rebuild of dynamic
dependents scales.

**Effort:** small. **Risk:** none. High information value per hour.

### I. Performance engineering, ranked by relevance to SaltRim

Mostly *not* worth copying, but two ideas map onto real SaltRim behaviour:

- **Parser cache** (`parser_cache.cljc`): bounded process-wide memo on formula
  source text, because filling a column with `=A2+1, =A3+1, …` re-parses N times
  for one distinct AST. SaltRim's paste-with-shift creates exactly this
  distribution. Cheap to add; measure first (SaltRim parses with `read-string` +
  regex desugaring, which may already be fast enough).
- **R1C1 normalization + AST interning** (`rc.cljc`): relative refs become
  offsets, so a filled-down column shares one AST object. Beyond memory, this is
  the honest data model for "the same formula, shifted" — which SaltRim
  approximates today by rewriting reference text on paste. If SaltRim ever adds
  *fill-down* (it doesn't have one), R1C1 is the right internal form.
- **Not relevant:** the MTV column store and the packed-long cell id. SaltRim's
  cells are per-property Datahike datoms keyed by A1 string, branch-aware — a
  columnar in-memory store solves a problem SaltRim doesn't have, and packed ids
  would fight the datom model.
- **Sector-bucketed reverse deps** (64×64 buckets, so a whole-column ref doesn't
  create a million rdep entries) only matters if SaltRim ever supports
  whole-column ranges. It currently rejects them at import (`max-range-cells`
  4096). Worth remembering *if* that limit is ever lifted.

### J. `.cljc` discipline — the door to client-side formula preview

rechentafel is `.cljc` throughout with one `platform.cljc` holding every reader
conditional, and datetime handled by `cljc.java-time` (java.time on JVM,
js-joda on cljs). SaltRim's stdlib is JVM-only (`java.time`, `BigDecimal`,
`Math/*`).

SaltRim already shares `addr`/`constants` as `.cljc` and its client is
ClojureScript — and **SCI has a cljs build**. So a genuinely interesting option
opens up: evaluate a formula *client-side* for instant preview while typing,
before the server round-trip confirms it. That needs a portable stdlib, which
needs the `platform.cljc` treatment. Not a near-term item, but it argues for
writing any new stdlib code as `.cljc` from the start rather than porting later.

### K. Structured table refs — steal the ergonomics, not the implementation

`Sales[Amount]`, `[#Headers]`, `[#Data]`, `[@col]` — a named region whose
columns are addressable by header name, so formulas read as prose and survive
row insertion. rechentafel implements this against Excel's ListObject model.

SaltRim doesn't need Excel's model, and arguably already has the raw material:
`:label` names a cell, the per-sheet `defs` library can hold named constants, and
`$(expr)` dynamic refs mean `(def sales "B2:B10")` + `$(sales)` is *already* a
named range. What's missing is the ergonomic layer — naming a **region** and
addressing it by column header. Given SaltRim's per-property datom model, a
`:table` prop on an anchor (exactly like `:merge`) plus header-name resolution in
the formula compiler would be a natural fit, and it composes with branching and
merge for free.

**Effort:** medium. **Risk:** low-medium. This is a "cheap win" candidate for the
roadmap, informed by rechentafel rather than copied from it.

> **Half DONE, and the design landed differently.** Defined names are in — but as
> an IMPORT-time resolution, not a new engine concept. Excel stores a name's
> target as a formula string of its own, so the translator calls itself on it:
> `Tax_Rate` → `Data!$B$1` → `#cell B1`, and ranges, expressions and
> names-over-names come free with every existing refusal intact.
>
> That is deliberately not the `:table` prop sketched above. The reason is
> reactivity's price: keeping the NAME in the formula means a runtime
> indirection, which in SaltRim is `$(…)`, and `set-cell!` structurally rebuilds
> every dynamic dependent on ANY edit — the `dyn` benchmark shape is 4x the
> others. Imposing that on every imported formula to preserve a label is a bad
> trade. For names a user writes themselves the opt-in already exists:
> `(def sales "B2:B10")` + `$(sales)`, which is what this item observed in the
> first place.
>
> Structured table refs (`Sales[Amount]`) are still open. They resolve the same
> way — POI's `XSSFTable` gives the area and the header names, so the rectangle
> is computable at import — and `[@col]` additionally needs the referring cell's
> row, which the importer has. That is the remaining half.

---

## 3. What NOT to take

- **`eval.cljc`** — the dirty-set/topo-recalc engine. Directly opposed to
  Spindel's push model; adopting it would mean abandoning what makes SaltRim
  collaborative and reactive.
- **`mtv.cljc` + `cell.cljc`** — column store and packed-long ids. Solves storage
  problems SaltRim solved differently (branch-aware datoms) and better for its
  use case.
- **Excel as the surface language.** The parser is an *import/export* tool and
  optionally a paste-mode convenience. SaltRim's pitch is that formulas are real
  Clojure; Excel syntax as a first-class input would undo that.
- **The `#N/A` stubs for bond math** (`PRICE YIELD COUP* DURATION AMOR*`) —
  registering a function that always returns `#N/A` is right for POI parity, but
  in SaltRim it's a trap: a user calls `PRICE` and gets a silent `#N/A` instead
  of "not implemented". Filter these out of the imported registry, or map them to
  an explicit "unsupported" error.

---

## 4. Licensing and dependency notes

- rechentafel is **Apache-2.0**; SaltRim is **MIT**. Depending on the artifact
  (`org.replikativ/rechentafel` from Clojars) is unproblematic — Apache-2.0 is
  permissive and imposes nothing on SaltRim's own source.
- **Copying source files into SaltRim is the messier path**: Apache-2.0 requires
  retaining the license/attribution notice for the copied files, which means
  SaltRim's tree becomes mixed-license and needs a NOTICE. Given the author is
  actively cooperating, **depend, don't vendor** — and upstream any fixes.
- Runtime dep footprint is small: Clojure + `cljc.java-time` (~a few hundred KB).
  POI is behind an optional `:poi` alias, and SaltRim already ships POI (~12 MB)
  for import/export, so no new weight there.
- The library is young: 5 commits, one author, initial public release, `0.1.x`.
  Treat the API as unstable and pin the version — the same discipline already
  applied to the Spindel 0.1.15 pin.

---

## 5. Cooperation: what to ask upstream, what to offer

Worth raising with the author, since the collaboration is welcome:

**Ask for:**
1. **A plain-value adapter in rechentafel itself** — `->value`/`<-value` +
   `call-plain`, so every non-Excel host embedding the function pack doesn't
   rewrite the same 30 lines. SaltRim would be the first consumer and can
   contribute the implementation.
2. **Docstrings + argument names in the registry** (`:doc`, `:args`). The
   registry knows arity but nothing human-readable; SaltRim wants it for
   autocomplete and the help modal, and every other embedder will too.
3. **Keeping `functions` + `fn/*` free of `eval`** as an explicit invariant. It's
   true today for 10 of 12 modules, and it is precisely what makes the function
   pack reusable. Worth a test that asserts it.
4. **A stable public API for `parse`/`unparse`** at the AST level, with the AST
   node shapes documented — that's the contract a translator depends on.
5. A **`:no-op`/`:unimplemented` marker** distinct from `#N/A` for the bond-math
   stubs, so embedders can filter or re-map them.

**Offer back:**
- Cross-validation from a *second* independent engine: SaltRim's importer runs a
  demote-and-verify pass against Excel's cached values on real workbooks — a
  useful corpus and a second opinion on semantics.
- SaltRim's `simplify`/`unparse`/flatten work (constant folding, associative
  flattening, literal-`if` pruning, hygiene-checked inlining) has no counterpart
  in rechentafel and would be a natural addition to an AST-based engine.
- Cycle-detection and dynamic-reference (`INDIRECT`-equivalent) experience — the
  reactive-engine trap SaltRim documented in `spikes/07` (stale await
  continuations on retarget) is relevant to anyone making rechentafel incremental.

---

## 6. Suggested sequence

| # | Item | Effort | Payoff | Notes |
|---|---|---|---|---|
| 1 | **A** — Excel function pack via adapter | S | Very high | Spike proved it works; ship behind an uppercase namespace |
| 2 | **C** — typed error values | S–M | High | Do with #1 so the adapter maps errors, not throws |
| 3 | **F** — volatile audit (`today` never refires) | S | Correctness | File as a bug now, independent of everything else |
| 4 | **H** — benchmark shapes for the Spindel engine | S | High info | Answers questions we currently guess at |
| 5 | **G** — offline POI oracle for the adapter | S–M | High | Reuses POI already on the classpath |
| 6 | **D** — 2D range shape | M | High | ✅ DONE as ADDITIVE `#area` — nothing breaking was needed |
| 7 | **B1** — importer: POI RPN → rechentafel AST | M | High | ✅ DONE. Note: the "fewer demotions" estimate was optimistic — see below |
| 8 | **B2** — live xlsx export (real formulas) | M–L | Very high | ✅ DONE (`xlformula` ns) — export's #1 documented limitation is gone |
| 9 | **K** — named regions / table refs | M | Medium | Cheap-win candidate, SaltRim-native design |
| 10 | **E** — dynamic arrays / spill | L | Very high | Roadmap-grade; blueprint exists; do last |

Items 1–5 are each a self-contained PR and none of them touch the reactive
engine. Item 6 is the first one that needs a design discussion.
