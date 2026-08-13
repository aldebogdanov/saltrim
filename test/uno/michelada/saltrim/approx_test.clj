(ns uno.michelada.saltrim.approx-test
  "The tolerance in `approx` is a number somebody chose, so it is worth pinning
   what it must accept and what it must still reject. The accepted values are
   the REAL ones from the CI failure that prompted it, not invented ones."
  (:require [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.approx :as approx]))

(deftest it-accepts-the-divergence-that-actually-happened
  ;; arm64 / JDK 26 (local) vs x86_64 / JDK 21 (CI), same code, same inputs
  (testing "npv 0.1 [-10000 3000 4200 6800] — three Math/pow calls compounded"
    (is (approx/= 1188.4434123352207 1188.4434123352216)))
  (testing "pmt 0.05 10 -1000"
    (is (approx/= 129.50457496545658 129.50457496545664)))
  (testing "and it is symmetric — whichever platform writes the expectation"
    (is (approx/= 1188.4434123352216 1188.4434123352207))))

(deftest it-still-rejects-a-real-defect
  ;; the point of a tolerance is that it is far tighter than any actual bug. A
  ;; wrong rate, a sign flip or an off-by-one period moves an early digit.
  (is (not (approx/= 1188.4434123352207 1188.4434123)) "8 digits in is too far")
  (is (not (approx/= 1188.4434123352207 1188.5)))
  (is (not (approx/= 149.02948869707532 -149.02948869707532)) "sign error")
  (is (not (approx/= 149.02948869707532 1490.2948869707532)) "off by 10x"))

(deftest small-values-get-an-absolute-floor
  ;; relative error is meaningless near zero: 1e-18 vs 0.0 is infinitely wrong
  ;; relatively and identical in every way that matters
  (is (approx/= 0.0 1e-18))
  (is (approx/= 0.0 0.0))
  (is (not (approx/= 0.0 1e-6)) "but the floor is not a licence to be wrong"))

(deftest it-does-not-quietly-pass-a-broken-cell
  ;; a failing cell is a map, a missing one is nil — neither is "close enough"
  (is (not (approx/= 1.0 nil)))
  (is (not (approx/= 1.0 {:error "boom" :code :div0})))
  (is (not (approx/= 1.0 "1.0")))
  (testing "and neither are the non-finite doubles"
    (is (not (approx/= 1.0 ##NaN)))
    (is (not (approx/= 1.0 ##Inf)))
    (is (not (approx/= ##NaN ##NaN)) "NaN is not equal to itself, here either")))

(deftest exact-equality-still-passes
  ;; the common case: nothing transcendental happened and the answer is exact
  (is (approx/= 2250 2250))
  (is (approx/= 20.0 20.0))
  (is (approx/= 1.4142135623730951 (Math/sqrt 2.0))
      "sqrt is correctly rounded by IEEE 754, so this would pass under = too"))
