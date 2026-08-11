(ns uno.michelada.saltrim.test-runner
  "Entry point for the ClojureScript suite: `clojure -M:cljs-test` compiles this
   with the plain CLJS compiler and runs the bundle under node.

   `dom-stub` is FIRST in the require list on purpose — it installs `document` /
   `window` as a load-time side effect, and `app` calls `addEventListener` on
   both at the top level, so a later load order throws before a single test runs."
  (:require [cljs.test :as t :refer-macros [run-tests]]
            [uno.michelada.saltrim.dom-stub]
            [uno.michelada.saltrim.addr-test]
            [uno.michelada.saltrim.client-geom-test]
            [uno.michelada.saltrim.app-test]))

;; cljs.test's default :end-run-tests does nothing, so the process would exit 0
;; on a red suite and the gate would pass on a failure.
(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(defn -main [& _]
  (enable-console-print!)
  (run-tests 'uno.michelada.saltrim.addr-test
             'uno.michelada.saltrim.client-geom-test
             'uno.michelada.saltrim.app-test))

(set! *main-cli-fn* -main)
