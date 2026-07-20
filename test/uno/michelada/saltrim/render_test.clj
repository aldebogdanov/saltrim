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
    (is (str/includes? h "Export to Excel") "second section half present")
    (is (str/includes? h "AI agents (MCP)") "third section half present")))

(deftest import-report-is-a-modal-fragment
  ;; The report lands in the import modal over SSE (#importreport), so it must be
  ;; a bare fragment — a full page would break patch-elements and, before that,
  ;; navigated the whole tab away from the sheet.
  (let [h (render/import-report-html
           {:sheets [{:sname "book-Data" :tab "Data" :cells 6 :formulas 2
                      :fallbacks [{:addr "B3" :formula "Other!A1*2" :reason "Ref3DPxg"}]
                      :demoted [] :masks-dropped []}]})]
    (is (str/includes? h "id=\"importreport\"") "patch target present")
    (is (not (str/includes? h "<html")) "no full page")
    (is (not (str/includes? h "<body")) "no full page")
    (is (str/includes? h "book-Data")))
  (let [h (render/import-error-html "not an .xlsx file")]
    (is (str/includes? h "id=\"importreport\""))
    (is (not (str/includes? h "<html")) "no full page")
    (is (str/includes? h "not an .xlsx file"))))

(deftest import-report-formula-counts
  ;; xlsx's :formulas counts only successfully TRANSLATED formulas; untranslated
  ;; fallbacks are never in it. The headline total is therefore :formulas +
  ;; fallbacks, and only demotions come out of the live count. Subtracting
  ;; fallbacks from :formulas (the old bug) under-counted "translated live" and
  ;; could print a negative.
  (let [render-one (fn [m] (render/import-report-html
                            {:sheets [(merge {:sname "s" :tab "T" :cells 9 :formulas 0
                                              :fallbacks [] :demoted [] :masks-dropped []} m)]}))
        fb (fn [n] (vec (repeat n {:addr "B3" :formula "X!A1" :reason "Ref3DPxg"})))
        dm (fn [n] (vec (repeat n {:addr "B1" :formula "SUM(A1:A2)" :cached 0})))]
    ;; all clean: 3 translated, nothing untranslated or demoted
    (is (str/includes? (render-one {:formulas 3})
                       "3 formulas (3 translated live, 0 untranslated, 0 demoted to values)"))
    ;; a fallback is an ADDITIONAL formula, not one taken out of :formulas
    (is (str/includes? (render-one {:formulas 2 :fallbacks (fb 1)})
                       "3 formulas (2 translated live, 1 untranslated, 0 demoted to values)"))
    ;; demotions come out of the translated count; the parts still sum to the total
    (is (str/includes? (render-one {:formulas 2 :fallbacks (fb 1) :demoted (dm 2)})
                       "3 formulas (0 translated live, 1 untranslated, 2 demoted to values)"))))
