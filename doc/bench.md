# Engine benchmarks

```bash
clojure -M:bench                      # default sizes (100, 1000)
clojure -M:bench 200 2000             # explicit sizes
clojure -M:bench --shapes=chain,star 1000
```

Pure in-memory sheets — no database, no server, no ports held, so this is safe
to run alongside anything else.

## What is measured

| column | what it costs in the app |
|---|---|
| **build** | `set-cell!` per cell + one `settle!` — typing, or the .xlsx importer |
| **load** | `load-document!` bulk path + `settle!` — opening an existing sheet |
| **edit** | one write to the root cell + `settle!` — **what a collaborator waits for** |
| **read all** | `sheet/value` over every cell — rendering, scaled to the whole sheet |

Each number is the **median of 5 runs after 2 warmups**, on a sheet built fresh
per run. Absolutes are machine-specific; compare *ratios* across a change, and
re-record the table below on the same machine when you do.

## Shapes

Borrowed from rechentafel's bench vocabulary (see
`doc/rechentafel-evaluation.md`), because they isolate different failure modes —
though the measurements are ours: a push-based Spin graph and a pull-based
dirty/topo recalc break in completely different places.

| shape | formula | stresses |
|---|---|---|
| `chain` | `A_i = (inc A_i-1)` | one long dependency chain — an edit at the root walks the whole depth |
| `wide` | `B_i = (* A_i 2)` | N independent one-hop formulas; an edit touches exactly one dependent |
| `aggregate` | `B1 = (sum A1:AN)` | one formula awaiting N cells |
| `star` | `B_i = (+ A1 i)` | N dependents on ONE cell — an edit fans out to all of them |
| `dyn` | `B_i = $(str "A" i)` | dynamic refs, which `set-cell!` structurally rebuilds on **any** edit |
| `xl` | `B_i = (pmt A_i …)` | a borrowed Excel function per cell, inside real reactive work |

## Results

_Recorded on the machine and version noted in the header; re-run after engine
changes rather than trusting these._

Recorded 2026-08-04 · SaltRim dev · JVM 26.0.1 · macOS · 10 cores.

| shape | n | cells | build | load | edit | read all | build/cell |
|---|---|---|---|---|---|---|---|
| chain | 100 | 100 | 23ms | 9.1ms | 2.2ms | 0.4ms | 0.23ms |
| wide | 100 | 200 | 163ms | 152ms | 0.1ms | 0.3ms | 0.82ms |
| aggregate | 100 | 101 | 147ms | 144ms | 0.1ms | 0.1ms | 1.46ms |
| star | 100 | 101 | 7.6ms | 5.8ms | 0.8ms | 0.1ms | 0.08ms |
| dyn | 100 | 200 | 608ms † | 589ms † | 0.1ms | 0.6ms | 3.04ms † |
| xl | 100 | 200 | 164ms | 158ms | 0.1ms | 0.2ms | 0.82ms |
| chain | 1000 | 1000 | 341ms | 33ms | 82ms | 0.9ms | 0.34ms |
| wide | 1000 | 2000 | 2.02s | 1.77s | 0.5ms | 2.0ms | 1.01ms |
| aggregate | 1000 | 1001 | 2.04s | 2.01s | 0.4ms | 0.9ms | 2.04ms |
| star | 1000 | 1001 | 118ms | 30ms | 79ms | 0.9ms | 0.12ms |
| dyn | 1000 | 2000 | 6.56s † | 6.84s † | 0.5ms | 2.2ms | 3.28ms † |
| xl | 1000 | 2000 | 1.70s | 1.49s | 0.5ms | 1.6ms | 0.85ms |

† `dyn` measured alone (`--shapes=dyn 1000`), for the order-sensitivity reason
above; it reads ~10 s when run sixth in the sweep, on identical code.

**`load` is now at or below `build` on every shape**, which is what it should
always have been — the same cells, minus the per-cell rebuild a load has no use
for. Where the two are close (`wide`, `aggregate`, `xl`, `dyn`) the cost is
compiling formulas, not ordering them.

### Before the deferred rebuild

`load` only. Nothing else moved, because nothing else was touched:

| shape | n | load before | after |
|---|---|---|---|
| chain | 100 | 42ms | **9.1ms** |
| star | 100 | 7.8ms | **5.8ms** |
| aggregate | 100 | 180ms | **144ms** |
| chain | 1000 | 2.75s | **33ms** |
| star | 1000 | 211ms | **30ms** |
| aggregate | 1000 | 4.85s | **2.01s** |
| wide | 1000 | 2.38s | **1.77s** |
| xl | 1000 | 2.12s | **1.49s** |

### Before the looped awaits

The same suite on the previous engine, for comparison. `build` and `load` are
where the change lands, because both were paying a per-formula `eval`:

| shape | n | build before | after | load before | after |
|---|---|---|---|---|---|
| chain | 100 | 221ms | **18ms** | 720ms | **42ms** |
| star | 100 | 220ms | **6.1ms** | 331ms | **7.8ms** |
| aggregate | 100 | 275ms | **148ms** | 7.17s | **180ms** |
| chain | 1000 | 3.28s | **337ms** | 32.43s | **2.75s** |
| star | 1000 | 3.30s | **124ms** | 4.23s | **211ms** |
| aggregate | 1000 | **FAILED** | **2.04s** | 3.01s | 4.85s |
| wide | 1000 | 4.46s | **2.02s** | 6.76s | **2.38s** |
| xl | 1000 | 4.80s | **1.89s** | 6.89s | **2.12s** |

9-30x on the shapes dominated by installing formulas, and `aggregate` at 1000
computes at all now instead of failing to compile. `edit` and `read` are
unchanged — they were never the problem.

Reading the table:

- **~0.1-2 ms per cell to install**, depending on shape. The 20 000-cell import
  cap is therefore seconds of engine time, not the minute it used to be.
- **An edit is cheap when the graph is shallow and wide** — 0.1-0.5 ms for
  `wide`, `dyn` and `xl` even at 2000 cells, because only the touched
  dependents recompute. It costs ~100 ms when the edit is at the root of a
  1000-deep chain or a 1000-wide fan-out. That is the collaboration latency
  budget, and it is fine.
- **Reads are free** (~1 ms for 2000 cells): values are already computed, so
  rendering a window is not where time goes.
- **`dyn` is not quadratic.** The structural rebuild of dynamic dependents on
  every edit was the thing most likely to blow up here; at these sizes it costs
  ~1.4x `wide` on build and nothing measurable on edit.

## What the benchmarks found

Three things so far, all real, none previously known — which is the argument for
having a benchmark suite at all. Two are fixed; the third is what the second fix
uncovered underneath.

### 1. Opening a sheet can be orders of magnitude slower than typing it — FIXED

`chain` at 1000 cells: **build 3.3 s, load 32.4 s**. Same cells, same
`set-cell!`, ten times the cost. `aggregate` at 100 shows the same shape of gap
(275 ms vs 7.2 s).

The cause was INSTALL ORDER, not the bulk path itself. A formula whose reference
does not exist yet binds a blank, and filling that blank later is a structural
change — `set-cell!` rebuilds the dependents to capture the real node. Install a
dependency chain back-to-front and every insert re-triggers the cascade.
`load-document!` iterated the document MAP, so the order was whatever the hash
gave, and `store/load-record` hands it exactly such a map.

**Fixed**: `load-document!` no longer loops over `set-cell!`. It installs every
cell first and rebuilds once, at the end, over the dependent closure of what it
loaded — which on a fresh or just-cleared sheet is EMPTY, because a Spin body
that has never run has captured nothing to go stale. Order-independence was
always the documented contract; it is now also free.

Measured directly on `chain` 300, both paths on today's engine:

| install order | `set-cell!` per cell | `load-document!` |
|---|---|---|
| dependency order | 35 ms | 32 ms |
| hash-map order (what a load actually gets) | 151 ms | **11 ms** |
| reverse dependency order | 7.75 s | **10 ms** |

221x between best and worst before, 3x after — and the worst case is now the
fastest, having nothing to cascade. (The original run measured 897 ms / 3.9 s /
111.7 s for the same three orders; that was the pre-looped-await engine, where
every install also paid an `eval`.)

### 2. A formula can await at most ~250 cells — FIXED

`=(sum $A1:A250)` compiled;
`=(sum $A1:A260)` dies with `ClassFormatError: Too many arguments in method
signature`. A range expands to one `await` per cell, and Spindel's CPS transform
nests a continuation per await carrying every prior binding as a method
argument — past ~250 the generated method exceeds the JVM's hard 255-argument
cap. `aggregate` is therefore the shape that fails rather than the shape that is
slow, and the harness reports **FAILED** for it instead of aborting the run.

**Fixed**: the awaits are now looped — one await site, one continuation frame
reused via `recur` — so nothing accumulates in a method signature. 260, 500,
1000 and 5000-cell formulas all compute, stay reactive, and keep range order.
What bounds a range now is the TIME of its first evaluation against
`sheet/EVAL-TIMEOUT-MS`, so `MAX-RANGE-CELLS` dropped from a fictional 10 000 to
a measured 5 000 and an oversized range is refused at install instead of wedging
the sheet. See `TECHDEBT.md` for the constraints the fix imposes on future
edits to `formula/compile`.

The original finding, for the record: the configured limits
(`MAX-DYN-RANGE` 10 000, the importer's `max-range-cells` 4 096) promise ranges
far larger than the engine can compile. Full write-up, including why the fix is
"a range should be ONE await of a collection", is in `TECHDEBT.md`.

### 3. Cycle detection costs more than installing the formula

Visible only once the load cascade (finding 1) was gone. `would-cycle?` walks the forward
dependency graph from each new reference looking for a way back, and in a chain
that walk is the whole ancestry — O(depth) per install, O(n²) for the sheet.
Stubbing it out (`with-redefs`, correctness discarded) on `chain` 1000:

| | with the check | without |
|---|---|---|
| build (dependency order) | 332 ms | **117 ms** |
| load (dependency order) | 237 ms | **31 ms** |

So **~65% of installing a deep chain is cycle checking**, and it is why loading
in dependency order is now the SLOW order (32 ms vs 10 ms for the same 300
cells): arriving after your dependencies means there is an ancestry to walk.

Nothing is wrong with the answer — a cyclic formula still `StackOverflow`s the
engine, so the check has to stay ahead of `compile`. What is wrong is
recomputing reachability from scratch on every keystroke when the graph changed
by one edge. Incremental cycle detection (a maintained topological order) is the
known fix and a real piece of work; see `TECHDEBT.md`.
