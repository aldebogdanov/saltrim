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

Recorded 2026-07-30 · SaltRim dev · JVM 26.0.1 · macOS · 10 cores.

| shape | n | cells | build | load | edit | read all | build/cell |
|---|---|---|---|---|---|---|---|
| chain | 100 | 100 | 18ms | 42ms | 1.0ms | 0.4ms | 0.18ms |
| wide | 100 | 200 | 162ms | 164ms | 0.1ms | 0.3ms | 0.81ms |
| aggregate | 100 | 101 | 148ms | 180ms | 0.2ms | 0.1ms | 1.47ms |
| star | 100 | 101 | 6.1ms | 7.8ms | 1.1ms | 0.2ms | 0.06ms |
| dyn | 100 | 200 | 626ms | 615ms | 0.1ms | 0.3ms | 3.13ms |
| xl | 100 | 200 | 160ms | 183ms | 0.1ms | 0.3ms | 0.80ms |
| chain | 1000 | 1000 | 337ms | 2.75s | 85ms | 1.1ms | 0.34ms |
| wide | 1000 | 2000 | 2.02s | 2.38s | 0.5ms | 1.8ms | 1.01ms |
| aggregate | 1000 | 1001 | 2.04s | 4.85s | 0.4ms | 0.9ms | 2.04ms |
| star | 1000 | 1001 | 124ms | 211ms | 87ms | 0.9ms | 0.12ms |
| dyn | 1000 | 2000 | 6.6s † | 7.2s † | 0.5ms | 1.8ms | 3.3ms † |
| xl | 1000 | 2000 | 1.89s | 2.12s | 0.5ms | 1.8ms | 0.95ms |

† `dyn` measured alone (`--shapes=dyn 1000`), for the order-sensitivity reason
above; it reads ~10 s when run sixth in the sweep, on identical code.

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

## What the first run found

Two things, both real, neither previously known — which is the argument for
having a benchmark suite at all.

### 1. Opening a sheet can be orders of magnitude slower than typing it

`chain` at 1000 cells: **build 3.3 s, load 32.4 s**. Same cells, same
`set-cell!`, ten times the cost. `aggregate` at 100 shows the same shape of gap
(275 ms vs 7.2 s).

The cause is INSTALL ORDER, not the bulk path itself. A formula whose reference
does not exist yet binds a blank, and filling that blank later is a structural
change — `set-cell!` rebuilds the dependents to capture the real node. Install a
dependency chain back-to-front and every insert re-triggers the cascade.
`load-document!` iterates the document MAP, so the order is whatever the hash
gives, and `store/load-record` hands it exactly such a map.

Measured directly on `chain` 300:

| install order | time |
|---|---|
| dependency order | 897 ms |
| hash-map order (what load actually does) | 3.9 s |
| reverse dependency order | **111.7 s** |

125x between best and worst on the same 300 cells. A user opening a sheet with a
long chain waits tens of seconds today, and the wait is decided by hash
ordering. See `TECHDEBT.md` — the fix is to install in dependency order, or to
suppress per-cell rebuilds during a bulk load and do one pass at the end.

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
