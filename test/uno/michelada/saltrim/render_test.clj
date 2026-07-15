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

(deftest border-side-expansion
  ;; the style bar sends `border` + the side dropdown's comma-joined prop list;
  ;; every expansion must be a writable style prop
  (is (= [:bordertop :borderright :borderbottom :borderleft]
         (render/border-props "bordertop,borderright,borderbottom,borderleft")))
  (is (= [:borderleft :borderright] (render/border-props "borderleft,borderright")))
  (is (= [:bordertop] (render/border-props "bordertop")))
  (is (every? render/prop-allowed? (apply concat (vals render/border-sides))))
  (is (nil? (render/border-props "")) "no side -> no write")
  (is (nil? (render/border-props "bg")) "only border props may ride the side list")
  (is (nil? (render/border-props "bordertop,bg")) "one bad prop rejects the group")
  ;; the dropdown offers `border` once, never the four sides directly
  (is (= [:bg :fg :weight :slant :align :border :format :label :comment]
         (vec render/style-bar-props))))

(deftest help-modal-renders
  (let [h (#'render/help-html)]
    (is (string? h))
    (is (str/includes? h "quick guide"))
    (is (str/includes? h "Cells &amp; formulas") "first section half present")
    (is (str/includes? h "Export to Excel") "second section half present")))
