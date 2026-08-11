(ns uno.michelada.saltrim.app-test
  "The client engine's behaviour: merged-block navigation, the selection algebra,
   and — the part that actually crosses to the server — WHICH `sr-*` bridge event
   comes out of a gesture.

   That bridge is the whole contract. Nothing in the HTML calls a function here;
   `app.cljs` dispatches `sr-*` CustomEvents on window and a
   `data-on:sr-*__window` handler on #ctl / #streamer turns each one into a
   Datastar action. So `dom/event` reading the last dispatched event is testing
   exactly what the server would have received."
  (:require [cljs.test :refer-macros [deftest is testing are use-fixtures]]
            [uno.michelada.saltrim.dom-stub :as dom]
            [uno.michelada.saltrim.app :as app]))

;; `init` runs once, on datastar-ready, exactly as the page does it — after that
;; the viewport/document listeners are the real ones and a test can fire at them.
(defonce ^:private boot (atom nil))

(use-fixtures :once
  {:before (fn []
             (dom/meta! {})
             (dom/set-viewport! 1120 208)
             (dom/clear-events!)
             (dom/fire-doc! "datastar-ready" nil)
             (reset! boot (dom/events)))})

(use-fixtures :each
  {:before (fn []
             (reset! app/SEL {:ranges []})
             (dom/meta! {})
             (dom/clear-events!))})

(defn- detail [nm] (:detail (dom/event nm)))
(defn- merges [m] (.-merges (clj->js {:merges m})))

;; --- merged cells navigate as one cell --------------------------------------

(deftest merge-block-lookup
  ;; B2 spanning 2 rows x 3 cols covers ci 1..3, ri 1..2. mblk answers
  ;; #js [anchor-col anchor-row cols rows] for ANY coordinate inside it.
  (let [m (merges {"B2" [2 3]})]
    (is (nil? (app/mblk m 0 0)) "outside the block")
    (is (= [1 1 3 2] (vec (app/mblk m 1 1))) "the anchor itself is in its block")
    (is (= [1 1 3 2] (vec (app/mblk m 3 2))) "so is the far corner")
    (is (nil? (app/mblk m 4 1)) "one column past it")
    (is (nil? (app/mblk m 1 3)) "one row past it"))
  (testing "no merges at all is the common case and must stay cheap and nil"
    (is (nil? (app/mblk (merges {}) 5 5)))))

(deftest arrows-step-over-a-block-not-into-it
  (let [m (merges {"B2" [2 3]})]
    (testing "right leaves from the block's FAR edge, wherever you are inside it"
      (is (= [4 1] (app/nav-step m 1 1 1 0)))
      (is (= [4 1] (app/nav-step m 3 2 1 0))))
    (testing "left and up leave from the anchor"
      (is (= [0 1] (app/nav-step m 3 1 -1 0)))
      (is (= [1 0] (app/nav-step m 2 2 0 -1))))
    (testing "down leaves from the bottom row"
      (is (= [1 3] (app/nav-step m 1 1 0 1))))
    (testing "landing inside a block snaps to its anchor"
      (is (= [1 1] (app/nav-step m 0 1 1 0))))
    (testing "the grid origin is a floor"
      (is (= [0 0] (app/nav-step m 0 0 -1 -1))))))

;; --- selection algebra ------------------------------------------------------

(deftest ranges-normalize-whichever-way-you-dragged
  (are [rng out] (= out (app/rng-norm rng))
    {:a [1 1] :f [3 4]} [1 1 3 4]
    {:a [3 4] :f [1 1]} [1 1 3 4]     ; dragged up-left
    {:a [3 1] :f [1 4]} [1 1 3 4]     ; dragged down-left
    {:a [2 2] :f [2 2]} [2 2 2 2]))

(deftest in-range-covers-the-normalized-rectangle
  (let [rng {:a [3 4] :f [1 1]}]
    (are [c r hit] (= hit (app/in-range? c r rng))
      1 1 true, 3 4 true, 2 2 true, 0 1 false, 4 4 false, 2 5 false)))

(deftest multi-means-more-than-one-cell
  ;; a predicate, so falsey is the contract — the empty case answers nil
  (testing "a lone cell is not multi — the server's own #self marker shows it"
    (reset! app/SEL {:ranges [{:a [1 1] :f [1 1]}]})
    (is (not (app/sel-multi?))))
  (testing "an empty selection is not multi either"
    (reset! app/SEL {:ranges []})
    (is (not (app/sel-multi?))))
  (testing "a wider range is"
    (reset! app/SEL {:ranges [{:a [1 1] :f [2 1]}]})
    (is (true? (app/sel-multi?))))
  (testing "and so are two single cells"
    (reset! app/SEL {:ranges [{:a [1 1] :f [1 1]} {:a [5 5] :f [5 5]}]})
    (is (true? (app/sel-multi?)))))

;; --- the bridge -------------------------------------------------------------

(deftest selecting-reports-the-active-cell-and-the-whole-selection
  ;; two different things go over the bridge on every selection change: sr-select
  ;; names the ACTIVE cell ($sel, the formula bar, presence for peers) and sr-sel
  ;; carries every range ($selcells, which is what style/clear/copy act on)
  (app/sel-single! 2 3)
  (is (= "C4" (:addr (detail "sr-select"))))
  (is (= "C4:C4" (:ranges (detail "sr-sel"))))

  (testing "extending moves the focus and grows the last range"
    (app/sel-extend! 4 5)
    (is (= "E6" (:addr (detail "sr-select"))))
    (is (= "C4:E6" (:ranges (detail "sr-sel")))))

  (testing "ctrl-click adds a second range, and the new cell becomes active"
    (app/sel-toggle! 0 0)
    (is (= "A1" (:addr (detail "sr-select"))))
    (is (= "C4:E6 A1:A1" (:ranges (detail "sr-sel")))))

  (testing "ctrl-clicking it again DESELECTS instead of stacking"
    (app/sel-toggle! 0 0)
    (is (= "C4:E6" (:ranges (detail "sr-sel"))))
    (is (= "E6" (:addr (detail "sr-select"))) "the survivor's focus is active again")))

(deftest deselecting-everything-still-tells-the-server
  ;; $selcells has to be cleared, or a style/clear action fires at a selection
  ;; the user can no longer see
  (app/sel-single! 0 0)
  (dom/clear-events!)
  (app/sel-toggle! 0 0)
  (is (= [] (:ranges @app/SEL)))
  (is (= "" (:ranges (detail "sr-sel"))))
  (is (nil? (dom/event "sr-select")) "no active cell to report"))

(deftest the-marquee-is-local-and-only-for-a-real-multi-selection
  ;; peers see the active cell (server-rendered #self); the rectangle is ours
  (app/sel-single! 1 1)
  (is (= "" (.-innerHTML (dom/el "selrange"))) "one cell draws nothing")
  (app/sel-extend! 3 2)
  (let [html (.-innerHTML (dom/el "selrange"))]
    ;; B2:D3 at the default size, window based at the origin
    (is (re-find #"left:112px" html))
    (is (re-find #"top:26px" html))
    (is (re-find #"width:335px" html))
    (is (re-find #"height:51px" html)))
  (testing "two ranges draw two rectangles"
    (app/sel-toggle! 6 6)
    (app/sel-extend! 7 7)
    (is (= 2 (count (re-seq #"position:absolute" (.-innerHTML (dom/el "selrange")))))))
  (testing "and it is drawn RELATIVE to the rendered window, not the grid"
    (dom/meta! {:cb 1 :rb 1})
    (app/sel-single! 1 1)
    (app/sel-extend! 3 2)
    (is (re-find #"left:0px" (.-innerHTML (dom/el "selrange"))))
    (is (re-find #"top:0px" (.-innerHTML (dom/el "selrange"))))))

(deftest merging-collapses-the-selection-onto-the-anchor
  (dom/meta! {:merges {"B2" [2 3]}})
  (testing "the range you just merged becomes the anchor — not the hidden corner"
    (app/sel-set! [{:a [1 1] :f [3 2]}])
    (app/snap-sel!)
    (is (= [{:a [1 1] :f [1 1]}] (:ranges @app/SEL)))
    (is (= "B2" (:addr (detail "sr-select")))))
  (testing "a selection reaching BEYOND the block is left alone"
    (app/sel-set! [{:a [1 1] :f [5 2]}])
    (dom/clear-events!)
    (app/snap-sel!)
    (is (= [{:a [1 1] :f [5 2]}] (:ranges @app/SEL)))
    (is (nil? (dom/event "sr-sel")) "a no-op must not chatter at the server"))
  (testing "and with no merges it is a no-op on every scroll"
    (dom/meta! {:merges {}})
    (app/sel-set! [{:a [0 0] :f [2 2]}])
    (dom/clear-events!)
    (app/snap-sel!)
    (is (nil? (dom/event "sr-sel")))))

;; --- clicks -----------------------------------------------------------------

(defn- click [a & {:keys [shift ctrl]}]
  #js {:target   (dom/cell-el a)
       :shiftKey (boolean shift)
       :ctrlKey  (boolean ctrl)
       :metaKey  false})

(deftest clicking-a-cell-selects-it
  (app/on-cell-click (click "B3"))
  (is (= [{:a [1 2] :f [1 2]}] (:ranges @app/SEL)))
  (testing "shift extends the range in place"
    (app/on-cell-click (click "D5" :shift true))
    (is (= [{:a [1 2] :f [3 4]}] (:ranges @app/SEL))))
  (testing "ctrl adds a second range"
    (app/on-cell-click (click "A1" :ctrl true))
    (is (= [{:a [1 2] :f [3 4]} {:a [0 0] :f [0 0]}] (:ranges @app/SEL))))
  (testing "a click on anything that is not a cell selects nothing"
    (dom/clear-events!)
    (app/on-cell-click #js {:target (dom/el "viewport")})
    (is (empty? (dom/events)))))

;; --- keyboard ---------------------------------------------------------------

(defn- key-ev [k & {:keys [shift mod]}]
  #js {:key            k
       :shiftKey       (boolean shift)
       :ctrlKey        (boolean mod)
       :metaKey        false
       :preventDefault (fn [] nil)})

(defn- active [] (app/sel-active))

(deftest arrows-move-the-active-cell
  (testing "with nothing selected the first arrow lands on A1"
    (app/on-key (key-ev "ArrowRight"))
    (is (= [0 0] (active))))
  (app/on-key (key-ev "ArrowRight"))
  (is (= [1 0] (active)))
  (app/on-key (key-ev "ArrowDown"))
  (is (= [1 1] (active)))
  (app/on-key (key-ev "ArrowLeft"))
  (is (= [0 1] (active)))
  (testing "up at the top edge stays put rather than going negative"
    (app/on-key (key-ev "ArrowUp"))
    (app/on-key (key-ev "ArrowUp"))
    (is (= [0 0] (active))))
  (testing "Tab is a right arrow, Shift+Tab a left one"
    (app/on-key (key-ev "Tab"))
    (is (= [1 0] (active)))
    (app/on-key (key-ev "Tab" :shift true))
    (is (= [0 0] (active)))))

(deftest shift-arrows-extend-and-tab-does-not
  (app/sel-single! 1 1)
  (app/on-key (key-ev "ArrowRight" :shift true))
  (app/on-key (key-ev "ArrowDown" :shift true))
  (is (= [{:a [1 1] :f [2 2]}] (:ranges @app/SEL)))
  (is (= "B2:C3" (:ranges (detail "sr-sel"))))
  (testing "Shift+Tab moves, it does not extend"
    (app/on-key (key-ev "Tab" :shift true))
    (is (= [{:a [1 2] :f [1 2]}] (:ranges @app/SEL)))))

(deftest arrows-skip-a-merged-block
  (dom/meta! {:merges {"B2" [2 3]}})
  (app/sel-single! 0 1)
  (app/on-key (key-ev "ArrowRight"))
  (is (= [1 1] (active)) "stepping in lands on the anchor")
  (app/on-key (key-ev "ArrowRight"))
  (is (= [4 1] (active)) "one more arrow clears the whole block"))

(deftest enter-opens-the-editor-over-the-active-cell
  (app/sel-single! 2 3)
  (dom/clear-events!)
  (app/on-key (key-ev "Enter"))
  (is (= "C4" (:addr (detail "sr-edit"))))
  (testing "and the floating input is sized and placed on that cell"
    (let [s (.-style (dom/el "editor"))]
      (is (= "224px" (.-left s)))
      (is (= "78px" (.-top s)))
      (is (= "111px" (.-width s)))
      (is (= "25px" (.-height s))))))

(deftest the-editor-spans-a-whole-merged-block
  (dom/meta! {:merges {"B2" [2 3]}})
  (app/sel-single! 1 1)
  (app/on-key (key-ev "Enter"))
  (let [s (.-style (dom/el "editor"))]
    (is (= "335px" (.-width s)) "3 columns wide")
    (is (= "51px" (.-height s)) "2 rows tall")))

(deftest the-modifier-keys-reach-the-server-unambiguously
  (app/sel-single! 0 0)
  (are [ev nm] (do (dom/clear-events!)
                   (app/on-key ev)
                   (= nm (:type (dom/event nm))))
    (key-ev "z" :mod true)             "sr-undo"
    (key-ev "z" :mod true :shift true) "sr-redo"
    (key-ev "y" :mod true)             "sr-redo"
    (key-ev "c" :mod true)             "sr-copy"
    (key-ev "x" :mod true)             "sr-cut"
    (key-ev "v" :mod true)             "sr-paste"
    (key-ev "Delete")                  "sr-clear"
    (key-ev "Backspace")               "sr-clear")
  (testing "the clipboard events carry the selection, not just the active cell"
    (app/sel-single! 1 1)
    (app/sel-extend! 2 2)
    (dom/clear-events!)
    (app/on-key (key-ev "c" :mod true))
    (is (= "B2:C3" (:ranges (detail "sr-copy"))))))

(deftest clipboard-and-clear-need-something-selected
  ;; with an empty selection these must fall through to the browser's own
  ;; copy/paste rather than posting an empty range
  (reset! app/SEL {:ranges []})
  (doseq [k ["c" "x" "v"]]
    (app/on-key (key-ev k :mod true)))
  (app/on-key (key-ev "Delete"))
  (is (empty? (dom/events))))

(deftest keys-are-ignored-while-a-field-has-focus
  ;; the editor and the toolbar own their own keys — including native text undo
  (app/sel-single! 1 1)
  (dom/clear-events!)
  (aset (dom/el "editor") "tagName" "INPUT")
  (aset js/document "activeElement" (dom/el "editor"))
  (app/on-key (key-ev "ArrowRight"))
  (app/on-key (key-ev "z" :mod true))
  (is (empty? (dom/events)))
  (is (= [1 1] (active)) "and the selection has not moved")
  (aset js/document "activeElement" nil))

;; --- wiring -----------------------------------------------------------------

(deftest the-client-boots-and-wires-itself
  (testing "datastar-ready is what starts everything"
    (is (contains? (dom/doc-listeners) "datastar-ready")))
  (testing "the grid owns wheel, click, dblclick and the resize grips"
    (is (= #{"wheel" "click" "dblclick" "mousedown"}
           (dom/listeners (dom/el "viewport")))))
  (testing "keyboard and the two delegated capture listeners live on document"
    (is (every? (dom/doc-listeners) ["keydown" "click" "pointerover" "focusin"])))
  (testing "and the collaboration stream is opened on boot"
    (is (some #{"sr-open"} (map :type @boot)))))

(deftest dragging-a-grip-emits-one-snapped-size
  ;; the whole resize gesture: mousedown on the grip, mousemove on document,
  ;; mouseup. Exactly ONE command crosses, and it is the snapped value the guide
  ;; was showing — not the raw pixel the pointer happened to stop on.
  (let [grip (-> (dom/el "grip-2") (dom/classed! "colgrip"))]
    (aset (.-dataset grip) "ci" "2")
    (dom/fire! (dom/el "viewport") "mousedown"
               #js {:target grip :clientX 100 :clientY 0
                    :preventDefault (fn [] nil) :stopPropagation (fn [] nil)})
    (is (= "sr-commit" (:type (dom/event "sr-commit")))
        "an open editor is committed first, or it floats over the re-render")
    (dom/fire-doc! "mousemove" #js {:clientX 210 :clientY 0 :altKey false})
    (is (nil? (dom/event "sr-size")) "nothing crosses mid-drag")
    (dom/fire-doc! "mouseup" #js {})
    (is (= "col:2:224" (:cmd (detail "sr-size"))) "222px snapped to 2x the default")))

(deftest a-grip-drag-with-alt-held-sizes-freely
  ;; 55px would stick to 52 (2x the default row height); Alt says leave it alone
  (let [grip (-> (dom/el "grip-5") (dom/classed! "rowgrip"))]
    (aset (.-dataset grip) "ri" "5")
    (dom/fire! (dom/el "viewport") "mousedown"
               #js {:target grip :clientX 0 :clientY 100
                    :preventDefault (fn [] nil) :stopPropagation (fn [] nil)})
    (dom/fire-doc! "mousemove" #js {:clientX 0 :clientY 129 :altKey true})
    (dom/fire-doc! "mouseup" #js {})
    (is (= "row:5:55" (:cmd (detail "sr-size"))))))
