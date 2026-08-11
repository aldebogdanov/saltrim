(ns uno.michelada.saltrim.geom-vectors
  "Axis-geometry cases the SERVER and the CLIENT must answer identically.

   `web.geom` (Clojure) lays out the window; `app.cljs` (ClojureScript) decides
   which window to ask for and where to draw it. They are two implementations of
   one arithmetic, and when they disagree the far edge of the grid renders empty
   — which has happened twice, both times because one side divided the pixel
   budget by the DEFAULT cell size instead of walking the real per-index sizes.

   So the expected numbers live here, in cljc, and both suites read them:
   `geom-test/client-and-server-agree-on-the-axis` on the JVM and
   `client-geom-test` in node. A change that moves one side and not the other
   fails on the platform that was left behind.

   Sizes are px. `:ov` is the sparse per-index override map (the sheet's
   :cols/:rows), keyed by 0-based index.")

;; A run of hand-shrunk columns, long enough that the whole covered window sits
;; inside it — a default-width column just past the run would soak up the rest of
;; the budget and hide the undercount.
(def narrow-run (into {} (for [i (range 64)] [i 28])))

(def axis-cases
  "Each case: the absolute start px of an index, `axis-off`/`axis-pos`.
   `:offsets` is index -> px; `:sizes` is index -> that index's own size."
  [{:label   "uniform default size"
    :base    112 :ov {}
    :offsets {0 0, 1 112, 5 560, 17 1904}
    :sizes   {0 112, 9 112}}

   {:label   "one wider column, only counted once we are past it"
    :base    112 :ov {2 200}
    :offsets {0 0, 2 224, 3 424, 6 760}
    :sizes   {1 112, 2 200, 3 112}}

   {:label   "a run of hand-shrunk columns"
    :base    112 :ov {0 28, 1 28, 2 28, 3 28}
    :offsets {0 0, 1 28, 4 112, 5 224}
    :sizes   {0 28, 4 112}}

   {:label   "sparse, out of order, both directions, row-sized"
    :base    26 :ov {5 60, 1 12, 9 40}
    :offsets {0 0, 1 26, 2 38, 5 116, 6 176, 10 294}
    :sizes   {1 12, 4 26, 5 60, 9 40}}])

(def index-cases
  "Which index a scroll position lands in — the inverse of `:offsets` above.
   Client-only arithmetic (the server is told the index), but it has to invert
   the server's own layout exactly or a scroll asks for the wrong window."
  [{:label "uniform"      :base 112 :ov {}
    :at {0 0, 111 0, 112 1, 559 4, 560 5}}
   {:label "one wide col" :base 112 :ov {2 200}
    :at {223 1, 224 2, 423 2, 424 3, 536 4}}
   {:label "shrunk run"   :base 112 :ov {0 28, 1 28, 2 28, 3 28}
    :at {27 0, 28 1, 111 3, 112 4, 223 4, 224 5}}
   {:label "sparse rows"  :base 26 :ov {5 60, 1 12, 9 40}
    :at {25 0, 26 1, 37 1, 38 2, 175 5, 176 6, 253 8, 254 9, 293 9, 294 10}}])

(def span-cases
  "How many consecutive indices from `:i0` it takes to cover `:px`. This is the
   one that has bitten twice: `(quot px base)` answers 16 for every case below."
  [{:label "default cells, one screen"     :base 112 :ov {}         :i0 0   :px 1792 :cap 192 :n 16}
   {:label "quarter-width run -> 4x as many" :base 112 :ov narrow-run :i0 0   :px 1792 :cap 192 :n 64}
   {:label "the run is behind us"          :base 112 :ov narrow-run :i0 200 :px 1792 :cap 192 :n 16}
   {:label "a wide column eats the budget" :base 112 :ov {0 896}    :i0 0   :px 1792 :cap 192 :n 9}
   {:label "the cap bounds the walk"       :base 112 :ov {}         :i0 0   :px 1000000 :cap 192 :n 192}
   {:label "nothing to cover is still one cell" :base 112 :ov {}    :i0 0   :px 0    :cap 192 :n 1}])
