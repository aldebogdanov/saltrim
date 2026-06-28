(ns uno.michelada.saltrim.render-test
  "Guards that the whole web layer COMPILES. The engine tests don't load
   `web`/`web.render`, so a bad form there — or a method over the JVM's 64KB
   bytecode limit (\"Method code too large\", which bit the giant `help-html`) —
   used to slip past `clojure -X:test` and only surface at uber/AOT time.
   Requiring `web` here forces compilation of render + handlers + the rest."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [uno.michelada.saltrim.web]
            [uno.michelada.saltrim.web.render :as render]))

(deftest web-layer-compiles
  ;; reaching this point means every web.* namespace compiled
  (is (some? (resolve 'uno.michelada.saltrim.web/-main))))

(deftest help-modal-renders
  (let [h (#'render/help-html)]
    (is (string? h))
    (is (str/includes? h "quick guide"))
    (is (str/includes? h "Cells &amp; formulas") "first section half present")
    (is (str/includes? h "Export to Excel") "second section half present")))
