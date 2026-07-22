(ns uno.michelada.saltrim.css-safety-test
  "A cell's computed style value is spliced into its `style` attribute. Hiccup
   escapes quotes, so it can't break OUT of the attribute — but a `;` starts a
   NEW declaration inside it, which hands any editor arbitrary CSS on every
   collaborator's screen: a `background-image:url(https://evil/)` beacon that
   fires from each viewer's browser, or a cell that covers its neighbours."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.render :as render]))

(def ^:dynamic *sh* nil)

(use-fixtures :each (fn [t]
                      (let [sh (sheet/create-sheet)]
                        (try (binding [*sh* sh] (t))
                             (finally (sheet/close! sh))))))

(deftest ordinary-style-values-are-allowed
  (is (every? render/css-value-ok?
              ["red" "#e2e6ea" "rgba(0,0,0,.15)" "bold" "italic" "center"
               "1px solid black" "2px dashed var(--accent)" "hsl(210 40% 50%)"
               "calc(100% - 2px)" "'Fira Code', monospace"])))

(deftest injection-shapes-are-refused
  (doseq [v ["red;position:fixed;top:0;left:0;width:100vw;height:100vh"
              "red;background-image:url(https://evil.example/leak.png)"
              "url(https://evil.example/x.png)"
              "URL ( https://evil.example/x.png )"
              "image-set(\"https://evil.example/x.png\" 1x)"
              "red;} .cell {background:blue"
              "expression(alert(1))"
              "red/*x*/;color:blue"
              "red\n;color:blue"]]
    (is (false? (render/css-value-ok? v)) (str "must refuse " (pr-str v)))))

(deftest an-injected-value-never-reaches-the-html
  (sheet/set-cell! *sh* "A1" "hi")
  (sheet/set-style! *sh* "A1" :bg
                    "red;position:fixed;background-image:url(https://evil.example/x.png)")
  (sheet/settle! *sh*)
  (let [html (render/cells-html *sh* [0] [0])
        decls (second (re-find #"style=\"([^\"]*)\"" html))]
    (is (not (str/includes? decls "position:fixed")))
    (is (not (str/includes? decls "evil.example")))
    (is (not (str/includes? decls "background-color:red"))
        "the whole value is refused, not trimmed to its safe prefix")
    (testing "and the refusal is reported, not silent"
      (is (= [:bg] (map first (render/css-errors *sh* "A1")))))))

(deftest a-formula-cannot-launder-one-past-the-check
  ;; the source looks harmless; only the COMPUTED string is dangerous, which is
  ;; why the check sits on the value rather than on what the user typed
  (sheet/set-cell! *sh* "A1" "1")
  (sheet/set-style! *sh* "A1" :bg "=(str \"red\" \";\" \"position:fixed\")")
  (sheet/settle! *sh*)
  (is (= "red;position:fixed" (sheet/style-value *sh* "A1" :bg))
      "the engine still computes it — this is a RENDER-time guard")
  ;; assert on the style ATTRIBUTE: the source also rides in data-sty (JSON, read
  ;; back into the style box), where it is data and never interpreted as CSS
  (let [html  (render/cells-html *sh* [0] [0])
        decls (second (re-find #"style=\"([^\"]*)\"" html))]
    (is (not (str/includes? decls "position:fixed")))
    (is (str/includes? html "data-sty=") "the source is still echoed to the UI"))
  (is (= [:bg] (map first (render/css-errors *sh* "A1")))))

(deftest a-safe-value-still-renders
  (sheet/set-cell! *sh* "A1" "1")
  (sheet/set-style! *sh* "A1" :bg "tomato")
  (sheet/set-style! *sh* "A1" :bordertop "1px solid black")
  (sheet/settle! *sh*)
  (let [html (render/cells-html *sh* [0] [0])]
    (is (str/includes? html "background-color:tomato"))
    (is (str/includes? html "border-top:1px solid black"))
    (is (empty? (render/css-errors *sh* "A1")))))

(deftest js-str-survives-a-quote
  ;; hiccup's escaping does NOT protect a data-on:* handler — the parser
  ;; un-escapes the attribute before the JS engine sees it
  (is (= "\"a'b\"" (render/js-str "a'b")))
  (is (= "\"a\\\"b\"" (render/js-str "a\"b")))
  (is (= "\"a\\\\b\"" (render/js-str "a\\b"))))
