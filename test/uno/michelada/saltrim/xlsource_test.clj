(ns uno.michelada.saltrim.xlsource-test
  "The ƒ panel's copy button, for the BORROWED half of the stdlib.

   A borrowed function used to hand over a comment and a one-line delegation to
   `excel/call` — unrunnable without the dependency you were trying to leave
   behind, and silent about what the function actually computes. It now hands
   over rechentafel's real implementation with the helpers it needs and
   SaltRim's value bridge around it. That promise is only worth anything if the
   text COMPILES somewhere else and COMPUTES the same answer, so both are
   checked here for every borrowed name."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [uno.michelada.saltrim.stdlib :as lib]))

(defn- standalone
  "Evaluate what the panel hands out in a FRESH namespace — nothing of SaltRim's
   in scope — and return the function it defines. This is the whole promise of
   the button: paste it into your own project and it works.
   The namespace is left in place: what comes back closes over ITS vars, and
   removing it unbinds them under the returned function's feet."
  [sym]
  (let [ns' (create-ns (gensym "xlsrc"))]
    (binding [*ns* ns']
      (clojure.core/refer-clojure)
      (eval (read-string (str "(do " (lib/source-for sym) ")"))))
    (some-> (ns-resolve ns' sym) deref)))

(def ^:private xl->sym
  "Excel name -> the symbol the stdlib installs it as. `borrowed-names` and
   `borrowed-syms` are the same list twice, so zipping them needs no third table."
  (zipmap lib/borrowed-names lib/borrowed-syms))

(deftest every-borrowed-function-compiles-on-its-own
  ;; The whole set, not a sample: what breaks a paste is a NAME COLLISION
  ;; between rechentafel's helpers and ours, and a collision is by nature
  ;; particular to one function. Three of these were real (see below).
  (doseq [sym lib/borrowed-syms]
    (testing (str sym)
      (let [src (lib/source-for sym)]
        (is (string? src) "the panel has source to hand over")
        (is (fn? (try (standalone sym)
                      (catch Throwable t
                        (println "  " sym "->" (.getMessage t))
                        t)))
            (str sym " must compile outside SaltRim"))))))

(def ^:private cases
  "Excel name -> arguments, spanning every module rechentafel groups its
   functions into. Scalar arguments on purpose where the function is scalar: the
   paste deliberately does not reproduce upstream's element-wise broadcast of a
   scalar function over a range, and the header says so."
  '{"PMT" [0.08 10 -1000] "FV" [0.05 10 -100] "IRR" [[-100 30 40 50]]
    "NPV" [0.1 [100 200 300]] "SLN" [1000 100 5] "EFFECT" [0.06 4]
    "XIRR" [[-100 30 40 50] ["2026-01-01" "2026-04-01" "2026-07-01" "2026-10-01"]]
    "ERF" [0.5] "ERFC" [0.5] "BESSELJ" [1.5 1] "BITAND" [12 10] "DEC2BIN" [9]
    "HEX2DEC" ["FF"] "CONVERT" [1 "m" "ft"] "DELTA" [3 3] "GESTEP" [5 4]
    "GEOMEAN" [[1 2 4 8]] "HARMEAN" [[1 2 4]] "KURT" [[1 2 3 4 5 6]]
    "SKEW" [[1 2 3 4 10]] "STDEV.P" [[1 2 3 4]] "VAR.S" [[1 2 3 4]]
    "DEVSQ" [[1 2 3 4]] "LARGE" [[3 1 4 1 5] 2] "MODE.SNGL" [[1 2 2 3]]
    "NORM.DIST" [1 0 1 true] "NORM.S.INV" [0.975] "T.INV" [0.9 5]
    "CHISQ.DIST" [2 3 true] "BINOM.DIST" [3 10 0.5 true] "GAMMALN" [4]
    "CORREL" [[1 2 3 4] [2 4 5 9]] "SLOPE" [[1 2 3 4] [2 4 5 9]]
    "TRIMMEAN" [[1 2 3 4 100] 0.4] "PERCENTILE" [[1 2 3 4] 0.5]
    "RANK" [3 [1 2 3 4]] "SUMIF" [[1 2 3 4] ">2"] "COUNTIF" [[1 2 3 4] ">2"]
    "SUMPRODUCT" [[1 2 3] [4 5 6]] "GCD" [12 18] "FACT" [5] "COMBIN" [5 2]
    "MROUND" [10 3] "ROUNDUP" [3.14159 2] "ARABIC" ["MCMXC"] "ROMAN" [1990]
    "GAUSS" [1] "PHI" [0] "MDETERM" [[[1 2] [3 4]]] "MINVERSE" [[[4 7] [2 6]]]
    "LEN" ["hello"] "MID" ["hello" 2 3] "PROPER" ["hello world"]
    "SUBSTITUTE" ["aXa" "X" "-"] "TEXTJOIN" ["," true ["a" "b"]]
    "FIND" ["l" "hello"] "CODE" ["A"] "VALUE" ["12.5"] "FIXED" [1234.5678 2]
    "EOMONTH" ["2026-03-15" 1] "EDATE" ["2026-03-15" 2] "WEEKDAY" ["2026-03-15"]
    "YEARFRAC" ["2026-01-01" "2026-07-01"] "NETWORKDAYS" ["2026-03-01" "2026-03-15"]
    "WORKDAY" ["2026-03-01" 10] "DATE" [2026 3 15] "ISOWEEKNUM" ["2026-03-15"]
    "DATEDIF" ["2026-01-01" "2026-07-01" "m"] "XOR" [true false]
    "MATCH" [3 [1 2 3 4] 0] "INDEX" [[1 2 3 4] 2]
    "VLOOKUP" ["b" [["a" 1] ["b" 2]] 2 false]})

(deftest copied-source-computes-what-the-sheet-computes
  (doseq [[xl args] cases]
    (testing xl
      (let [sym (xl->sym xl)]
        (is (= (apply (get lib/stdlib sym) args)
               (apply (standalone sym) args))
            (str sym " must answer the same outside SaltRim"))))))

(deftest names-that-collide-are-pulled-apart
  ;; Every one of these produced source that compiled and then failed at RUN
  ;; time, which is the worst way for a copy button to be wrong.
  (testing "a helper sharing the function's own name gives way to it"
    ;; `FACT` is implemented over a private `fact`; defining the wrapper on top
    ;; left the implementation calling a redefined var through a primitive
    ;; signature it no longer had
    (let [src (lib/source-for 'fact)]
      (is (str/includes? src "fact-rt"))
      (is (= 120 ((standalone 'fact) 5)))))
  (testing "an upstream helper already called <name>-impl pushes ours aside"
    ;; rechentafel's own `norm-dist-impl` took four arguments; ours shadowed it
    ;; and the wrapper ended up calling itself
    (let [src (lib/source-for 'norm-dist)]
      (is (str/includes? src "norm-dist-impl*"))
      (is (= 0.8413447404368685 ((standalone 'norm-dist) 1 0 1 true)))))
  (testing "the date bridge is renamed where rechentafel has its own"
    ;; `datetime.cljc` has `date->serial` / `serial->date` too, in LocalDates
    ;; where SaltRim's work in ISO strings
    (let [src (lib/source-for 'eomonth)]
      (is (str/includes? src "date->serial-sr"))
      (is (= "2026-04-30" ((standalone 'eomonth) "2026-03-15" 1))))))

(deftest the-paste-is-clojure-not-cljc
  (testing "reader conditionals are resolved, not handed over"
    ;; `#?(…)` is a syntax error in the ordinary .clj file this is going into
    (doseq [sym lib/borrowed-syms]
      (is (not (str/includes? (lib/source-for sym) "#?"))
          (str sym " still carries a reader conditional")))))

(deftest the-source-says-where-it-came-from
  (let [src (lib/source-for 'pmt)]
    (testing "upstream is credited, with its licence and coordinate"
      (is (str/includes? src "rechentafel"))
      (is (str/includes? src "Apache-2.0"))
      (is (str/includes? src "org.replikativ/rechentafel")))
    (testing "and the implementation is really there, not a delegation"
      (is (not (str/includes? src "excel/call")))
      (is (str/includes? src "(defn pmt ")))
    (testing "with the requires the text actually uses"
      (is (str/includes? src "[rechentafel.value :as val]"))
      (is (str/includes? src "[rechentafel.functions :as f]")))
    (testing "and one namespace is required once, under one alias"
      ;; `excel.clj` calls rechentafel.value `rv` and the function modules call
      ;; it `val`; requiring it twice reads as an accident
      (is (= 1 (count (re-seq #"rechentafel\.value" src)))))))

(deftest hand-written-source-is-untouched
  ;; the hand-written half was already right; this only added a second path
  (is (str/includes? (lib/source-for 'sum) "defn nums"))
  (is (= ";; `abs` IS clojure.core/abs — you already have it."
         (lib/source-for 'abs))))
