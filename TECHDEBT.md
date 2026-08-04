# Tech debt

## Scroll model — DONE (logical scroll)

Replaced the giant-spacer native scroll with a logical-scroll engine (no sized
div): /app.js keeps a logical pixel position (SX,SY), translates the window
layers by the offset for smoothness, draws custom scrollbars, and fetches a new
window (POST /view) only when the top-left cell index changes. Cells are
positioned window-relative; the window base (cb/rb) ships in #meta alongside
#cells so the transform always matches the displayed content (no jump while a
fetch is in flight). Row cap and scrollbar-precision issues are gone.

With the giant spacer gone, `MAX-COLS`/`MAX-ROWS` are no longer a DOM-element
ceiling — they're a pure coordinate clamp, sized to a familiar grid
(`16384` cols = XFD, `1048576` rows). The "600000 to stay under Firefox's
element limit" rationale below is historical.

Follow-ups: keyboard nav DONE (arrows/Tab/Enter move selection, Enter/dblclick
edit — see web layer). **Window size from the viewport is DONE**: the client
measures its own viewport and reports `$wc`/`$wr`, which `geom/window` uses
(falling back to a px-budget guess before first paint, clamped by `MAX-WIN-*`) —
the fixed WIN-COLS/ROWS this entry complained about are now just that fallback.
Still open: PgUp/PgDn/Home/End are not wired; momentum/trackpad feel is raw
deltas (could smooth). The notes below are the original analysis, kept for
context.

### (original) giant spacer analysis

Current viewport uses a "fake-scroll" spacer: one `<div id="space">` sized to the
logical sheet (`cols*CW × rows*RH`) so the native scrollbar reflects the sheet
size, with only the visible window of cells rendered on top.

**Problem:** the spacer height is bounded by the browser's max element pixel size
(~33.5M px Chrome/Safari, ~17.9M px Firefox). At 26px/row that caps usable rows
at ~1.28M (Chrome) / ~688k (Firefox). `MAX-ROWS` is pinned to 600000 to stay
under the Firefox limit. Scrollbar precision is also terrible at that scale
(~1 px ≈ thousands of rows).

**Want:** a scroll model that needs **no huge div** — compute everything from a
logical position instead of a physical one. Options:
- custom/synthetic scrollbar whose thumb maps to a row range (non-linear ok),
  decoupled from any sized element;
- "logical scroll": intercept wheel/drag, keep a virtual `r0/c0` offset, render
  the window at fixed screen coords (translate, not scroll), draw our own
  scrollbar. Address-box jump already proves far-navigation without scrolling.

This removes the row cap entirely and makes the cap purely a coordinate clamp.

## Other

- Concurrent edits can race into a transient `#ERR` (the edit lock — now per
  ROOM, `[sheet branch]`, not per sheet — serializes server side, but
  simultaneous async posts arrive unordered). The "stale toast" half of this is
  gone: toasts are appended cards, so a message can no longer be overwritten by
  a later one before it is read.
- No config system yet — grid bounds, geometry are `def` constants. Fold into
  per-sheet settings once persistence lands.
- **Signals are INTERNED BY ID in the execution context — a trap worth
  remembering.** `sig/->SignalRef "val:<addr>"` does not always hand back a
  fresh signal: after a cell is blanked (which drops our `vals` entry but leaves
  the signal registered under that id) it returns the OLD one, still holding the
  old value, and `ensure-signal-initialized!` will not overwrite it. That made
  clear-then-retype silently keep the previous value (`raw` said 99, the cell
  read 5, and dependents computed off the stale number); it self-healed on
  reload, which is why it hid for so long. `write-cell!` now `reset!`s the signal
  unconditionally after creating it. Any future code that mints a signal by a
  reused id must do the same.

## Sessions — crash/sleep cleanup backstop

DONE: sessions now register on load (`/session/start`) and release on unload via
`navigator.sendBeacon('/session/end')`; sheets are ref-counted and unloaded
(execution context closed, saved) when the last session leaves; viewport is
per-session.

DONE (backstop): each session is stamped with :last-seen (touched on /cell,
/view); a server-side sweep (every 60s) reaps sessions idle > 30 min and unloads
their sheets — so crash/sleep, where the beacon never fires, no longer pins a
sheet forever. A swept client transparently re-registers on its next action
(ensure-session!). No client heartbeat.

REMAINING (minor):
- TTL/sweep interval are constants; move to config.

DONE: **session-less rooms are swept.** A room loaded by a bare GET (or an MCP
tool call) that never opens a `/stream` has no session to release it, so those
accumulated for the life of the process. `sweep-orphan-rooms!` now releases any
room with zero sessions untouched for `ROOM-IDLE-MS`; the idle window is what
keeps it off a load that is mid-handshake.

NOTE: collaboration uses a persistent per-session SSE stream (/stream) for
server->client push. Cleanup does NOT rely on http-kit's channel close (it
doesn't fire on idle disconnect without a write); instead beacon + sweep call
reap-session! which close-sse!s the stored generator. No heartbeat.

## Collaboration — follow-ups

- **Stream reconnect — DONE.** `app.cljs` re-opens the stream on `finished` /
  `retries-failed` with capped exponential backoff (`schedule-reopen!`, 2s→30s),
  suppressed while unloading/hidden. No heartbeat.
- **Dead-connection reaping latency**: a crashed peer's stream is closed on the
  next broadcast to it (write throws -> reap) or by the sweep. Fine, but means a
  zombie socket can linger up to the sweep interval with no traffic.
- **Editor double-path**: the editor still gets its patches from the one-shot
  `@post` response; peers get them via the stream. Could unify (everything via
  streams, `/cell` returns empty) once reconnect is solid.
- **Conflict policy**: last-write-wins for distinct cells. Same-cell editing is
  now guarded by presence locks (below); cross-cell merge is still absent.

The `/debug` endpoint (session/sheet counts) is already gated on
`auth/dev-auth?` in `web.clj`, so it is unreachable once a real OAuth provider
is configured. Nothing to do before deploy.

## Presence & edit locking — follow-ups

DONE: collaborator cursors + edit locks + selection — all SERVER-RENDERED as
overlays (#self for the current user, #peers for others), no per-cell client JS.
Per-session :cursor/:editing; presence posted declaratively via Datastar
(@post '/presence' in cell + formula-bar data-on handlers). Editing marker locks
the cell (pointer-events + server-side locked-by-other? guard in /cell).
Selection persists off-focus (box stays on :cursor); editing tier = animated
marching-ants border.

REMAINING:
- **Stuck lock on crash mid-edit**: if a client sets :editing and then crashes
  (no blur/commit, no beacon), the cell stays locked for that peer until the TTL
  sweep (30 min) reaps the session. Mitigations to consider: a short per-edit
  lock TTL (auto-expire :editing after N seconds of no refresh, with the client
  re-asserting while focused), or clearing :editing on stream death.
- **Selection latency**: the #self overlay moves on a server round-trip (presence
  @post -> patch). Native input :focus masks it, but addrbox jumps show a small
  lag. Fine; revisit only if it feels sluggish.
- **Presence chattiness**: every focus/blur POSTs /presence and re-broadcasts the
  whole #peers overlay to all sessions (and patches #self back). Fine at small
  scale; debounce / diff if sessions-per-sheet grows.
- **No name/identity**: DONE — sessions carry :uid/:uname from the auth layer;
  peer markers show the real user name ("Bob editing…").

## Auth & multi-tenancy — follow-ups

DONE: OAuth login (GitHub/Google via env config, hand-rolled code flow on
http-kit's client), name-only dev provider (auto-on when no real provider is
configured), HttpOnly token cookies with persistent users/tokens registries,
per-owner sheet namespacing (`<uid>__<name>`, fmt 2 ownership envelope),
owner-only /share toggle (+ share link), access checks on every endpoint,
logout reaps the user's live sessions, /debug gated behind the dev provider.

DONE (Datahike step 1): users + auth tokens moved off the EDN files into a
Datahike store (`db` ns). Tokens are now stored as a **SHA-256 hash** (cookie
carries the secret). Backends: H2 dev/staging, YugabyteDB prod (konserve-jdbc
fork), `:memory` for tests. Verified: token survives a server restart; logout
revokes it. (Old `data/users.edn`/`data/tokens.edn` are now unused.)

REMAINING:
- **Sheet `:public` flag moved to the DB ACL — DONE (Datahike step 2a).** A
  sheet's public state is now an `:everyone`/`:read-write` `share` row, not the
  file's `:public` bool. `sheet-rec` registers the `sheet` entity (`ensure-
  sheet!`) and, on its FIRST registration, one-shot-migrates the file's legacy
  `:public` into a grant; `accessible-rec` queries `db/access-level`; `handle-
  share` toggles the grant. Sheet ids stay `<owner>__<name>` strings (the uuid
  switch was NOT needed and is deferred).
  The one-shot `:public` → link migration is **REMOVED**: it read `(:public rec)`,
  which `store/load-record` has hardcoded to `false` since the file store was
  retired, so it could never fire. `migrate-everyone->link!` (a DB-grant upgrade,
  not a file one) stays — an old database can still hold an `:everyone` grant.
- **Read-only tier + direct user grants — DONE (Datahike step 2b).** Public is
  now a level (`:everyone`/`:read` view OR `:read-write` edit), and owners can
  grant **direct per-user shares** (`:user` kind; resolved by name in dev /
  email in prod via `auth/resolve-grantee` → `db/uid-by-email`). `with-access`
  threads the caller's effective `:level` into the rec; `handle-cell` rejects a
  write when it isn't `:read-write`, and `handle-presence` won't let a viewer
  hold an edit lock. `handle-share` dispatches on a `$shareact` signal
  (public/grant/revoke); `evict-unauthorized!` reaps only sessions that LOST
  access (a downgrade isn't evicted — the write-guard covers it). UI: owner
  share **panel** (public level select + link + grant list/add/remove) and a
  picker that groups 'your sheets' (👤) and 'shared with you' (✎/👁).
  REMAINING: **group/org grants** (`:group` kind is in the schema but unused);
  email shares only reach users who've already signed in (no pending invites);
  a `:read` viewer with an open editor sees writes blocked only on commit (no
  proactive UI lock-out).
- **Capability-link sharing — DONE (Datahike step 2c).** "Anyone with the link"
  is now a `:link` grant whose `grantee` is an unguessable token carried in the
  URL (`?t=…`), not the guessable `<owner>__<name>` URL. Closes the enumeration
  hole (knowing a name + sheet name no longer grants access) and adds
  **rotation** (`rotate-link!` mints a new token, killing old links). One link
  per sheet at a level (view/edit). The token rides through every layer: page
  seeds `$link`, POSTs send it, `/stream` takes `?t=`; sessions remember their
  token so a rotate/downgrade re-checks them in `evict-unauthorized!`. The old
  blanket `:everyone` tier is GONE — `migrate-everyone->link!` upgrades any
  legacy public grant to a link on load. db: `link-grant`/`set-link-level!`/
  `rotate-link!`; `access-level` now takes `[uid sheet-id token]`.
  REMAINING: **discoverable-public gallery** (opt-in publish + its own paginated
  browse view — deliberately NOT the personal picker, to avoid clutter) and a
  bounded **recents** list for link-visited sheets are both deferred; the link
  token is stored in plaintext (it's a capability URL, not a credential — fine,
  but note it's readable in the DB).
- **Spindel pinned at 0.1.15** — retested 2026-07-23 against **0.1.36**, still
  broken the same way: **24 failures across 10 tests** (every `dynamic-*` suite
  plus `structural-rebuild` and `empty-cells`), cells resolving to
  `{:error "Spin cancelled by user"}` after a rebuild. The cause is the
  spin-cancellation semantics changed in 0.1.23: we rebuild dependents
  structurally on every edit, which dynamic refs REQUIRE (retargeting `$(expr)`
  otherwise leaves the old target's await continuation live — spike 07). So
  unpinning is not a version bump, it is reworking that path against the new
  semantics, in its own PR with the dynamic-ref suite as the oracle.
- **Datahike create→connect pause**: first-run creation sleeps ~300 ms before
  `connect` to dodge konserve-jdbc's async c3p0 pool close. Works, but a retry/
  await on a readiness signal would be cleaner than a fixed sleep.
- **Read-only tier — DONE.** Sharing now has view/edit levels (see the
  Datahike step 2b note above); a public sheet can be read-only.
- **Unsharing evicts collaborators — DONE.** `handle-share` reaps every
  non-owner session on the sheet (`evict-foreign!`) when it goes public→private;
  their held streams close and the next /cell, /view or /stream reconnect fails
  the access check. Verified via /debug + two-client curl.
- **Google OAuth is still unexercised.** GitHub is no longer in this bucket —
  it is the provider the production deployment signs in with. Google was
  implemented to the same spec but its code path has never met the real
  endpoint. Before 1.0 it needs one manual run against a real client id, or the
  provider should be cut and the app shipped GitHub-only rather than offering a
  button that might 500.
- **Sheet picker — DONE.** The toolbar has a `#sheetpicker` dropdown of the
  signed-in user's sheets (`store/list-names`); selecting one navigates to it.
  A foreign shared sheet shows as a leading `↗ <name>` option. The `#sheetbox`
  text input remains for creating/opening a sheet by a new name.
- **OAuth state + auth sessions are single-node** (in-memory nonces, atom
  registries). Fine for the current single-JVM deploy.

## Cell presentation (style / format / sizing)

- **Style props are reactive; axis sizes are not.** Per-cell `:style`/`:format`
  props compile to spins (literal or `=`-formula, `$val` = own value). Column
  widths / row heights are plain pixel integers (`:cols`/`:rows`, sparse,
  zero-based index keys) — the rendering geometry wants concrete numbers. A
  formula-backed width/height is a rare need we can layer on later via the same
  style machinery if asked.
- **Style UI is a raw text field.** The toolbar style row takes a literal/
  formula string for any prop; no color picker / bold toggle / mask presets yet.
  A friendlier control set is a follow-up (the engine + `/style` endpoint don't
  change).
- **Format = number masks only.** `fmt/apply-mask` supports `0 # . , %` and
  literal prefix/suffix. No date/time patterns yet; non-numbers pass through.
- **Resize re-renders the whole window** (`/size` → `render-window!` +
  `broadcast-window!`). Cheap at current window sizes; if windows grow a lot it
  could push just the affected strips instead.

## ClojureScript client (refactor/use-proper-datastar-attributes)

- **Compiled `resources/public/app.js` is gitignored** (a build artifact). The
  dev nREPL watch builds it `:simple` on `(start)`; `clojure -T:build cljs` and
  `uber` build `:advanced`. Footgun: a fresh checkout running bare
  `clojure -M:web` (e.g. the preview launch config) 404s `app.js` until you run
  `clojure -T:build cljs` once (or start the nREPL). Documented in CLAUDE.md /
  README; revisit if it bites.
- **No CLJS tests yet — and the quality gate now names them as owed.** The
  shared `addr`/`constants`/`geom` cljc is covered on the CLJ side only, so the
  half of it that runs in the browser is guarded by the `:advanced` compile and
  manual verification. That is exactly where the CLJS-only bugs live: `(int
  char)` is `bit-or` in CLJS, `.-foo` is renamed under `:advanced` (hence the
  `aget`/`getAttribute` rule), and `geom/span-count` has to agree with the
  server's answer cell for cell or the right of the grid goes empty. A cljc test
  build — the existing `addr`/`geom` tests compiled and run under the plain CLJS
  compiler, no node/npm beyond `node` itself, which the check step already
  assumes — would lock that down and become step 2 of the gate in CLAUDE.md.
- **Datastar (1.0.2) is vendored and self-served** at `resources/public/datastar.js`
  → `/datastar.js`. DONE — it used to load from jsdelivr with the local path as a
  reader comment beside it, which made a CDN outage a blank page and put a
  third-party origin in `script-src` with full access to every cell. Bumping the
  version now means replacing that one file.
- **`app.legacy.js` — DELETED.** The pre-CLJS hand-written engine was kept for
  reference until the port had been in use long enough to trust; it is no longer
  in the tree (`git log -- '**/app.legacy.js'` if you need to read it).

## Git-like branching (PR A: switch + fork · PR B: merge)

- **Merge is 3-way, owner-driven (PR B, `feat/branch-merge`).** Within a branch
  it's still last-write-wins; cross-branch reconciliation is the merge.
- **Merge base assumes one-level lineage.** `db/merge-base` handles
  source-of-target, target-of-source, and sibling (same parent) cases via
  `as-of` base-tx; a deep chain (fork of a fork merged back across several hops)
  has no true LCA walk yet. Fine for the common fork→edit→merge-back flow;
  revisit if branch trees get deep. Unrelated branches (no common ancestor) are
  refused rather than 2-way merged.
- **Merge re-renders the whole window** (like `/size`): correct + simple, but a
  huge merge pushes more than the few changed cells. Could target `broadcast!`
  per affected cell if it matters.
- **Merge preview/apply recompute independently.** No locked snapshot between
  preview and apply, so a collaborator editing the source/target in between can
  shift what Apply does (it always uses live state at apply time). Acceptable;
  a confirm-against-previewed-plan check could tighten it.
- **Deleting a branch tells its collaborators — DONE.** `evict-branch-deleted!`
  pushes `$branchgone` to every other session in the room, raising a modal that
  names the branch. Deliberately NOT the `$goto` redirect this entry originally
  asked for: silently landing someone on `main` is how a person carries on typing
  and edits main by accident. Until they dismiss it their `$branch` still names
  the dead branch, so every write 403s and no keystroke can reach main.
- **Branch list isn't pushed live.** A fork/delete by the owner only shows up in
  collaborators' branch pickers on reload (the picker is server-rendered once per
  page). Acceptable since branches are owner-managed; could patch `#branchbar`
  to peers if needed.
- **Per-branch presence only.** Peers on a *different* branch are invisible to
  each other (by design — different working copies). There's no "who's on which
  branch" overview.

## Dependency-graph view + cell labels (feat/dep-graph)

- **Graph is a deliberate v1.** Layered SVG DAG with a fixed grid layout, capped
  at 250 nodes (real wide tables are unreadable as a node graph). No zoom, pan,
  force layout, filtering, or focus-on-a-cell-and-its-neighbours yet — all
  deferred polish (per the owner's "quick one-shot, defer polish" call).
- **Node click selects, doesn't jump.** Clicking a node sets `$sel` (fills the
  address box) but doesn't scroll the grid to it — that needs an app.cljs jump
  bridge; skipped to keep this PR server-only (no cljs rebuild).
- **Graph isn't pushed live.** `/graph` renders on open; it won't update while
  open if a collaborator edits. Reopen to refresh. (Fine — it's a peek.)
- **Labels are display-only + set via the style dropdown.** `:label` is a cell
  metadata prop (rides the per-property datom path), but it's settable only by
  picking `label` in the 🎨 style row — there's no dedicated label field, and it
  isn't referenceable in formulas (formula-by-name is a separate, larger
  feature). Label uniqueness isn't enforced (irrelevant while display-only).
- **Only value-formula deps are graphed.** `sheet/deps` is the value layer; style
  formula deps (`style-deps`) aren't drawn. Minor.

## As-of / history viewing (PR C: feat/branch-history)

- **Cells are reconstructed as-of, but defs + axis sizing use the CURRENT
  branch-meta.** A historical view recomputes formulas against today's
  definitions/sizes, so a value can differ slightly if a def changed since. Fine
  for the common case (defs rarely change); reconstruct branch-meta as-of too if
  fidelity matters.
- **Transient sheet rebuilt per scroll.** `/viewat` builds a whole as-of sheet
  (all cells at that tx) on every scroll, then closes it. Simple + leak-free, but
  wasteful for large sheets / rapid scrolling. A short-lived cache keyed by
  `[id branch tx]` (a read-only "room") would amortize it.
- **Revisions = every change tx, capped at 50.** No grouping/labeling (e.g.
  "fork point", author, or collapsing a burst of edits). The fork-copy shows as
  one big revision. A richer timeline (author, message, grouping) is a follow-up.
- **History views don't collaborate.** No stream/presence on an as-of view (by
  design — it's a frozen snapshot); two people viewing the same revision don't
  see each other.

## Insert row/column + multi-cell style (feat/insert-line)

- **Inserting inside a range — RESOLVED.** This used to surface `#ERR` until the
  blank was filled, because a reference to a BLANK cell was an error. Blank cells
  now read as `nil` (`runtime/lookup` returns a fresh const nil-Spin) and the
  stdlib aggregates filter nils, so a range that grows over an inserted blank
  keeps computing. Plain scalar arithmetic over a blank still needs `(or $X 0)`.
- **Delete row/column — DONE.** `#REF!` was decided: `formula/delete-shift`
  rewrites references past the deleted line and turns a reference AT it into
  `(deleted-ref)` → `#REF!`. `⊖ row` / `⊖ col` expose it. Note the undo is not
  the inverse rewrite (`delete-shift` isn't invertible): `delete-undo-snapshot`
  captures the line plus every cell whose source the rewrite touched, so one
  Ctrl+Z restores both the cells and the references to them.
- **Insert is a full rebuild + full-window re-render.** `insert-line!` rebuilds
  the whole cell graph from the shifted document and the handler re-renders the
  entire window for every session. Cheap for modest sheets; could be incremental
  if it matters.
- **Cells shifted past `MAX-COLS`/`MAX-ROWS` are dropped** (the "where possible"
  edge). Irrelevant in practice (used ranges sit far from the grid bound).
- **Multi-cell style records one undo entry per cell** (not a single grouped
  step), so undoing a rectangle-style takes N Ctrl+Z. Insert, by contrast, is one
  structural step. Grouping consecutive per-cell edits into one undo is a possible
  refinement.

## XLSX export (`export` ns) — deferred polish

`/export.xlsx` writes a STATIC snapshot via Apache POI: computed values + a subset
of presentation. Intentional limits / future polish:

- **No formulas / reactivity, by design.** SaltRim formulas are Clojure, not Excel
  syntax; we export the computed value and keep the source as a cell comment
  rather than attempt a (lossy, partial) Clojure→Excel translation. The UI tooltip
  + help modal warn about this.
- **Column widths / row heights are not exported.** Our sizes are pixels; POI uses
  1/256-character + twip units, and `autoSizeColumn` needs headless AWT fonts. Left
  at Excel defaults for now.
- **Colours: hex (`#rgb`/`#rrggbb`), `rgb()`, and a ~30-name subset only.** Any
  other CSS colour name (or a style formula yielding one) is skipped (cell keeps
  the default fill/font colour) rather than guessed.
- **Non-ASCII sheet names are sanitised to `_` in the download filename** (no
  RFC 5987 `filename*` yet); the worksheet TAB name keeps the real name (clamped to
  Excel's 31-char / reserved-char rules).
- **POI adds ~12 MB to the uberjar** (poi-ooxml + xmlbeans). Verified to work from
  the packaged jar; the one-time `log4j-core not found` line at startup is a benign
  POI logging fallback.

## xlsx import (feat/xlsx-import)

`/import` translates Excel formulas to Clojure by walking **rechentafel's
formula AST** (`rechentafel.parser/parse` over the string POI hands us);
everything outside the vocabulary falls back to the cached value + a `comment`,
and a verify pass demotes translated cells that disagree with Excel's cache.

It walked POI's RPN `Ptg` stream with a hand-written stack machine until the
AST rewrite (see `spikes/11-excel-ast-import.clj`) — behaviour-identical on
every formula the old path translated, minus the three token-order hazards
(`AttrPtg(isSum)`, trailing `FuncVarPtg`, `#external#` + `NameXPxg`) and minus
the `FormulaParsingWorkbook`/sheet-index plumbing. `translate-formula` now
takes only the string, which is also what a "paste an Excel formula" input mode
would need.

The function vocabulary is three tiers — hand-mapped (chosen semantics), then
`stdlib/excel-name` (~213 borrowed), then `xl/NAME` verbatim (~414). The last
two were wired up after the AST rewrite made the gap obvious: `xl/` had always
been documented as the reason an imported formula stays live rather than
demoting, and the importer simply never reached for it.

Deferred (all land as commented values today, so sheets stay correct). The AST
names each of these precisely, so the refusal REASON in the audit comment is now
the construct rather than a POI token class — what is missing is somewhere to
put them, not the ability to see them:

- **SUMIF / COUNTIF / AVERAGEIF criteria strings** (`">5"`, `"a*"`) need a
  small criteria parser; map onto `filter` + the aggregate.
- **Approximate-match VLOOKUP** (4th arg TRUE/omitted) — needs sorted-scan
  semantics; only exact match (`FALSE`) translates.
- **Cross-sheet references** (`Other!A1`, 3D areas) — SaltRim has no
  cross-sheet refs yet; revisit if/when it does.
- **Named ranges + structured table refs** (`Tax_Rate`, `Sales[Amount]`) —
  arrive as `:name` / `:table-ref` nodes. Could resolve through POI's workbook
  names into plain refs at translate time; properly, they want SaltRim named
  regions (roadmap item K).
- **Spill refs** (`A1#`) and **range intersection** (`A1:A3 B1:B3`) — `:spill-ref`
  and `:intersect` nodes; spill needs dynamic arrays (roadmap item E).
- **Error literals** (`=#N/A`) — the stdlib has `if-error`/`if-na`/`error-type`
  to CATCH an error but nothing to RAISE one, so `:err` nodes have no target.
- **Whole-column/row ranges** (`A:A`) — ranges expand statically to one ref per
  cell, so a whole column is ~1M of them against `max-range-cells` (4096); needs
  a bounded "used range" clamp to translate safely.
- **Merged regions** — imported as the top-left value only (no merge concept).
- **Excel `=` text comparison is case-insensitive**; ours is exact. Verify
  demotes any cell where this changes the result.
- **`.xls` (BIFF8 legacy)** — XSSF only; HSSF would need the same walk over
  `HSSFWorkbook`.
- **Styles-only blank cells are dropped at IMPORT** (`read-tab`'s
  `:when (:value m)` skips them when reading the workbook). NOTE: SaltRim's own
  persistence now keeps styled blank cells (`sheet/document` serializes the
  union of value + styled cells — fixed after they silently vanished on
  reload), so lifting this is purely an importer change now.
- **Trailing spaces in text cells** are lost to `parse-literal`'s trim (the
  apostrophe escape preserves leading ones).

## Cell borders (style `border`)

The style bar's `border` pseudo-prop writes four independent per-side props
(`bordertop`/`borderright`/`borderbottom`/`borderleft`), each a raw CSS border
shorthand rendered straight into the cell's inline style. Deferred:

- **Borders don't survive .xlsx export.** `export/style-spec` hand-picks the
  props it maps to POI (fill/font/align/format) and `styled?` ignores borders,
  so a border-only cell exports unstyled. Needs a CSS-shorthand → POI
  `BorderStyle` + `XSSFColor` mapping (width/style keywords don't line up
  1:1 — POI has THIN/MEDIUM/THICK, not px).
- **Excel borders aren't imported** either — the reader maps the same five
  props only.
- **No adjacent-cell border collapsing**: each cell draws its own edges, so a
  `right` border and its neighbour's `left` border stack (2 lines, not 1).
  Grid lines already sit under them; a range's outline is drawn per cell.

## Dynamic refs (`$(expr)`)

Shipped: runtime-computed cell/range addresses, reactive both ways (address
inputs + current target), runtime cycle guard, dashed graph edges. Deferred:

- **Dynamic refs in STYLE formulas** — still rejected with a clear error. The
  `:dyn` registry keys by owner ADDRESS; a style's dynamic edge would masquerade
  as a *value* dep of the owner (false "circular" rejections), so styles need a
  per-`[addr prop]` registry. The style-layer rebuild machinery this also needed
  now EXISTS (`sheet/rebuild-styles!`, see below), so the registry split is the
  only remaining blocker.
- **Style staleness on structural replace — CONFIRMED AND FIXED.** The suspicion
  was correct: a style formula `await`s the referenced cell's public spin, but
  style deps live in their own registry, so `set-cell!`'s value-layer rebuild
  never reached them — a `literal → formula` edit recomputed the cell and left
  its colour frozen forever. `set-cell!` now collects every address whose spin it
  REPLACED and calls `rebuild-styles!`, which recompiles exactly the style
  formulas referencing them (`$val` included, since it rewrites to a ref on the
  owner). Covered by `style_test.clj`.
- **Rebuild-skip optimization** — `set-cell!` structurally rebuilds every
  dynamic dependent in the reverse closure on ANY upstream edit (the
  stale-continuation guard, see spike 07). Cheap but over-broad: an edit that
  provably can't change a parent's *addresses* (reaches it only via its own
  dynamic edge, no chained dynrefs) could skip the recompile and let the
  await edge propagate.
- **xlsx import: `INDIRECT(text)` → `$(…)`** — the importer currently leaves
  INDIRECT untranslated (demoted to a value); the address-string grammar now
  exists to target.
- **Static range size cap — DONE.** `formula/MAX-RANGE-CELLS` (10000, mirroring
  `rt/MAX-DYN-RANGE`) refuses an oversized rectangle at parse time, computing the
  area from the corner INDICES so a typo'd `$A1:ZZ99999` is rejected before any
  markers are built.
- **Stale conts hold the abandoned target's spin** until the parent's next
  rebuild — harmless under the rebuild hook (one edit's worth), noted for
  engine archaeology.
- **Pre-existing quirk inherited:** `shift-refs`'s text regexes rewrite
  `$A1`-shaped tokens inside STRING literals too (`=(str "owe $A1")` shifts
  on paste) — applies inside `$(…)` bodies the same way.

## MCP server (agents, `mcp` ns)

- **Agent writes are not undoable by the human.** The undo stack is
  per-SESSION (per browser tab) in `web`; an MCP writer has no tab, so an
  agent's edits can't be `Ctrl+Z`'d. Mitigated by design — agent writes land on
  their own auto-forked branch, so the human's remedy is "don't merge" / delete
  the branch. A durable, author-scoped undo (`:cellprop/author` + history) would
  close it properly.
- **No agent branch lifecycle.** Agent branches are created on first write and
  never cleaned up. Rotating an ACCOUNT key no longer orphans one (the branch is
  derived from the uid, not the key), but a rotated LINK token still does, and
  nothing prunes a branch whose agent is gone. Wants a "discard my branch" tool
  and/or owner-side pruning in the 🌿 panel.
- **One agent key per user, unlabelled.** Minting replaces the previous key, so
  two agents can't hold distinct credentials and you can't revoke one without
  revoking the other — and they'd share an agent branch anyway (it is keyed on
  the uid). Multiple named keys, each with its own branch, is the natural next
  step if more than one agent works a sheet.
- **Account keys carry the user's FULL access.** By design — reach follows the
  ACL — but there is no way to mint a read-only key, or one scoped to a subset
  of sheets. A per-sheet capability link is the only narrower option today.
- **Read pagination is a cap, not a cursor.** `saltrim_read_range` truncates
  at `MAX-READ-CELLS` and says so; MCP guidance prefers a real cursor/
  `has_more` so an agent can page a large sheet.
- **Body needs `Content-Type: application/json`.** A POST without it is
  parsed as form params by the existing middleware, which consumes the body —
  the endpoint then answers a (correct but confusing) `-32700 invalid JSON`.
  Real MCP clients always send the header; a friendlier message would help
  anyone poking it with curl.
- **Cell content is untrusted input reaching an agent.** Tool results are
  returned as data, but a cell containing instructions is a prompt-injection
  surface for whatever client is driving. Worth an explicit note in the tool
  descriptions if agents start reading third-party sheets.

## Merged cells (`sheet/merge-spans`, feat/merge-cells)

- **Partial overlap not rejected.** `/mergecells` clears any `:merge` span
  whose anchor sits INSIDE the new rectangle, but a pre-existing merge anchored
  OUTSIDE the rectangle that only partially overlaps it is left as-is (both
  spans coexist; `geom/covered` just unions them). Rare — a merge is usually
  drawn over free cells — but a "reject / auto-expand to whole blocks" policy
  would be cleaner.
- **Copy/paste doesn't clear what it overlaps.** `copy`/`paste` carries the
  `:merge` prop like any style, so pasting a merged anchor stamps its span at
  the target (usually what you want) but doesn't clear whatever it now
  overlaps.
- **Merges don't shift on row/col insert.** The span is a plain `"RxC"`
  string, not ref-rewritten, so a line inserted THROUGH a block doesn't grow
  it.

## Runaway formulas (`sheet/EVAL-TIMEOUT-MS`, `sheet/degraded?`)

A formula is arbitrary user code, SCI cannot be preempted, and spins are lazy —
so `=(loop [] (recur))` runs forever, starting on whoever first RENDERS the
cell. That used to be an HTTP thread holding the single global `edit-lock`, i.e.
one cell froze every write on every sheet for every user, permanently, and came
back after a restart (the formula is persisted).

Fixed as far as this design allows: `value`/`style-value` deref with a timeout
(`Spin` implements `IBlockingDeref`), and the edit lock is now per room. What
remains:

- **A runaway body wedges its whole SHEET, not just its cell.** It never leaves
  the executor, so the context's drain never completes and no other spin in that
  sheet — not even a literal wrapper — runs again. `degraded?` records the
  culprit once and every later read answers from that mark, so a wedged sheet is
  fast and clearly labelled instead of paying the timeout per cell (a 500-cell
  window would otherwise take sixteen minutes). Other sheets are unaffected.
- **Recovery needs a fresh context.** Editing still works (nothing on the write
  path derefs), so the fix reaches the db and the next load is clean — but the
  stuck body owns this context forever, hence the message "fix that formula,
  then reopen the sheet". The room is rebuilt when its last session leaves.
  Automating that (rebuild into a new execution context on wedge, which needs
  `:rt` to become swappable) would make recovery invisible.
- **The thread is never reclaimed.** One executor thread spins until the sheet
  unloads. Killing it properly needs a step/deadline budget inside the
  interpreter — SCI has no hook for that today, and `Thread.stop` is gone. A
  deliberate abuser can therefore still burn a core per sheet they can edit.
- **`EVAL-TIMEOUT-MS` is a constant** (2s — three orders of magnitude above any
  real formula). Fold into per-sheet settings with the other constants.

## Datahike value-size caps

Datahike 0.8.1746 added opt-in value-size caps; we set `:max-string-length 0`
(explicitly unbounded) rather than take the `:value-caps :default` preset, whose
4096-char string cap would start REFUSING writes that work today — at save time,
on data already typed. The `:defs` blob (a sheet's whole function library in one
string) is the one that would hit it first. Unbounded is what the app always did.

Worth revisiting with a MEASURED cap: large enough for a real defs library and
the longest plausible formula or comment, small enough to bound index growth and
stay inside backend value limits. That needs numbers from a real sheet, plus a
decision about what the app does when a write exceeds it (refuse at the UI, with
a message, rather than throw at save).

## Sheet load is order-dependent, and the order is a hash order — FIXED

Found by the benchmark suite. Installing the SAME 300-cell dependency chain
costs 897 ms in dependency order, 3.9 s in hash-map order, and **111.7 s**
reversed — 125x between best and worst, on identical cells. At 1000 cells the
`chain` shape builds in 3.3 s and loads in 32.4 s.

The mechanism is the blank-cell rule doing its job at the worst possible moment.
A formula whose reference does not exist yet binds a fresh nil-spin; filling
that blank later is a STRUCTURAL change, so `set-cell!` rebuilds the dependents
to capture the real node (this is what makes a reference to a not-yet-filled
cell reactive at all — see the empty-cell note). Install a chain back-to-front
and every single insert re-triggers that cascade over everything already placed.

`load-document!` iterated the document MAP, so the order was whatever the hash
gave — and `store/load-record` hands it exactly such a map. Nobody chose this
order; it is not stable across Clojure versions either. So the time to open a
sheet was decided by hashing.

**Fixed** by the second of the two options weighed here — suppress the rebuild
during the bulk load and do one pass at the end — because it is order-independent
by construction rather than merely order-correct. (The first was: derive the dep
graph from the document and install in topological order. Rejected: it only
makes the good order likely, and it cannot help the interactive path.)

`load-document!` now installs every cell with no cascade, then rebuilds ONE set:
the dependent closure of what it loaded, computed from a single reverse-edge
index rather than a scan per cell. That set is normally EMPTY, and the reason is
worth keeping in mind before anyone "optimizes" the pass away or widens it —
**a Spin body that has never run has captured nothing**. Nothing in the install
pass derefs a spin, so a cell installed before its dependencies still awaits the
finished registry entry when it eventually runs. Only cells ALREADY on the sheet
can hold a dead node, and only `restore-line!` loads onto a populated sheet.

On a populated sheet the loaded cells are rebuilt too, deliberately: resetting a
literal's signal marks the old dependents dirty and the executor drains on its
own thread, so one of them can run — and capture — while the pass is still
going. That is cheap there (the snapshot is one line's worth of cells) and it is
the only window in which a loaded cell can go stale.

Measured after, on today's engine: 35 ms / 11 ms / 10 ms for the same three
orders, and `chain` 1000 loads in 33 ms instead of 2.75 s. See `doc/bench.md`.

## Cycle detection is O(depth) per install — FIXED

Found by the benchmark suite once the load cascade above stopped hiding it.
`would-cycle?` answered "can this new reference reach me?" by walking the forward
dependency graph from scratch, every time. In a chain that walk is the entire
ancestry, so installing n cells cost O(n²) — **~65% of the time to build a
1000-deep chain** (332 ms with the check, 117 ms with it stubbed out), and the
reason loading in DEPENDENCY order had become the slow order.

The check itself is not optional: a cyclic formula deadlocks `await` into a
StackOverflowError, so it has to run before `compile`, and it has to consider
dynamic edges as well as static ones (see `rt/lookup-dyn`, which repeats it at
run time for targets only known then).

**Fixed** without touching the walk, and without the incremental topological
order this entry originally proposed (keep a rank per cell, reorder only the
region between the endpoints of a new edge) — that would have had to survive
`remove-line!`, the reshape rebuilds and the dynamic edges recorded
mid-evaluation, and it turned out not to be needed. A cycle through `addr` has
to come back INTO it, so it needs an edge x -> addr. If nothing references
`addr` there is no cycle to find, however deep the ancestry below it goes, and
the walk is skipped outright. That is the common case: a fresh formula, the next
cell down a column, every cell of an import in dependency order. A
self-reference is the one cycle whose in-edge comes from the install itself, so
`would-cycle?` checks it directly rather than leaving it to the walk —
**remove that line and `=$A1` in A1 StackOverflows the sheet.**

The cheap "does anything reference this?" answer comes from the `:readers`
index (below). `chain` 1000 builds in 31 ms instead of 394 ms.

## The `:readers` index is maintained, not derived

`:readers` is `:meta`'s `:deps` inverted — `{addr #{addrs naming it}}` — and it
exists because rendering an edit, rebuilding dependents and refusing a cycle all
ask "who reads this cell?", which used to mean a `keep` over the entire sheet
per question. Removing those scans is where the **60x edit improvement** came
from (a write to the root of a 1000-deep chain: 83 ms -> 1.3 ms).

The cost is that it has to be updated by hand, in `reindex-readers!`, on every
path that changes a cell's `:deps` — and a missed update is not a slow answer
but a wrong one: a dependent that never rebuilds (silently stale value) or a
cycle that is not refused (StackOverflow). The paths today are the three
branches of `write-cell!` plus `load-document!`'s error branch, which REPLACES a
meta entry wholesale. `engine-test/reverse-index-tracks-every-write` pins it by
comparing the maintained index against a fresh scan after each kind of write and
after 400 random ones; deleting any single `reindex-readers!` call fails it.

**If you add a path that writes `:deps` into `:meta`, it must call
`reindex-readers!` first** (it reads the OLD deps from `:meta`, so ordering
matters). The dynamic half of the reverse edges is deliberately NOT indexed:
`:dyn` is written from `rt/lookup-dyn` on executor threads, it holds an entry
only for cells that have a dynamic ref (usually none), and a second index kept
in step across threads is a lock waiting to be got wrong.

## A formula can await at most ~250 cells (JVM 255-argument limit) — FIXED

Found by the benchmark suite, fixed in the same track. Left here because the
shape of the fix is a constraint on any future change to `formula/compile`.

`=(sum $A1:A250)` compiled; `=(sum $A1:A260)` failed to compile with
`ClassFormatError: Too many arguments in method signature`. A range expands to
one `await` per cell, and Spindel's CPS transform nests a continuation per await
whose method signature carries every previously-bound local — so ~250 awaits
exceeded the JVM's hard cap of 255 arguments.

The fix: **awaits are looped, not written out flat**. One await site, one
continuation frame reused via `recur`, values collected into a vector that
reaches the SCI body through `apply`. No per-cell local exists, so nothing
accumulates in a signature. Two rules that fall out of this and must survive
future edits:

- The addresses arrive as a runtime ARGUMENT, never as a literal in the emitted
  code — a 5 000-element literal vector hits the other JVM limit, "Method code
  too large".
- `static-spin` is a plain `defn`, not an `eval`ed factory, because the shape no
  longer varies per formula. That removed a per-`set-cell!` `eval` and is most
  of why build/load got 9-30x faster; do not reintroduce a per-formula `eval` on
  the static path.

The dynamic path keeps the flat shape below `FLAT-AWAIT-LIMIT` (200 refs) and
switches to the looped one above it. Not premature: a dynamic formula re-runs
its address fns on every recompute and is structurally rebuilt on ANY edit, and
making the looped shape unconditional cost the bench `dyn` shape 10.5 s against
6.3 s at 1000 cells.

What now bounds a range is TIME, not the argument cap — see `MAX-RANGE-CELLS`,
lowered from a fictional 10 000 to a measured 5 000 so an oversized range is
refused at install instead of wedging the sheet.

## Errors as VALUES (so a guard can catch a propagated one)

`errors` classifies a failure and the cell shows its code, and `if-error` /
`if-na` / `error-type` let a formula branch — but only on a failure raised by
the expression they wrap. An error arriving from a REFERENCED cell cannot be
caught: `formula/compile` hoists every ref out of the body and awaits it before
the body runs (the CPS breakpoints must be literal), so the exception reaches
the cell before any guard exists. `(if-error (/ $A1 $B1) 0)` works;
`(if-error $A1 0)` over an already-broken A1 does not. `errors-test`
(`propagation-is-not-catchable`) pins the current behaviour so this cannot
change silently.

Excel does not have this problem because an error there is a VALUE that flows
through every operator, first-error-wins. Matching that means cells returning
`{:error …}` instead of throwing, and every operator becoming error-aware —
otherwise `(+ <error> 1)` is a ClassCastException and `#NUM!` degrades to
`#VALUE!`, losing the original cause. That is a formula-runtime change, not a
patch: it touches the compiler, the stdlib, the aggregates (do they skip errors
like blanks?) and `simplify`. Worth doing, worth its own design pass.

## Excel interop (`xl/`) — deferred pieces

The Excel library (`excel` ns, ~410 functions borrowed from rechentafel) landed
behind an `xl/` namespace, for import/export interop only — the formula language
stays Clojure. Four known seams are open. None of them silently lies; they
either work or fail loudly.

- **The native translation layer is DONE** (`stdlib` ns, ~230 borrowed
  functions under kebab-cased Clojure names, ISO dates at the boundary). What it
  consciously left out, and would be worth revisiting:
  - **2D-shaped functions** — `MMULT`, `MINVERSE`, `MDETERM`, `TRANSPOSE`,
    `LINEST`, `LOGEST`, `TREND`, `GROWTH` all take and/or return rectangles, and
    a SaltRim range is flat. They stay `xl/`-only until ranges carry a shape
    (next item).
  - **Times of day.** `HOUR`/`MINUTE`/`SECOND`/`TIME`/`TIMEVALUE` are not
    translated because SaltRim has no time-of-day convention — dates are ISO
    `yyyy-MM-dd` strings with no clock part. Adding one is a design decision
    (ISO-8601 datetime strings? a separate type?), not a translation.
  - **`CEILING`/`FLOOR` proper** are absent: our `ceil`/`floor` are 1-arg and
    must stay that way, so Excel's round-to-a-multiple pair is exposed under its
    modern names `ceiling-math`/`floor-math`. Slightly surprising if you came
    from Excel; the alternative was changing what `floor` means.
  - **`EVEN`/`ODD`** are absent on purpose — as functions returning rounded
    integers they sit one character away from clojure.core's `even?`/`odd?`
    predicates, which is a footgun for the sake of two rarely-used roundings.

- **Errors are messages, not values.** An Excel function that fails throws an
  `ex-info` whose message is the spreadsheet name (`#DIV/0!`, `#N/A`) and whose
  `ex-data` carries `{:excel-error :div0}`. The cell renders that as its
  `{:error …}`, so the user sees the right word — but a *formula* still cannot
  branch on it, because SaltRim has no error VALUE. Making the taxonomy real
  (`#DIV/0! #VALUE! #REF! #NAME? #NUM! #N/A` as first-class values that
  propagate) would give a genuine `IFNA` (today's `if-error` catches everything,
  including real bugs), give cell assertions something precise to test, and fold
  in the existing `deleted-ref` → `#REF!` case, which already reaches for this
  taxonomy informally. The `ex-data` is already carrying the code for it.

- **Ranges have no shape.** A SaltRim range expands to a FLAT row-major vector,
  so `$A1:A3` and `$A1:C1` are indistinguishable once read. The adapter picks
  the useful default — a flat argument becomes a single COLUMN, which is what
  `SUM`/`SORT`/`UNIQUE`/`FILTER` need — and anything wanting a rectangle takes
  an explicit `(as-rows w …)`. That is why the native `xvlookup` needs its width
  argument too. Giving ranges a real shape (a vector of rows, or a flat vector
  plus `{:rows :cols}`) is the actual fix and would make `VLOOKUP`, `INDEX`,
  `MATCH`, `MMULT`, `TRANSPOSE` and binop broadcasting work without ceremony —
  but it changes the runtime shape every existing formula sees, so it needs its
  own PR and a migration story, not a drive-by.

- **Volatile functions are excluded, and our own `today` has the bug they
  would have.** `NOW`/`TODAY`/`RAND`/`RANDARRAY`/`RANDBETWEEN` are not exposed:
  they depend on nothing, and SaltRim has no recalc sweep, so their value would
  freeze at the cell's last structural rebuild and then differ across branches,
  merges and as-of views. The native `today` in `formula/stdlib` is exactly that
  bug already — a sheet open across midnight, or reloaded from the db months
  later, shows a stale date. Needs a volatility policy (recompute on load? a
  coarse timer? an explicit "as of" input cell?) before any of them are safe. If
  `RAND` ever lands, seed it per sheet — a non-deterministic cell makes 3-way
  merge meaningless.

Also open, smaller: `TEXT` with a date mask returns the serial's digits rather
than a formatted date (upstream implements the numeric masks only), and the
`excel` ns is JVM-only — rechentafel is `.cljc`, so a future client-side formula
preview could use the same pack if the adapter were written portably.
