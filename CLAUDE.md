# SaltRim — working instructions

A simple-but-powerful spreadsheet: **Clojure** engine on **Spindel** (reactive),
**Datastar** UI (hypermedia, SSE), Datahike persistence, live collaboration.
Read `SPEC.md` for the technical architecture. This file = how to work here.

## MCP servers usage

- **Qdrant (preferred memory):** Use the Qdrant MCP server (`mcp-server-qdrant`,
  `qdrant-local`, etc...) for persistent vector memory — it is the **preferred**
  store (semantic recall across sessions). Explicitly use collection name
  `dev-saltrim` (project history: `dev-calcloj` → `dev-clorax` → `dev-saltrim`
  across renames; older collections kept as backups). If the MCP tools error
  (they have — empty errors, often a corrupt fastembed model cache or a stale
  server process), the Qdrant REST API on `localhost:6333` works directly:
  `POST /collections/dev-saltrim/points/query` (embed the query first) or
  `/points/scroll` for reads; embed with the MCP's own fastembed python env and
  `PUT /points` for writes — see the `qdrant-collection-saltrim` memory for the
  exact recipe.
- **Mirror memory both ways.** Qdrant can be unavailable (MCP broken, server
  down, or you're offline), so **also** write anything worth keeping to the
  file-based memory under `~/.claude/projects/.../memory/` — it loads every
  session regardless of Qdrant health. Qdrant is the preferred/searchable copy;
  the file store is the always-available mirror. Keep them in sync.
- **Clojure:** Always use `clojure-mcp` for interactive Clojure development. REPL
  is Clojure's superpower. If nREPL server isn't active, run it using command 
  `clojure -M:nrepl --port 7888` background command. Do not use Claude default 
  code execution and file creation capabilities with Clojure code. Use 
  `clojure-mcp` instead.

## Communication style

The user runs a "caveman" mode plugin — terse, fragments, drop filler. Match it
in chat. **Write code, commits, PRs, and docs normally** (full sentences).
If the user types `/caveman`, invoke the `caveman` Skill.

## How the user works (observed preferences)

- **Decisive, hands-on, opinionated.** They review closely and push back when an
  approach is wrong (e.g. "use another server?", "I don't like heartbeat",
  "collaboration is a need"). Take pushback seriously — they're usually right.
- **Verify before claiming.** They dislike hand-waving. Read real source, run
  spikes, test in the browser, show evidence. Don't assert behavior you haven't
  checked.
- **Prefers clean structure**: no imperative JS in HTML (Datastar attributes +
  a thin `app.cljs`, bridged by custom events — not hidden trigger buttons), no
  stray top-level forms, single source of truth, separate files.
- **Wants extensibility planned now** for near-future features (e.g. style/format
  as reactive properties; the persistence format already leaves room).
- They sometimes edit files between turns (addr.clj, gitignore, etc.). Respect
  those edits; don't revert them.

## Workflow

- **PR workflow**: commit/push/open PRs freely on feature branches — no need
  to ask. Never commit directly to `main`; the user reviews and merges PRs.
  End commit messages with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- One coherent change per commit; write a real body explaining *why*.
- **Spike risky unknowns first** — as REPL walkthroughs under `spikes/` (eval the
  forms at a dev REPL; see `spikes/README.md`), not cold-run mains. Don't build
  UI on unproven engine assumptions.
- **Test after engine changes**: `clojure -X:test` (must stay green, currently
  72 tests / 364 assertions; `db`/`auth` suites use the `:memory` Datahike
  backend). Add tests for new engine behavior.
- **The client is ClojureScript** (`src/.../app.cljs`, compiled to
  `resources/public/app.js`). The dev REPL `(start)` watch-compiles it on save
  (plain CLJS compiler, no node/npm); for a one-shot use `clojure -T:build cljs`.
  After a cljs edit, sanity-check the compiled output: `node --check
  resources/public/app.js`. `app.legacy.js` is the pre-CLJS source, kept for
  reference only (not served). `addr`/`constants` are `.cljc` — shared verbatim
  by server and client (one source of truth for addressing + grid geometry).
- Keep `TECHDEBT.md` current — append when you defer something, mark items DONE.

## Running / testing the app

```bash
clojure -M:nrepl --port 7888  # dev REPL (auto-loads dev/user.clj). Preferred.
                              # (start) also watch-compiles app.cljs -> app.js
clojure -T:build cljs         # one-shot :advanced /app.js (needed before -M:web
                              # on a fresh checkout — app.js is gitignored)
clojure -M:web                # one-shot server on :8080 (open ?s=<sheet-id>)
clojure -X:test               # engine + addr + store + fmt suites
node --check resources/public/app.js

clojure -T:build uber             # compiles /app.js then builds a runnable uberjar
java -jar target/saltrim-<v>.jar  # run the built artifact (serves :8080)
```

**Dev REPL workflow (preferred — use `clojure-mcp` against the running nREPL).**
The system is `mount`-managed (`uno.michelada.saltrim.system`): states
`db/conn` → `web/sweeper` → `web/server`, with timed start/stop logging. From the
REPL (`dev/user.clj` is auto-loaded):

```clojure
(start)    ; bring the system up   (logs each step + elapsed ms)
(stop)     ; take it down
(restart)  ; stop + start, no code reload
(reset)    ; stop, reload changed src nses (tools.namespace), start — edit-then-(reset)
```

Caveat: don't `(require … :reload-all)` with datahike/core.async loaded — it
reloads core.async's protocols and breaks the executor. `(reset)` is scoped to
`src/` and is safe; `:reload` (single ns) is fine.
Spikes are REPL walkthroughs under `spikes/` (eval forms at the REPL).

**Datahike store.** Users, auth tokens, sheet metadata + shares, **and sheet
CONTENT** all live in Datahike (`db` ns), not files. Cells are per-property,
branch-aware datoms: a `:cellprop` per `(sheet, branch, addr, prop)` → `src`
(value is the `:value` prop; each style/format prop is its own cellprop, so a new
style needs no schema change), plus a `:branch` entity for per-branch scalars
(`dcw/drh`, `cols/rows/defs` blobs). Branch `"main"` is the default — the branch
dimension seeds git-like branching (`db/fork-branch!`; per-prop `as-of` via
history). `:cellprop/author` = the writer uid (for per-user undo; change time is
the built-in `:db/txInstant`). `store` is the thin seam over `db` (same
`save!`/`load-record`/…); **`save!` diff-saves** (transact only changed props) —
a blind re-transact churns history under `:keep-history?` (see
`spikes/04-db-cell-storage.clj`). **The file store is RETIRED** — old
`data/*.edn` are ignored, not migrated (collections start fresh in the db).
Dev/staging defaults to an H2 file at `data/saltrim-h2`; prod sets
`SALTRIM_DB_JDBC_URL` (YugabyteDB); tests use `:memory`. Env: `SALTRIM_DB_BACKEND`
(`mem`), `SALTRIM_DB_JDBC_URL`, `SALTRIM_DB_TABLE`, `SALTRIM_DB_PATH` (H2 file),
`SALTRIM_DB_ID` (stable store UUID). JDBC is konserve-jdbc directly (forked for
YugabyteDB — see `deps.edn`); **datahike-jdbc is NOT used** (datahike 0.8
connects konserve stores generically). **Spindel stays pinned at
0.1.15** — 0.1.23 breaks structural rebuild (see TECHDEBT.md).

Namespaces are rooted at `uno.michelada.saltrim.*` under
`src/uno/michelada/saltrim/`. Coordinate `uno.michelada/saltrim`; repo lives at
`github.com/aldebogdanov/saltrim`. **Releases are GitHub-only (no Clojars)**: push a
`v*` tag and `.github/workflows/release.yml` tests, builds the uberjar, and
attaches it to a GitHub Release. See SPEC.md "Build & release".

### Browser verification (important, and harness-specific)

Use the **Claude Preview** MCP tools (`preview_start` with `.claude/launch.json`,
then `preview_eval`/`preview_screenshot`/`preview_console_logs`/`preview_network`).

**ALWAYS shut down what you start.** A SaltRim server (preview `-M:web`, a dev
nREPL, an uberjar) holds port 8080 **and file-locks the dev H2 db**
(`data/saltrim-h2`), so a left-running JVM blocks the user's own runs and forces
them to `lsof`/`kill` by hand. Before you finish a turn (and before starting a
fresh server), stop every process you launched and free the ports — never leave
one running "for convenience":

```bash
lsof -ti:8080 -ti:7888 | xargs kill -9 2>/dev/null   # free web + nREPL ports
```

Prefer `preview_stop` for a preview server; the command above is the catch-all.
If you started a background `clojure`/`java`, kill it explicitly when done.

Gotchas learned the hard way:
- `preview_start` launches a **fresh JVM**. To pick up `web.clj` edits, restart
  the server. `app.js`/`datastar.js` are slurped per request, so a browser
  **reload** picks those up without a server restart.
- The preview harness ties the browser tab to *its* managed server. **Killing
  the server breaks browser control** — you can't cleanly test a real
  server-restart reconnect this way. Test mechanisms with synthetic events +
  `curl` instead.
- `preview_fill` does **not** fire `change`/`focusin`; dispatch events yourself
  in `preview_eval` (e.g. `el.dispatchEvent(new Event('change',{bubbles:true}))`).
- **Test collaboration on a clean load.** Heavy reload/jump churn leaves stale
  client state that *looks* like a collab bug but isn't. Reproduce server-side
  with two `curl` clients (one holding `/stream`, one POSTing `/cell`) before
  suspecting the engine.
- `GET /debug` returns session + loaded-sheet detail (dev only — gate before any
  real deploy).

## Spindel gotchas (the engine) — already solved, don't relearn

- `track` returns an **Interval**, not a value — read with `@(track sig)`.
- Signal mutation only **enqueues**; the executor drains async. Don't pump a
  drain loop; in app code just read after it settles. Tests use
  `sheet/settle!` (→ `simple/await-drain-complete!`) as a barrier.
- **`track` only handles `SignalRef`**, not `Spin`. Cross-formula refs use
  `await`. Every cell is a Spin; literals are a thin spin over an editable
  signal. See `SPEC.md`.
- **Awaiting the same cell twice in one body glitches** on recompute. The
  formula compiler de-dupes: each distinct cell is `await`ed once in a `let`.
- `await`/`track` must appear **literally** in the spin body (CPS breakpoints) —
  not inside a nested `fn`. Ranges expand statically at read time.
- A cyclic formula **StackOverflows** — `sheet/would-cycle?` rejects before
  install.

## Datastar / http-kit gotchas — already solved

- Datastar is **1.0.2 from the CDN** (`@v1.0.2/bundles/datastar.js`); the page
  uses the CDN URL. A matching copy is vendored at `resources/public/datastar.js`
  (served at `/datastar.js`) for offline/air-gapped use — the local path sits as
  a reader comment next to the CDN URL in `web.render/page`, so switching is a
  one-line swap; keep the two in sync if you bump the version. SSE
  events: `datastar-patch-elements` / `datastar-patch-signals`. Attrs use colon
  syntax (`data-on:click`, `data-bind:x`); the event var in expressions is `evt`.
- SSE/lifecycle now uses the official SDK (`dev.data-star.clojure/*`).
- **Never send an empty `patch-elements`.** `d*/patch-elements!` with blank HTML
  emits a `datastar-patch-elements` event with **no `elements` line**; the
  client SSE reader throws ("Error in input stream"), aborts the stream, and
  reconnect-storms — in *every* browser (curl looks fine; it doesn't parse).
  This bit `/stream`'s on-open #self/#peers flush when there was no cursor.
  `patch-inner!` now substitutes an inert `<!-- -->` for blank content. Verify a
  persistent stream by counting `/stream` resource entries on a clean load (must
  stay 1), not by eyeballing — a storm of ~1 reconnect/sec still "mostly works".
- **http-kit does NOT fire an async-channel close on idle disconnect without a
  write** (verified). So session cleanup uses `navigator.sendBeacon` on
  `pagehide` + a TTL sweep — **no heartbeat**. Don't reintroduce heartbeats.
- A persistent SSE that sends nothing looks "finished" to the client → reconnect
  storm. `/stream` flushes an empty signals patch on open to establish it.
- There is no `data-on:load` plugin; run once-on-load via `data-effect` (no
  signal refs ⇒ fires once), or — as we do for the stream — dispatch a custom
  event from `app.cljs` to a `data-on:<evt>__window` handler.
- **Two toast channels, `$err` (red) / `$info` (green, `--lime`), same corner —
  mutually exclusive by construction.** `web.sse/signals!` is the ONE choke
  point every handler patches signals through; it auto-clears whichever of
  `:err`/`:info` a call doesn't mention when the other is set to non-blank, so
  a stale success toast can never linger behind a fresh error (or vice versa)
  without every one of 60+ call sites having to remember to clear the sibling.
  A merge/action confirmation ("merged N cells…") is `:info`, never `:err` —
  don't reuse the error channel for good news.
- **No hidden trigger buttons / bound-input boxes** (the old smell). The split:
  Datastar attributes own all signals + server round-trips (`@post`/`@get`);
  `app.cljs` owns the imperative work (scroll, editor position, resize, keyboard,
  the beacon) and bridges to the server **only** by dispatching `sr-*` window
  CustomEvents that `#ctl`/`#streamer` handlers turn into Datastar actions
  (reading data off `evt.detail`). So nothing in HTML calls a cljs function ⇒
  `:advanced` needs zero `^:export`s. Read DOM `dataset` via `aget`/`.getAttribute`
  in cljs (advanced renames `.-foo`). The persistent `/stream` lives on its own
  `#streamer` element so its `datastar-fetch` lifecycle is distinguishable from
  the `@post`s for reconnect. `$sid` is server-seeded (also on `#ctl`'s
  `data-sid`, which the unload beacon reads).

## Status / roadmap

Done: reactive engine, A1 addressing + ranges, formulas (incl. formula→formula),
errors+toast, cycle detection, tests, persistence, sessions (beacon + TTL
sweep), live collaboration (push streams + reconnect), logical scroll, keyboard
navigation, **auth + multi-tenancy** (OAuth GitHub/Google + dev login, per-user
sheets `<uid>__<name>`, named presence). Dev login is on by default when no
`SALTRIM_*_CLIENT_ID/SECRET` env vars are set. **Sharing** is a Datahike ACL of
`share` grants (db ns): a **capability link** (`:link` grant — an unguessable
token in the URL, `?t=…`, rotatable) at a **read-only or edit** level, plus
**direct per-user grants** (share by name in dev / email in prod); owner-only
share panel; `/cell` write-guard enforces `:read` vs `:read-write`; the picker
lists 'shared with you' sheets. There is no blanket public-to-everyone tier —
broad sharing is the link (the old `:everyone` flag auto-migrates to a link).
**Cell presentation** (PR #14): reactive per-cell style (`$val`, separate style
layer, 5 CSS props + 4 border sides) + number-format masks (`fmt` ns, `:format` prop) +
per-column/row sizing (sparse `:cols`/`:rows`, prefix-sum virtualizer, drag to
resize); in-app help modal + README user guide. **Per-sheet size defaults**:
each sheet carries its own default column width / row height (`:dcw`/`:drh`,
`CW`/`RH` initially), editable in an owner-only `⚙` properties modal (`/props`),
flowed to the client on `#meta` as `data-dcw/drh`; resize drags **snap** to
multiples of the default (hold `Alt` to disable). **Per-sheet namespace** (PR #24):
each sheet has its own SCI context — a predefined stdlib (math/stats/text/date,
bare, read-only) plus the user's own functions/constants kept as a **library of
chunks** (the `ƒ` modal): each chunk `{:id :src}` edited independently with a
**collaborative per-chunk lock** (session `:editdef`; `/deflock` `/defunlock`
`/defsave` `/defadd` `/defdel`; #deflib pushed per session), all merged in order
into the sheet program, persisted as `:defs` (vector) and recompiled live;
`formula/compile` takes the sheet ctx. **Client = ClojureScript** (PR #25): the
JS engine is ported to `app.cljs` (plain CLJS compiler, no node), the address +
geometry code shared as `.cljc`, and the old hidden-trigger UI replaced by a
Datastar-attribute + custom-event bridge.

**What's next lives in `ROADMAP.md`** (single source). SCI, per-sheet ns,
**JS → CLJS**, **cells → Datahike** (the boss-fight storage move), **per-user
selective undo/redo** (`Ctrl+Z`/`Ctrl+Shift+Z`/`Ctrl+Y` → `sheet/undo-step`;
per-session stack in `web`), collapsible-toolbar UI, multi-selection +
cut/copy/paste are all DONE. **Branching — switch + fork (PR A)** is DONE: the
web runtime keys every loaded engine + collaboration broadcast on a
`(sheet, branch)` **room** (sessions carry `:room`; broadcasts filter on it), so
users on different branches don't see each other's cells; `&b=`/`$branch` pick
the working branch (bad/deleted → main); a branch picker switches, an owner-only
🌿 modal forks (`db/fork-branch!`, recording `:branch/parent`+`:branch/base-tx`
lineage) or deletes (`db/delete-branch!`, no resurrection) via `/branch`, with a
`$goto` signal + `data-effect` to navigate. **Merge — PR B** is DONE: owner-only
3-way merge of another branch INTO the current one (`/merge`), against the
common ancestor resolved from fork lineage via `as-of` (`db/merge-base`); the
pure `merge` ns classifies each cell-property into auto-merge vs conflict; the
🌿 modal previews (clean count + per-conflict take-source checkbox → `$mergetake`)
and applies onto the target engine (live + saved + broadcast). **As-of viewing —
PR C** is DONE: read-only time-travel. `&at=<tx>`/`$at` render a sheet `as-of` a
past transaction from a TRANSIENT snapshot (`db/branch-revisions` lists the
points, `db/sheet-doc-asof`→`store/load-record-asof` rebuilds it); the as-of page
is request-scoped (no live room/stream, scroll via `/viewat`), edits refused
(`$at`→`:read`); a 🕘 modal enters it, a banner+picker+Back-to-live drive it. The
**git-like branching boss fight is complete** (switch/fork/merge/as-of).
**Dependency-graph view + terse refs** are DONE: `$A1`/`$A3:D8` are shorthand for
`#cell`/`#cells` (relative, shift on paste); a 🕸 modal renders the cell graph as
a layered SVG DAG (pure `graph` ns over `sheet/deps`; arrows dep→reader; click a
node → `$sel`; capped at 250). Nodes show an optional per-cell `:label` (a
metadata prop on the per-property datom path, set via the style row) else the
address. **`:label` and `:comment` are distinct meta props**: `:label` NAMES the
cell (graph node), `:comment` is prose ABOUT it (corner flag + hover title, and
where the .xlsx importer leaves its audit trail — it used to abuse `:label`).
**Borders**: the style bar offers one `border` pseudo-prop plus a side dropdown
(all/vertical/horizontal/top/bottom/left/right) whose option value is the
comma-joined concrete prop list; `render/border-props` expands it server-side, so
each side stays its own reactive prop (`render/border-sides` is the one map).
**The rendered window is a PX BUDGET, not a cell count**: `WIN-COLS`/`WIN-ROWS`
express it at the DEFAULT cell size, and covering it takes as many cells as their
REAL sizes allow — so both sides WALK the per-index sizes from the window's own
top-left (`geom/span-count`, mirrored by `app.cljs`); dividing by `dcw`/`drh`
undercounts a run of hand-shrunk columns and the right of the grid goes empty
again (that bug twice). The client — the only party that knows its viewport —
measures and reports `$wc`/`$wr` (0 = not yet measured → server's guess; clamped
by `MAX-WIN-*`, sized so a 4K viewport of `MINSZ` cells can't reach it).
`window`/`in-window?`/`total-px` all take `[sh view]` and MUST agree
(`in-window?` derives from the same `view-base`, which clamps at the origin; too
tight and a peer's pushed edit patches nothing). The grid is `flex:1` in a
`100vh` flex-column body — never a fixed `vh`.
**Dynamic refs `$(expr)`** are DONE: the expression's runtime value names the
target — `"A5"` (scalar, like `$A5`) or `"A1:B3"` (row-major vector, like the
static range; the STRING decides the shape, a 1-cell range stays a vector).
Parse wraps the source in parens (top-level `$(…)` is TWO reader forms) and
fuses `$`+list into a `(::dynref …)` marker — so parse now REJECTS trailing
junk after the formula. Inner `$refs` are ordinary static deps (drive
re-resolution + shift on paste); the computed target is not. Compile emits a
per-site loop that awaits each resolved cell via `rt/lookup-dyn`, which
validates (`rt/resolve-dyn`, `MAX-DYN-RANGE` 10k), CYCLE-CHECKS over static ∪
dynamic edges and records the edge in the sheet's `:dyn` registry in one
`swap!` (throw = `{:error}` → `#ERR`, never a StackOverflow), and serves a
double-await collision (dyn target = already-awaited cell) as a fresh
const-spin. THE TRAP (spike 07, reproduced): Spindel await-chain bodies leak
the OLD target's reactive continuation on retarget — an edit of the abandoned
target then writes a WRONG value. Cure: `set-cell!` structurally rebuilds
every DYNAMIC dependent in the combined reverse closure on ANY edit (even
value-only). Spins are pull/LAZY: dyn edges are recorded when a body actually
RUNS (deref) — `handle-graph` forces `sheet/dyn-cells` before reading
`sheet/dyn-deps` (dashed edges in the 🕸 view). Styles reject dynrefs (own-PR
plumbing, see TECHDEBT).
**Merged cells** are DONE: a cell "swallows" its neighbours into one big cell
keeping the top-left (anchor) address. Presentational + non-destructive — a
`:merge` `"<rows>x<cols>"` span prop on the anchor (`sheet/merge-spans`,
`sheet/merge-prop`), so it rides the ordinary cellprop plumbing (persist /
branch / 3-way-merge / as-of / undo for free). Covered cells are HIDDEN, not
cleared (their values/formulas survive; a ref to one keeps working). `geom`
turns the span into geometry (`covered`, `block-of`, `span-px`); `cells-html`/
`render-cells`/`self-html`/`peer-marker` skip covered cells and draw the anchor
spanning its block; `#meta`'s `data-merges` flows the spans to `app.cljs`, which
navigates/edits a block as one cell (`mblk`/`nav-step`, selection snaps to the
anchor). `/mergecells` + `/unmergecells` (owner-or-editor) full-window re-render
like `/insert`; a `:merge` undo entry reports `:affected :all` (undo-step) for
the same reason. NOT via the style bar (kept out of `meta-props`/`style-bar-props`).
**MCP server** (agents) is DONE (phase 1): `POST /mcp` in the SAME process
(`mcp` ns, one route in `web.clj`) — a side-process would be a SECOND WRITER
bypassing the room/autosave/broadcast. Stateless JSON-RPC (no Mcp-Session-Id,
no second SSE beside the browser stream); notifications (no `:id`) → 202 no
body; tool failures are `isError` RESULTS, not protocol errors. Auth (`mcp/credential`) takes
TWO kinds of `Authorization: Bearer`: an **account AGENT KEY** (`srk_…`,
`:agentkey` datoms, SHA-256 hashed like the browser token — `auth/mint-agent-key!`
/`agent-key->uid`/`revoke-agent-key!`, minted in the 🔑 panel via `/agentkey`)
authenticating a USER, or the older per-sheet **capability link**. An account key
reaches every sheet its owner can (so a new sheet needs NO config change) — the
sheet comes from a tool arg but `mcp/resolve-sheet` re-authorizes it against that
user's real ACL on EVERY call, so reach widens and AUTHORITY does not; a link
credential still refuses any sheet arg but its own. Minting REPLACES the previous
key (rotation = revocation); the secret is returned once and never readable again.
**Agent writes AUTO-FORK**: first write forks `main` into `mcp/agent-branch`
(idempotent), so the human reviews via the owner-only 3-way merge — main is never
written by an agent. That branch is derived from the UID for an account key (so
rotating doesn't strand the agent's work, and no secret lands in a branch name),
from the token for a link. Tools go through the
handler seam (`sheet-rec`→`set-cell!`→`settle!`→`save-rec!`→`broadcast!` with a
nil editor-sid = every session sees it live) and return COMPUTED values so the
agent gets the reactive feedback loop. Tool descriptions push FORMULAS over
pasted numbers. Caps: `MAX-READ-CELLS` 2000 (truncates, not errors),
`MAX-WRITE-CELLS` 1000. Spike: `spikes/08-mcp-transport.clj`.
Cheap win left: cell assertions (`=(assert …)`). See `TECHDEBT.md` for
deferred items.
