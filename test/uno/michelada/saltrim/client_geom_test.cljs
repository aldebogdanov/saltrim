(ns uno.michelada.saltrim.client-geom-test
  "The client half of the axis arithmetic, run against the same vectors the
   server suite reads (`geom-vectors`). Everything here is pure — the numbers do
   not depend on a DOM — but `app` cannot be loaded without one, so the stub
   comes FIRST in the require list and installs the globals on load."
  (:require [cljs.test :refer-macros [deftest is testing are]]
            [uno.michelada.saltrim.dom-stub :as dom]
            [uno.michelada.saltrim.app :as app]
            [uno.michelada.saltrim.geom-vectors :as gv]
            [uno.michelada.saltrim.constants :refer [CW RH MAX-WIN-COLS MAX-WIN-ROWS MINSZ]]))

(defn- ov->js
  "The sparse override map as the client really receives it: a JSON object off
   #meta, so STRING keys and no reader to keywordize them."
  [ov]
  (js/JSON.parse (js/JSON.stringify (clj->js ov))))

;; --- the shared vectors -----------------------------------------------------

(deftest axis-offsets-match-the-server
  (doseq [{:keys [label base ov offsets sizes]} gv/axis-cases]
    (testing label
      (let [o (ov->js ov)]
        (doseq [[i px] offsets]
          (is (= px (app/axis-pos i base o)) (str "start px of index " i)))
        (doseq [[i sz] sizes]
          (is (= sz (app/axis-size i base o)) (str "size of index " i)))))))

(deftest scroll-px-inverts-the-layout
  ;; a scroll position names the index it lands IN — off by one here and the
  ;; client asks the server for the wrong window on every wheel tick
  (doseq [{:keys [label base ov at]} gv/index-cases]
    (testing label
      (let [o (ov->js ov)]
        (doseq [[px i] at]
          (is (= i (app/pixel->index px base o)) (str px "px falls in index " i)))))))

(deftest span-count-walks-real-sizes-like-the-server
  ;; THE twice-bitten bug: dividing the budget by the default size answers 16 for
  ;; every one of these, and the far edge of the grid renders empty
  (doseq [{:keys [label base ov i0 px cap n]} gv/span-cases]
    (testing label
      (is (= n (app/span-count i0 px base (ov->js ov) cap))))))

(deftest offsets-and-index-are-inverses
  ;; belt and braces over the hand-written vectors: whatever axis-pos says an
  ;; index starts at, pixel->index must hand that index back
  (doseq [{:keys [label base ov]} gv/axis-cases]
    (testing label
      (let [o (ov->js ov)]
        (doseq [i (range 0 24)]
          (let [x (app/axis-pos i base o)]
            (is (= i (app/pixel->index x base o)) (str "first px of index " i))
            (is (= i (app/pixel->index (+ x (dec (app/axis-size i base o))) base o))
                (str "last px of index " i))))))))

;; --- what the viewport asks for ---------------------------------------------

(def ^:private meta! dom/meta!)

(deftest win-need-reports-what-this-viewport-covers
  ;; only the browser knows how big it is; the server renders the window WE ask
  ;; for. +1 on each axis covers the partly-scrolled cell at the far edge.
  (testing "default cells: the count is the viewport over the real sizes"
    (meta! {})
    (dom/set-viewport! (* 10 CW) (* 8 RH))
    (is (= [11 9] (vec (app/win-need 0 0)))))
  (testing "a run of hand-shrunk columns needs more of them, not fewer"
    (meta! {:colw (into {} (for [i (range 64)] [i (quot CW 4)]))})
    (dom/set-viewport! (* 10 CW) (* 8 RH))
    (is (= 41 (first (app/win-need 0 0))) "quarter-width columns -> 4x as many"))
  (testing "counted from the window's OWN top-left, so a run behind us is gone"
    (is (= 11 (first (app/win-need 200 0)))))
  (testing "a smaller per-sheet default needs more cells for the same px"
    (meta! {:dcw (quot CW 2) :colw {}})
    (dom/set-viewport! (* 10 CW) (* 8 RH))
    (is (= 21 (first (app/win-need 0 0)))))
  (testing "the walk is bounded, so a huge viewport can't run the grid"
    ;; the +1 for the partly-scrolled edge cell puts the ask one past the cap;
    ;; win-dims clamps it server-side, which is where the real refusal belongs
    (meta! {:dcw MINSZ :drh MINSZ :colw {} :rowh {}})
    (dom/set-viewport! 10000000 10000000)
    (is (= [(inc MAX-WIN-COLS) (inc MAX-WIN-ROWS)] (vec (app/win-need 0 0))))))

(deftest an-unmeasured-viewport-still-asks-for-one-cell
  ;; before layout, clientWidth is 0 — asking for 0 columns would render nothing
  ;; at all, so span-count floors at 1 (and the +1 makes it 2)
  (meta! {})
  (dom/set-viewport! 0 0)
  (is (= [2 2] (vec (app/win-need 0 0)))))

(deftest meta-is-read-defensively
  ;; #meta is the client's only source of geometry and it arrives as text off
  ;; data-* attributes. A missing or malformed one must degrade to a usable
  ;; default — anything that throws in `mta` takes out every wheel tick, every
  ;; selection redraw and every window request at once.
  (testing "a page that has not rendered a window yet still yields geometry"
    (let [ds (.-dataset (dom/el "meta"))]
      (doseq [k ["tw" "th" "cb" "rb" "dcw" "drh" "colw" "rowh" "merges"]] (js-delete ds k)))
    (let [m (app/mta)]
      (is (= CW (:dcw m)) "falls back to the shared constant, not 0")
      (is (= RH (:drh m)))
      (is (= 0 (:cb m)))
      (is (= 1 (:tw m)) "a 1px extent scrolls nowhere, which is right")
      (is (zero? (count (js/Object.keys (:colw m)))))
      (is (zero? (count (js/Object.keys (:merges m)))))))
  (testing "malformed JSON reads as empty rather than throwing"
    (dom/meta! {})
    (aset (.-dataset (dom/el "meta")) "colw" "{oops")
    (is (zero? (count (js/Object.keys (:colw (app/mta))))))
    (is (= 112 (app/axis-pos 1 CW (:colw (app/mta)))))))

;; --- resize snapping --------------------------------------------------------

(deftest resize-snaps-to-multiples-of-the-sheet-default
  (testing "within SNAP px of a multiple it sticks"
    (are [raw out] (= out (app/snap-size raw CW false))
      112 112, 108 112, 116 112, 224 224, 220 224, 336 336))
  (testing "further away it stays where you put it"
    (are [raw] (= raw (app/snap-size raw CW false))
      150 90 200 300))
  (testing "Alt disables snapping entirely"
    (is (= 108 (app/snap-size 108 CW true))))
  (testing "it never snaps to zero — that would delete the column"
    (is (= 4 (app/snap-size 4 CW false)))
    (is (= 1 (app/snap-size 1 CW false))))
  (testing "rows snap on their own default"
    (is (= (* 2 RH) (app/snap-size (+ (* 2 RH) 3) RH false)))))
