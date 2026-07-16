(ns spikes.dynamic-await
  "Step 7 — dynamic cell addresses (`$(expr)`) groundwork. Proves the Spindel
   0.1.15 behaviors the feature rests on:

   (a) a loop INSIDE one spin body can await a runtime-varying set of spins,
       carrying a map/vector accumulator across the await boundaries — the
       emission shape for dynamic ranges (single cell = range of one);
   (b) `(spin v)` over a plain value (a const-spin) is awaitable — the vehicle
       for runtime collision dedupe (same node must never be awaited twice in
       one body);
   (c) THE TRAP: a body that RETARGETS (awaits a different spin on re-run)
       leaves the old target's reactive continuation live — editing the old
       target later resumes a stale body slice and caches a WRONG value.
       A structural rebuild (cleanup-spin! + recreate) is the cure. This is
       why `sheet/set-cell!` must rebuild dynamic parents on ANY upstream
       edit, not only on structural changes;
   (d) a throw during a tail resume rejects the spin (surfaces as {:error …}
       at the sheet layer), the engine still drains afterwards, and a rebuild
       recovers once the input is fixed — the runtime cycle-guard error path.

   REPL walkthrough: eval the forms in the (comment …) block one at a time.")

(comment
  (require '[org.replikativ.spindel.spin.cps :as cps]      ; cps/spin
           '[org.replikativ.spindel.effects.track :as trk] ; trk/track
           '[org.replikativ.spindel.effects.await :as awt] ; awt/await
           '[org.replikativ.spindel.signal :as sig]
           '[org.replikativ.spindel.spin.core :as spin-core]
           '[org.replikativ.spindel.engine.core :as ec]
           '[org.replikativ.spindel.engine.context :as ctx]
           '[org.replikativ.spindel.engine.impl.simple :as simple])

  (defn wait-deref
    "Poll a spin until it equals `expected` (or timeout). Returns the last value."
    [s expected & {:keys [timeout-ms] :or {timeout-ms 3000}}]
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop [] (let [v @s]
                 (if (or (= v expected) (> (System/currentTimeMillis) deadline))
                   v (do (Thread/sleep 5) (recur)))))))

  (def rt (ctx/create-execution-context))

  ;; ── (b) const-spin: a spin over a plain value is awaitable ──────────────
  (binding [ec/*execution-context* rt]
    (def k42 (cps/spin 42))
    (def plus1 (cps/spin (+ (awt/await k42) 1)))
    @plus1)                               ;; => 43

  ;; ── (a) loop-await: runtime count, loop-carried map across awaits ───────
  (binding [ec/*execution-context* rt]
    (def nsig (doto (sig/->SignalRef "n" 2)   sig/ensure-signal-initialized!))
    (def t0   (doto (sig/->SignalRef "t0" 10) sig/ensure-signal-initialized!))
    (def t1   (doto (sig/->SignalRef "t1" 20) sig/ensure-signal-initialized!))
    (def t2   (doto (sig/->SignalRef "t2" 30) sig/ensure-signal-initialized!))
    (def wn (cps/spin @(trk/track nsig)))
    (def w0 (cps/spin @(trk/track t0)))
    (def w1 (cps/spin @(trk/track t1)))
    (def w2 (cps/spin @(trk/track t2)))
    (def targets [w0 w1 w2])
    ;; The `$(...)`-emission shape: count comes from an awaited value; each
    ;; iteration awaits a different node; vm/acc survive the await boundary.
    (def collector
      (cps/spin
       (let [n (awt/await wn)]
         (loop [i 0 vm {} acc []]
           (if (< i n)
             (let [v (awt/await (nth targets i))]
               (recur (inc i) (assoc vm i v) (conj acc v)))
             {:vals acc :vm vm})))))
    @collector)                           ;; => {:vals [10 20] :vm {0 10 1 20}}

  ;; NB: signal writes need the context bound too — a bare (reset! …) at the
  ;; REPL throws "No execution context bound".
  (binding [ec/*execution-context* rt]
    [(do (reset! t0 11)                   ;; member edit propagates
         (wait-deref collector {:vals [11 20] :vm {0 11 1 20}}))
     (do (reset! nsig 3)                  ;; extent grows: loop re-runs longer
         (wait-deref collector {:vals [11 20 30] :vm {0 11 1 20 2 30}}))])
  ;; => [{:vals [11 20] …} {:vals [11 20 30] …}]   VERIFIED 2026-07-16

  ;; ── (c) the stale-continuation trap, and the rebuild cure ───────────────
  (binding [ec/*execution-context* rt]
    (def sel (doto (sig/->SignalRef "sel" 0) sig/ensure-signal-initialized!))
    (def ta  (doto (sig/->SignalRef "ta" 10) sig/ensure-signal-initialized!))
    (def tb  (doto (sig/->SignalRef "tb" 20) sig/ensure-signal-initialized!))
    (def wsel (cps/spin @(trk/track sel)))
    (def wa   (cps/spin @(trk/track ta)))
    (def wb   (cps/spin @(trk/track tb)))
    (def picker
      (cps/spin (let [s (awt/await wsel)]
                  (awt/await (nth [wa wb] s)))))
    [@picker                              ;; => 10
     (do (reset! sel 1)
         (wait-deref picker 20))          ;; => 20 (retargeted a→b)
     (do (reset! ta 99)                   ;; edit the ABANDONED target…
         (Thread/sleep 300)
         @picker)])
  ;; => [10 20 99]   VERIFIED 2026-07-16 — 99 IS THE BUG: the old target's
  ;; reactive continuation resumed a stale body slice (correct value: 20)

  ;; cure: structural rebuild — cleanup kills the stale conts
  (binding [ec/*execution-context* rt]
    (spin-core/cleanup-spin! picker)
    (def picker2
      (cps/spin (let [s (awt/await wsel)]
                  (awt/await (nth [wa wb] s)))))
    [@picker2                             ;; => 20 (correct again)
     (do (reset! ta 77)                   ;; abandoned target edits now inert
         (Thread/sleep 300)
         @picker2)])
  ;; => [20 20]   VERIFIED 2026-07-16

  ;; ── (d) throw on tail resume: error surfaces, engine drains, rebuild heals ─
  (binding [ec/*execution-context* rt]
    (def gx (doto (sig/->SignalRef "gx" 1) sig/ensure-signal-initialized!))
    (def wgx (cps/spin @(trk/track gx)))
    (def guard
      (cps/spin (let [v (awt/await wgx)]
                  (when (neg? v)
                    (throw (ex-info "circular reference (dynamic)" {:v v})))
                  (* v 10))))
    (def r1 @guard)                       ;; => 10
    (reset! gx -1) (Thread/sleep 300)     ;; tail resume now throws
    (def r2 (try @guard (catch Exception e (.getMessage e))))
    (def r3 (simple/await-drain-complete! rt :timeout-ms 5000))
    (reset! gx 2)                         ;; fix input, rebuild (as set-cell! would)
    (spin-core/cleanup-spin! guard)
    (def guard2
      (cps/spin (let [v (awt/await wgx)]
                  (when (neg? v)
                    (throw (ex-info "circular reference (dynamic)" {:v v})))
                  (* v 10))))
    [r1 r2 r3 @guard2])
  ;; => [10 "circular reference (dynamic)" true 20]   VERIFIED 2026-07-16
  ;; the sheet layer turns the throw into {:error …} → #ERR + toast; the drain
  ;; stays healthy; the rebuild recovers once the input is fixed.

  (ctx/close-context! rt))
