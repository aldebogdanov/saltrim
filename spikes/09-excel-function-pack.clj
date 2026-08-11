(ns spikes.09-excel-function-pack
  "SPIKE 09 — can we borrow rechentafel's Excel function library without
   borrowing its engine?

   rechentafel (org.replikativ/rechentafel, Apache-2.0) is a pure-Clojure Excel
   interpreter. Its recalc model is the OPPOSITE of ours — a pull-based dirty
   set swept in topological order, versus our push-based Spindel graph — so
   adopting the engine is off the table. But its ~500 Excel functions are
   registered in a plain atom and dispatched through `rechentafel.functions/call`
   with no workbook, no dependency graph and no evaluator anywhere in sight.

   The three things this spike had to prove before any of it was worth writing:

     1. Can a strict function be called STANDALONE, outside the engine?
     2. Can plain SaltRim values (numbers, strings, nil, flat range vectors)
        cross the value seam into Excel's tagged model and back?
     3. Does 400+ extra names in the SCI sandbox cost anything per sheet?

   All three: yes. What the spike also SETTLED, and why the adapter looks the
   way it does — starting with the biggest decision, which is NOT technical:

     - The pack is NOT merged into the bare namespace. SaltRim's formula
       language is Clojure; Excel is a boundary, not a second language. So
       everything here is reached as `xl/PMT`, and a formula you write yourself
       sees only Clojure. `xl/` earns its keep on IMPORT (a formula whose
       function we lack stays live instead of demoting to a dead number) and on
       EXPORT (an `xl/` call maps back to a real Excel formula).

     - Not every registered name should be exposed. Upstream registers the
       functions POI leaves unimplemented (PRICE, YIELD, CALL, CUBEVALUE, …) as
       constant #N/A for parity. In SaltRim that is a trap: the user gets a
       silent #N/A instead of \"no such function\". They are excluded.
     - Volatile functions (NOW/TODAY/RAND/…) have nothing to depend on, so in a
       push engine they would freeze at their last structural rebuild. Excluded
       until there is a volatility policy.
     - A flat range has no shape, and the shape MATTERS: as a row, SORT does
       nothing; as a column it sorts. Flat becomes a column; rectangles are
       explicit via `as-rows`.

   Run: eval the `(comment …)` forms below one at a time at a dev REPL."
  (:require [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.formula :as formula]
            [uno.michelada.saltrim.sheet :as sh]
            [rechentafel.functions :as rf]
            [rechentafel.value :as rv]
            [sci.core :as sci]))

(comment

  ;; --- 1. the registry is usable standalone ------------------------------
  ;; No workbook, no cell, no recalc — a function is a (fn [args] value) over
  ;; tagged maps. This is the whole reason the borrow is possible.

  (rf/call "PMT" [(rv/number 0.08) (rv/number 10) (rv/number -1000)])
  ;; => {:t :num, :v 149.02948869707532}

  (rf/count-registered)
  ;; => 506   ;; incl. the NORM.DIST / NORMDIST style aliases, each a real entry

  ;; Errors come back as VALUES, not exceptions — which is what lets a bad
  ;; cell stay a bad cell instead of taking down the formula.
  (rf/call "MOD" [(rv/number 1) (rv/number 0)])
  ;; => {:t :err, :v :div0}


  ;; --- 2. the value seam --------------------------------------------------
  ;; ->rv / <-rv are the entire adapter. Blank is the interesting one: a blank
  ;; cell must be Excel's blank, NOT 0, or AVERAGE silently divides by the
  ;; wrong count.

  (excel/->rv nil)                          ;; => {:t :blank}
  (excel/->rv [1 2 3])                      ;; => a 3x1 COLUMN area
  (excel/->rv [[1 2] [3 4]])                ;; => a 2x2 area

  (excel/call "AVERAGE" [[10 20 nil]])      ;; => 15   (not 10)
  (excel/call "COUNT"   [[10 20 nil]])      ;; => 2

  ;; Integral doubles narrow back to Long, so a count reads as 3, not 3.0 —
  ;; same rule the .xlsx importer uses for cell literals.
  (excel/call "SUM" [[1 2]])                ;; => 3
  (class (excel/call "SUM" [[1 2]]))        ;; => java.lang.Long

  ;; An Excel error becomes an ex-info named the way a spreadsheet user knows
  ;; it. The sheet layer renders ex-message as the cell's {:error …}.
  (try (excel/call "SQRT" [-1]) (catch Exception e [(ex-message e) (ex-data e)]))
  ;; => ["#NUM!" {:excel-error :num}]


  ;; --- 3. why flat ranges become COLUMNS ---------------------------------
  ;; A SaltRim range is a flat row-major vector: $A1:A3 and $A1:C1 are
  ;; indistinguishable once read. Watch what the choice costs — as a 1x3 row,
  ;; SORT sorts each row and changes nothing:

  (excel/<-rv (rf/call "SORT" [{:t :area :r0 0 :c0 0 :r1 0 :c1 2
                                :values [[(rv/number 3) (rv/number 1) (rv/number 2)]]}]))
  ;; => [3 1 2]     ;; a row of one row — sorted, uselessly

  (excel/call "SORT" [[3 1 2]])
  ;; => [1 2 3]     ;; column: what anyone actually meant

  ;; Rectangles stay explicit until ranges carry a real shape (see TECHDEBT):
  (excel/call "VLOOKUP" ["b" (excel/as-rows 2 ["a" 1 "b" 2 "c" 3]) 2 false])
  ;; => 2


  ;; --- 4. dates: two conventions, one bridge -----------------------------
  ;; Excel dates ARE numbers (1900 serials); SaltRim dates are ISO strings.
  ;; Feeding one to the other fails loudly rather than lying:

  (try (excel/call "YEAR" ["2026-07-29"]) (catch Exception e (ex-message e)))
  ;; => "#VALUE!"

  (excel/date->serial "2026-07-29")                    ;; => 46232
  (excel/call "YEAR" [(excel/date->serial "2026-07-29")])  ;; => 2026

  ;; Excel believes 1900 was a leap year. Serial 60 is a day that never
  ;; existed, so serials below it need the other epoch — both sides agree:
  (mapv excel/date->serial ["1900-01-01" "1900-03-01"])   ;; => [1 61]
  (mapv excel/call ["DATE" "DATE"] [[1900 1 1] [1900 3 1]])
  ;; => [1 61]


  ;; --- 5. cost of 400+ names in every sheet's sandbox --------------------
  ;; Each sheet gets its OWN SCI context, so this is per-sheet, not per-process.

  [(count formula/stdlib) (count excel/sci-ns)]   ;; => [50 414]

  (let [t0 (System/nanoTime)]
    (dotimes [_ 50] (sci/init {:namespaces {'clojure.core formula/stdlib
                                            'xl excel/sci-ns}}))
    (/ (- (System/nanoTime) t0) 50e6))
  ;; => ~0.3 ms per context. Nothing. (SCI is a plain map lookup here.)

  ;; SCI resolves a qualified alias, dotted Excel names and all — and does NOT
  ;; resolve the bare name, which is exactly the boundary we want:
  (def ctx (sci/init {:namespaces {'clojure.core formula/stdlib 'xl excel/sci-ns}}))
  (sci/eval-string* ctx "(xl/NORM.DIST 42 40 1.5 true)")   ;; => 0.9087887181301249
  (try (sci/eval-string* ctx "(NORM.DIST 42 40 1.5 true)")
       (catch Exception e (ex-message e)))
  ;; => "Unable to resolve symbol: NORM.DIST"


  ;; --- 6. end to end, through the real reactive engine -------------------

  (def s (sh/create-sheet))
  (doseq [[a raw] [["A1" "10"] ["A2" "20"] ["A3" ""] ["A4" "30"]]]
    (sh/set-cell! s a raw))

  (sh/set-cell! s "B1" "=(xl/SUM $A1:A4)")
  (sh/set-cell! s "B2" "=(xl/AVERAGE $A1:A4)")
  (sh/set-cell! s "B3" "=(+ (xl/SUM $A1:A4) (sum $A1:A4))")  ;; interop + native
  (sh/settle! s)
  (mapv #(sh/value s %) ["B1" "B2" "B3"])
  ;; => [60 20 120]     ;; AVERAGE skips the blank: 60/3

  ;; Errors are reactive like any other value — the cell recovers when the
  ;; dependency it choked on changes.
  (sh/set-cell! s "C1" "=(xl/MOD 1 $A3)")
  (sh/settle! s) (sh/value s "C1")          ;; => {:error "#DIV/0!"}
  (sh/set-cell! s "A3" "3")
  (sh/settle! s) (sh/value s "C1")          ;; => 1


  ;; --- 7. what we refused to expose, and how it is detected --------------
  ;; The #N/A stubs all come from one `na-stub` factory per module, so the
  ;; closure's CLASS names them. Pinned by excel-test so an upstream change
  ;; fails a test instead of leaking stubs back in.

  (into {} (map (fn [[k v]] [k (count v)])) excel/excluded)
  ;; => {:lazy 33, :volatile 9, :stub 56}

  (map #(.getName (class (:fn (rf/lookup %)))) ["PRICE" "PMT"])
  ;; => ("rechentafel.fn.financial$na_stub$fn__…"     ;; a stub
  ;;     "rechentafel.fn.financial$eval…$fn__…")      ;; the real thing

  (count excel/exposed-names)               ;; => 411
  )
