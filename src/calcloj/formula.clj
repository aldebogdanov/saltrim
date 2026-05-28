(ns calcloj.formula
  "Formula = restricted Clojure expression. Cell refs via reader tag:
     #cell A:1   ->  current value of cell A:1

   Uniform model: every cell is a Spin, so refs use `await` (handles Spins;
   `track` only handles SignalRef). `#cell A:1` -> `(await (lookup \"A:1\"))`.

   Pipeline:
     parse    : string -> {:form :deps}
     validate : whitelist every symbol (pre-eval sandbox; EDN reader blocks #=)
     compile  : form -> Spin, via Spindel's real spin macro (await is a
                registered effect). Must run with *execution-context* bound."
  (:refer-clojure :exclude [compile await])
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [calcloj.runtime :as rt]))

;; --- parse --------------------------------------------------------------

(def ^:private readers
  ;; #cell A:1 -> (await (calcloj.runtime/lookup "A:1"))   [await must be bare]
  {'cell (fn [sym]
           (list 'await (list 'calcloj.runtime/lookup (str sym))))})

(defn deps
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x)
                  (= 'calcloj.runtime/lookup (first x)))
         (vswap! acc conj (second x)))
       x)
     form)
    @acc))

(defn parse
  "Formula string (without leading =) -> {:form :deps}."
  [s]
  (let [form (edn/read-string {:readers readers} s)]
    {:form form :deps (deps form)}))

;; --- validate (whitelist sandbox) --------------------------------------

(def allowed-ops
  '#{+ - * / await track deref
     calcloj.runtime/lookup calcloj.runtime/lookup-val
     min max abs mod quot rem
     = not= < > <= >= and or not if when
     sum avg count})

(defn validate!
  [form]
  (walk/postwalk
   (fn [x]
     (when (and (symbol? x) (not (contains? allowed-ops x)))
       (throw (ex-info "disallowed symbol in formula" {:symbol x})))
     x)
   form)
  form)

;; --- compile ------------------------------------------------------------

(defn compile
  "Parsed form -> Spin. Validates, then evals `(spin form)` in this namespace
   so bare spin/track/await resolve and CPS recognizes the effects."
  [form]
  (validate! form)
  (binding [*ns* (find-ns 'calcloj.formula)]
    (eval (list 'spin form))))

(defn compile-literal-wrapper
  "Spin that exposes a literal cell's editable signal as a public, awaitable
   node: (spin (deref (track (lookup-val addr))))."
  [addr]
  (compile (list 'deref (list 'track (list 'calcloj.runtime/lookup-val addr)))))
