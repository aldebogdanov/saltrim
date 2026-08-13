(ns uno.michelada.saltrim.approx
  "Floating-point comparison for test assertions, and the reason one is needed.

   `java.lang.Math` does NOT promise bit-identical results across platforms.
   Its contract allows 1 ulp of error on `pow`, `exp`, `log` and friends, and it
   explicitly permits a hardware-specific intrinsic — which is the point of it
   existing separately from `StrictMath`, whose whole job is to reproduce fdlibm
   exactly, everywhere, more slowly.

   So a borrowed financial function is entitled to answer differently on two
   machines. It did: `npv 0.1 [-10000 3000 4200 6800]` is 1188.4434123352207 on
   an arm64 JDK 26 and 1188.4434123352216 on the x86_64 JDK 21 that CI runs —
   about 4 ulp apart, from three `Math/pow` calls compounded through three
   divisions and a sum. Asserting `=` on that was asserting something the JVM
   never offered, and the suite was green locally for months purely because it
   only ever ran on one machine.

   The tolerance is RELATIVE and deliberately loose next to the ~1e-15 the
   platforms actually differ by, and still far tighter than any real defect: a
   wrong rate, a sign error or an off-by-one period moves the answer in the
   first few digits, not the fifteenth.

   Only use this where a value came through transcendental math. `Math/sqrt` is
   correctly rounded by IEEE 754, and +, -, *, / are exact operations, so an
   answer built only from those is bit-identical everywhere and should keep
   asserting `=` — loosening a test that cannot fail only hides the next bug.

   This namespace is deliberately NOT named `*-test`; the runner would try to
   run it."
  (:refer-clojure :exclude [=]))

(def ^:const DEFAULT-REL 1e-12)

(defn =
  "True when `actual` is within `rel` RELATIVE error of `expected` (default
   1e-12). Values below 1 are compared against an absolute floor of `rel`, so a
   near-zero expectation cannot demand impossible precision.

   Shadows `clojure.core/=` inside this namespace only; call it qualified —
   `(approx/= 1188.4434123352207 (v s \"A3\"))` — so the intent is visible at
   the assertion rather than hidden in a require."
  ([expected actual] (uno.michelada.saltrim.approx/= expected actual DEFAULT-REL))
  ([expected actual rel]
   (boolean
    (and (number? actual)
         (let [e (double expected)
               a (double actual)]
           (or (clojure.core/= e a)
               (and (Double/isFinite e) (Double/isFinite a)
                    (<= (Math/abs (- e a))
                        (* rel (Math/max 1.0 (Math/abs e)))))))))))
