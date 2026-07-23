# SaltRim

A simple-but-powerful collaborative spreadsheet: a reactive **Clojure** engine
(on [Spindel](https://github.com/replikativ/spindel)) with a hypermedia
**[Datastar](https://data-star.dev)** UI (server HTML over SSE), Datahike
persistence, authentication, sharing, and live multi-user editing.

The same guide is available in the app itself — click the **?** button in the
top toolbar.

## User guide

### Cells & formulas

Type a value into a cell, or start with `=` to write a formula. Formulas are
restricted **Clojure s-expressions** (not infix). Reference other cells with the
`$` notation:

| Reference | Meaning |
|-----------|---------|
| `$A1` | the value of A1 |
| `$A1:A3` | a vector of a column range `[A1 A2 A3]` |
| `$A1:C1` | a row range `[A1 B1 C1]` |
| `$A1:B2` | a rectangle, row-major `[A1 B1 A2 B2]` |

References shift on paste (see below). The `$` forms are shorthand for the
underlying reader tags — `$A1` for `#cell A1`, and `$A1:A3` for `#cells A1:A3` —
which you can also write out in full if you prefer.

**Relative references** point to a cell by *offset from the cell itself*, written
`$<col><row>` where each of col/row is `_` (same index), `+N`, or `-N`. They are
resolved per cell, so they **survive copy/paste unchanged** — copy one down or
across and each copy points relative to its own position. That makes series
trivial:

```clojure
; B2 = 1, then B3 = =(inc $_-1) copied down B4:B11  ->  B2:B11 = 1,2,…,10
=(inc $_-1)                  ; "the cell one row up, same column"
; A1 = 0, B1 = 1, then C1 = =(+ $-2_ $-1_) copied right  ->  0,1,1,2,3,5,8,…
=(+ $-2_ $-1_)               ; "two cols left + one col left, same row"
```

**Dynamic references** compute the address *at runtime* — like Excel's
`INDIRECT`, but reactive. Write `$(expr)`: the expression (ordinary formula
code) must produce an address string like `"A5"`, or a range like `"A1:B3"`,
which yields a row-major vector exactly as `$A1:B3` would:

```clojure
=$(str "A" $B1)              ; B1 picks the row: reads A<B1>
=(sum $(str "A1:A" $B1))     ; a range whose EXTENT follows B1
=$(str $C1 $B1)              ; column from C1, row from B1
```

The formula re-fires both when the address inputs change (it re-points to the
new target) and when the current target's value changes. Cell refs *inside*
the expression shift on paste as usual; the computed target itself doesn't —
it's recomputed, not stored. A result that isn't a valid address, a cycle
through the computed target, or an oversized range stops evaluation with
`#ERR` and a toast — a dynamic cycle can't hang the sheet. In the 🕸 graph
view, currently-resolved dynamic edges draw dashed.

Examples:

```clojure
=(+ $A1 $B1)                  ; sum two cells
=(reduce + $A1:A3)            ; sum a range
=(sum $A1:A3)                 ; the same, with a stdlib helper
=(if (> $A1 0) "ok" "no")
```

Formulas that depend on other cells recompute automatically when those cells
change. Circular references are rejected. Errors show as `#ERR` in the cell and
a toast message describing what went wrong.

A **stdlib** is available bare in every formula: math (`sum`, `product`, `round`,
`sqrt`, `pow`, `sign`, …), stats (`mean`/`avg`, `median`, `variance`, `stdev`),
text (`upper`, `lower`, `trim`, `join`, `split`, `str-replace`, `includes?`, …),
date over ISO `yyyy-MM-dd` strings (`today`, `year`, `month`, `day`,
`days-between`), and excel-compat helpers with Excel semantics (`if-error`,
`excel-truthy`, `xmin`, `xmax`, `xround`, `xdate`, `xvlookup`) that the .xlsx
importer targets.

**Empty cells** read as `nil`. The aggregate stdlib functions (`sum`, `mean`,
`median`, `product`, `variance`, `stdev`) **ignore blanks**, so a roomy range over
a partially-filled column just works — `=(sum $B1:B20)` sums whatever rows you've
filled in, and `mean` divides by the count of *present* numbers (not the whole
range). A blank counts as nothing, not zero — so it never skews an average. In
plain arithmetic a `nil` won't add, so wrap a maybe-blank cell to treat it as
zero: `=(+ (or $B5 0) 1)`.

### Reusable functions (the `ƒ` library)

The `ƒ` button (top bar) opens this sheet's **definitions library**: your own
functions and constants, kept as separate entries, callable from any cell. They
run in the same sandbox as formulas (pure, no host interop) and are saved with
the sheet.

```clojure
;; one entry:
(defn margin [rev cost] (/ (- rev cost) rev))
;; another entry:
(def vat 1.16)
```

```clojure
;; then in cells:
=(margin $A1 $B1)
=(* $A1 vat)
```

Each entry collapses to **badges** of the names it declares plus its last-edit
time; **Edit** expands it into a textarea, and **⤢** opens a full-size editor.
While one collaborator is editing an entry it is **locked** for everyone else
(their view shows a lock badge). All entries merge, in order, into the sheet's
program; **Save** recompiles every cell against it (for you and any
collaborators). The built-in functions (above) are shown read-only.

The same **⤢ big editor** sits next to the formula bar and the style bar, for
composing longer formulas or style expressions in a roomy modal.

### Styling a cell

The third toolbar row styles the **selected** cell. Pick a property, type a
value (or an `=`-formula), and press **Enter**. Inside a style formula, `$val`
is the selected cell's own computed value — so styling can react to the data:

```clojure
=(if (> $val 100) "tomato" "white")   ; bg: red when above 100
```

| Property | Controls | Example values |
|----------|----------|----------------|
| `bg` | background color | `tomato`, `#eef`, an `=`-formula |
| `fg` | text color | `navy`, `#333` |
| `weight` | font weight | `bold`, `600` |
| `slant` | font style | `italic` |
| `align` | text alignment | `left`, `right`, `center` |
| `border` | a CSS border | `1px solid black`, `3px double navy` |

Picking `border` reveals a second dropdown for the **side(s)** the value applies
to: `all`, `vertical`, `horizontal`, `top`, `bottom`, `left`, `right`. Each side
is its own property, so you can give a cell a heavy bottom rule and a hairline
left one independently.

Style formulas are reactive too: a style that reads another cell updates when
that cell changes. A broken style formula is reported in the toast and simply
isn't applied. Dynamic references (`$(expr)`) aren't supported inside style
formulas yet — use static `$A1` references there.

**Styling a whole selection:** select a range (or several), then apply a property
— it sets that property on every cell in the selection at once.

### Labels & comments

Two properties in the same dropdown describe a cell instead of painting it:

| Property | Purpose |
|----------|---------|
| `label` | **names** the cell — a short identifier shown instead of its address in the 🕸 dependency graph |
| `comment` | a **note about** the cell — free prose for whoever reads the sheet next |

A commented cell is marked with a small flag in its top-right corner and shows
the comment when you hover it. Comments are also where the `.xlsx` importer
leaves its audit trail, and they travel into an exported workbook as the Excel
cell comment.

### Insert & delete rows and columns

In the format row (**🎨**), the **insert** buttons add a blank row or column next
to the selected cell — **⤒ row** above, **⤓ row** below, **⇤ col** left, **⇥ col**
right. Existing cells shift out of the way and **formula references follow the
shift** (a range that the new line falls inside grows to include it). An insert
is a single **Undo** step (Ctrl/⌘+Z puts everything back).

The **delete** buttons — **⌫ row**, **⌫ col** — remove the row or column the
selected cell is on. Cells after it shift back, and a range that crossed the line
loses exactly the one cell that went. A formula that pointed **at** a deleted cell
becomes **`#REF!`**, naming what it lost, rather than quietly re-pointing at
whichever cell moved into that slot — a formula that still computes and is wrong
is worse than one that stops. Deleting is also a single **Undo** step, and it
restores the whole line: values, formulas and styling.

### Merge cells

Select a range and press **⛶ merge** (format row): the top-left cell "swallows"
the rest of the rectangle into one big cell, keeping its own address (e.g. merging
`B2:D3` leaves a 3×2 cell still addressed `B2`). Merging is **presentational and
non-destructive** — the swallowed cells are only **hidden**, not cleared, so their
values and formulas are kept (a formula that references a hidden cell keeps
working). **unmerge** (or Ctrl/⌘+Z) brings them back exactly as they were. A merged
cell selects, navigates and edits as a single cell — arrows step over it.

### Number format

The `format` property applies a display **mask** to a cell's numeric value
(text is left untouched):

| Mask | input → output | |
|------|----------------|---|
| `0.00` | `1234.5` → `1234.50` | fixed decimals |
| `#,##0` | `1234567` → `1,234,567` | thousands grouping |
| `$#,##0.00` | `1234.5` → `$1,234.50` | literal prefix/suffix |
| `0.0%` | `0.25` → `25.0%` | percent (scales ×100) |

Tokens: `0` required digit · `#` optional digit · `.` decimal point · `,`
thousands grouping · `%` scale by 100 and append `%`. Any other characters are
literal text.

### Column & row size

Drag the trailing edge of a **column header**, or the bottom edge of a **row
number**, to resize it. Sizes are saved with the sheet. Drag back to (or past)
the minimum to reset toward the default. Dragging **snaps** to multiples of the
sheet default (1×, 2×, 3×…) — hold **Alt** to size freely.

If you own the sheet, the **⚙ Sheet properties** panel (top bar) sets the
sheet-wide default column width and row height.

### Navigation

- **Click** a cell to select it; **double-click** or **Enter** to edit.
- **Arrows** / **Tab** move the selection; **Esc** cancels an edit.
- The address box (e.g. `A1`) jumps to a cell.

### Selecting ranges

- **Shift+click** or **Shift+arrows** extends a rectangular range.
- **Ctrl/⌘+click** adds another range (multi-range selection).
- **Delete** / **Backspace** clears the selected cells (undoable).

### Copy / cut / paste

- **Ctrl/⌘+C** copy · **Ctrl/⌘+X** cut · **Ctrl/⌘+V** paste at the selected cell.
- Pasted **formulas shift their references** relative to the move — copy
  `=(+ $A1 1)` down a row and it pastes `=(+ $A2 1)`.
- **Select a range before pasting to fill it** — a single copied cell lands in
  every selected cell (a copied block tiles across the selection), with relative
  refs re-resolved per cell. So one `=(inc $_-1)` pasted down a column is a
  running counter.

### Undo / redo

- **Ctrl/⌘+Z** undoes your last edit; **Ctrl/⌘+Shift+Z** (or **Ctrl+Y**) redoes.
- Undo is **per-user**: it only rolls back *your own* edits, and a cell a
  collaborator changed after you is left untouched.

### Flatten a formula

Select a formula cell and press **⧉** (next to the formula bar): every formula
it references is **inlined in place, recursively**, producing one
self-contained expression — references to plain values stay references. With
`A1 =(* $A2 $A3)`, `A3 =(+ $B1 $B2)`, `B2 =(inc $B3)`, flattening A1 gives:

```clojure
=(* $A2 (+ $B1 (inc $B3)))
```

The result is also **simplified** toward idiomatic Clojure: constants folded
(`(+ 1 2)` → `3`), nested associative calls flattened
(`(+ a (+ b c))` → `(+ a b c)`), `(+ x 1)` → `(inc x)`, `(if true a b)` → `a`,
and (unless **strict** is ticked) identities dropped (`(+ x 0)` → `x`).

- The flattened source opens in the **big editor** for review — nothing changes
  until you press **Apply** (a normal, undoable edit of the cell).
- The **strict** checkbox keeps only rewrites that preserve error behavior
  exactly: e.g. `(+ x 0)` → `x` turns an error over a blank cell into a blank,
  so it's skipped in strict mode.
- Flatten refuses (with a toast) when a local binding in one formula would
  capture a name used by an inlined one — rename the binding and retry.

### Branches

A **branch** is a parallel version of a sheet you can edit independently — like
git, for spreadsheets.

- The **🌿 picker** in the top bar switches branches (the address bar gains
  `&b=<branch>`). Every sheet starts on `main`.
- People working on **different branches don't see each other's cells** — each
  branch is its own live, collaborative copy.
- The owner's **⑂ button** opens a small panel to **fork** the current branch
  into a new one (it starts as an exact copy, then the two diverge), **delete**
  a non-main branch, or **merge** another branch into this one.
- **Merge** is a 3-way merge against the point the branches diverged: changes
  that only one side made are merged automatically; where both sides changed the
  same cell, you get a **conflict list** — tick the ones you want to take from
  the other branch (unticked keeps your current version), then Apply.

### History (time-travel)

The **🕘 button** opens a list of past revisions of the current branch. Pick one
to view the sheet **as it was** at that moment — a read-only snapshot you can
scroll around. A banner shows the timestamp; **Back to live** returns you to the
current sheet. (Editing is disabled while viewing history.)

### Dependency graph

The **🕸** button opens a diagram of how cells feed each other: an arrow points
from a cell to the cells whose formulas read it, laid out left-to-right by
dependency depth. Click a node to select that cell.

To make nodes readable, give a cell a **label**: open the format row (**🎨**),
pick `label` in the property dropdown, and type a name (e.g. `revenue`). The
graph then shows the name instead of the address (`A1`). Labels are display-only
for now (you still reference cells by address / `$A1` in formulas).

> On large real-world tables the graph gets dense — it's intentionally a simple
> first version (capped, basic layout); zoom/filtering are future polish.

### Sharing & collaboration

Owners get a link/lock button in the top bar to share a sheet by **capability
link** (an unguessable URL, rotatable) or with **specific people**, at view or
edit level. Multiple people can edit the same sheet at once — you'll see each
other's cursors and edit locks live.

### AI agents (MCP)

SaltRim speaks the **Model Context Protocol**, so an AI agent can work in your
sheet as a collaborator — served by the same process, at `POST /mcp`.

**Give an agent access** with an **agent key** — the 🔑 button (top bar) mints one
for your account. A single key reaches **every sheet you can**, so making a new
sheet needs no configuration change. It carries *your* access and nothing more:
an agent can only touch sheets you own or were granted.

The secret is shown **once** (only its hash is stored). **Rotate** at any time —
the old key stops working immediately — or **revoke** it outright.

```jsonc
// ~/.claude.json or Claude Desktop config
{ "mcpServers": {
    "saltrim": {
      "command": "npx",
      "args": ["-y", "mcp-remote@latest",
               "https://your-saltrim/mcp",
               "--transport", "http-only",
               "--header", "Authorization:${AUTH_TOKEN}"],
      "env": { "AUTH_TOKEN": "Bearer srk_…" } } } }
```

> Note the `--header` value has **no space** around the `:` — put the space
> inside the env var, as above. That's an `mcp-remote` argument-parsing quirk.

A per-sheet **capability link** token also works as a credential if you want to
scope an agent to exactly one sheet (enable the link at edit level and use its
token instead) — then the sheet argument is fixed and cannot name another.

**Agent edits never touch `main`.** The first write on a token forks `main` into
that agent's own branch (`agent-<token prefix>`). You review its work through the
normal 🌿 **branches** panel — a 3-way merge preview with a conflict picker — and
merge it when you're happy, or delete the branch if you're not. Nothing the agent
does can silently rewrite your sheet.

Because the sheet is reactive, agents are told to write **formulas**, not
pre-computed numbers: what an agent builds keeps recalculating after it's gone.
Writes come back with the computed values, and you watch the cells land live if
you have the branch open.

Tools: `saltrim_list_sheets` · `saltrim_describe_sheet` · `saltrim_read_range` ·
`saltrim_write_cells`.

### Export to Excel

The **⬇ xlsx** button (top bar) downloads the sheet as an `.xlsx` file. It is a
**static snapshot**: every cell exports its current **computed value**, carrying
its styling (fill, font colour, bold/italic, alignment) and number format — but
**not** its borders, and **not** its formula. SaltRim formulas are Clojure expressions, not Excel syntax,
so the exported file has **no live formulas and no reactivity**: changing a value
in Excel won't recompute anything. Each formula's original source is attached as
a **cell comment** so the logic isn't lost. The export respects what you're
viewing — the current branch, or a read-only history snapshot.

### Import from Excel

The **⬆ xlsx** button imports an Excel workbook: every tab becomes a **new
sheet** of yours. Unlike export, import is **live** — Excel formulas are
**translated to Clojure** and keep recomputing:

| Excel | SaltRim |
|---|---|
| `SUM(A1:A10)` | `=(sum $A1:A10)` |
| `IF(A1>2,SUM(B1:B3),0)` | `=(if (> $A1 2) (sum $B1:B3) 0)` |
| `IFERROR(A1/B1,0)` | `=(if-error (fn [] (/ $A1 $B1)) 0)` |
| `VLOOKUP("k",A1:C10,2,FALSE)` | `=(xvlookup "k" $A1:C10 3 2)` |

Supported out of the box: arithmetic (`+ - * / ^ % &`), comparisons, `SUM`,
`AVERAGE`, `MEDIAN`, `MIN`/`MAX`, `COUNT`/`COUNTA`, `IF`/`AND`/`OR`/`NOT`
(with Excel's number-truthiness via `excel-truthy`), `ABS`/`ROUND`/`SQRT`/
`EXP`/`LN`/`LOG10`/`SIGN`/`POWER`, `CONCATENATE`/`&`, `LEN`/`UPPER`/`LOWER`/
`TRIM`, `TODAY`/`YEAR`/`MONTH`/`DAY`/`DATE`, `IFERROR`, and exact-match
`VLOOKUP`. These lean on a permanent **excel-compat** stdlib category
(`if-error`, `excel-truthy`, `xmin`, `xmax`, `xround`, `xdate`, `xvlookup`) —
usable from any formula, listed in the ƒ modal.

Values, styling, number-format masks and column/row sizes carry over; dates
become ISO strings (`2024-03-15`); text that looks like a number or a formula
is protected with a leading apostrophe (`'123` — works when typing, too).

**Anything untranslatable** (cross-sheet references, named ranges,
whole-column ranges, other functions) is imported as its last **computed
value**, with the original Excel formula kept as the cell's `comment`. Every
translated formula is then **verified against Excel's own cached value** —
mismatches (e.g. Excel's blank-as-zero arithmetic) are demoted to values the
same way. An imported sheet is always *correct-or-commented*; the import report
lists every fallback and demotion.

## Running & development

```bash
clojure -M:web        # dev server on http://localhost:8080  (open ?s=<sheet>)
clojure -X:test       # engine / format / store / auth test suites
clojure -T:build cljs # compile the ClojureScript client -> resources/public/app.js
clojure -T:build uber # standalone uberjar (compiles the client first)
```

The browser client is **ClojureScript** (`src/.../app.cljs`, compiled with the
plain CLJS compiler — no node/npm). The compiled `resources/public/app.js` is a
build artifact (gitignored). The preferred dev loop is the nREPL
(`clojure -M:nrepl --port 7888` then `(start)`), which watch-compiles `app.js`
on every save; before a bare `clojure -M:web` on a fresh checkout, run
`clojure -T:build cljs` once to produce it.

Architecture and engine internals are documented in
[`SPEC.md`](SPEC.md); contributor conventions and gotchas live in
[`CLAUDE.md`](CLAUDE.md).

## Acknowledgments

SaltRim stands on the work of many open-source authors and communities. Thank you.

**Language & runtime**

- [Clojure](https://clojure.org) — Rich Hickey and the Clojure core team
- [ClojureScript](https://clojurescript.org) — the compiler that builds the browser client

**Reactive engine & persistence**

- [Spindel](https://github.com/replikativ/spindel) (reactive signal engine), [Datahike](https://github.com/replikativ/datahike) (durable Datalog store), and [konserve-jdbc](https://github.com/replikativ/konserve-jdbc) (JDBC storage layer) — by [replikativ](https://github.com/replikativ)
- [SCI](https://github.com/babashka/sci) — the sandboxed Clojure interpreter behind formulas, by [Michiel Borkent (@borkdude)](https://github.com/borkdude)
- [next.jdbc](https://github.com/seancorfield/next-jdbc) — JDBC access, by [Sean Corfield](https://github.com/seancorfield)
- [H2](https://h2database.com) and [YugabyteDB](https://www.yugabyte.com) — the SQL backends

**Web stack**

- [Datastar](https://data-star.dev) — the hypermedia/SSE UI framework, by Delaney Gillilan and the Datastar team (including the official Clojure SDK)
- [http-kit](https://github.com/http-kit/http-kit) — async HTTP server
- [Ring](https://github.com/ring-clojure/ring) and [Hiccup](https://github.com/weavejester/hiccup) — by [James Reeves (@weavejester)](https://github.com/weavejester)
- [jsonista](https://github.com/metosin/jsonista) — JSON, by [Metosin](https://github.com/metosin)
- [mount](https://github.com/tolitius/mount) — component lifecycle, by [Anatoly Polinsky (@tolitius)](https://github.com/tolitius)

**Interop & tooling**

- [Apache POI](https://poi.apache.org) and [Apache Log4j](https://logging.apache.org/log4j/) — the Apache Software Foundation
- [tools.build](https://github.com/clojure/tools.build), [test-runner](https://github.com/cognitect-labs/test-runner), [nREPL](https://github.com/nrepl/nrepl), and [tools.namespace](https://github.com/clojure/tools.namespace) — build, test, and REPL tooling
