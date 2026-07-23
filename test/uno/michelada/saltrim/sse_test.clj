(ns uno.michelada.saltrim.sse-test
  "A toast is an element appended to the page's `#toasts` list, not a signal
   written into a slot — that is what lets messages stack instead of overwriting
   each other, and what puts each card's whole lifetime (click, and a CSS
   animation that ends by removing an info card) in the markup the server sends
   once. `signals!` is the single choke point every handler patches through, so
   these tests pin the translation at the wire level."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [starfederation.datastar.clojure.api :as d*]
            [uno.michelada.saltrim.web.sse :as sse]))

(defn- capture
  "Run `signals!` with the SSE writes captured instead of opened over a real
   connection: `{:cards [[html opts] …] :signals <map or ::none>}`."
  [m]
  (let [cards (atom [])
        sigs  (atom ::none)]
    (with-redefs [d*/patch-elements! (fn [_gen html opts] (swap! cards conj [html opts]))
                  d*/patch-signals!  (fn [_gen s] (reset! sigs (json/read-value s json/keyword-keys-object-mapper)))]
      (sse/signals! :fake-gen m))
    {:cards @cards :signals @sigs}))

(defn- cards [m] (mapv first (:cards (capture m))))

(deftest a-message-is-an-appended-element-not-a-signal
  (let [{:keys [cards signals]} (capture {:err "boom"})]
    (is (= 1 (count cards)))
    (let [[html opts] (first cards)]
      (is (= "#toasts" (get opts d*/selector)))
      (is (= d*/pm-append (get opts d*/patch-mode))
          "append — anything else would replace the cards already up")
      (is (str/includes? html "boom")))
    (is (= ::none signals) "nothing left to send as signals")))

(deftest an-info-card-dismisses-itself-and-an-error-card-does-not
  (let [[info] (cards {:info "merged 3 cells"})
        [err]  (cards {:err "boom"})]
    (is (str/includes? info "class=\"toast info\""))
    (is (str/includes? err  "class=\"toast err\""))
    (testing "the animation's end is what removes an info card"
      (is (str/includes? info "data-on:animationend=\"el.remove()\"")))
    (testing "an error waits to be acknowledged"
      (is (not (str/includes? err "animationend"))))
    (testing "either way a click takes it away"
      (is (every? #(str/includes? % "data-on:click=\"el.remove()\"") [info err])))))

(deftest messages-stack-rather-than-replacing-each-other
  (testing "two channels in one call are two cards, not one winner"
    (let [[a b] (cards {:err "boom" :info "saved"})]
      (is (str/includes? a "boom"))
      (is (str/includes? b "saved"))))
  (testing "and the same message twice is two cards — no de-duplication by text"
    ;; the old single-slot signal made this a silent no-op: the value never
    ;; changed, so the second report of an identical mistake showed nothing
    (let [[a b] (concat (cards {:err "bad formula"}) (cards {:err "bad formula"}))]
      (is (every? #(str/includes? % "bad formula") [a b]))
      (is (not= a b) "distinct ids, so morphing can never fold them together"))))

(deftest a-blank-channel-says-nothing
  ;; it used to CLEAR the shared slot; there is no slot now, and a card belongs
  ;; to whoever it was sent to
  (is (= [] (cards {:err ""})))
  (is (= [] (cards {:info ""})))
  (is (= [] (cards {:err "" :info ""}))))

(deftest a-message-is-escaped
  ;; messages routinely carry user text — a formula, a sheet name, an exception
  (let [[html] (cards {:err "bad ref <img src=x onerror=alert(1)> in =A1"})]
    (is (not (str/includes? html "<img")))
    (is (str/includes? html "&lt;img"))))

(deftest other-signals-are-untouched
  (is (= {:pcw "10" :prh "20"} (:signals (capture {:pcw "10" :prh "20"}))))
  (testing "a call carrying both goes out as both: cards AND the rest as signals"
    (let [{:keys [cards signals]} (capture {:mergetake "" :branchpanel false :info "merged 1 cell"})]
      (is (= 1 (count cards)))
      (is (= {:mergetake "" :branchpanel false} signals)))))

(deftest an-empty-call-still-patches
  ;; /stream's on-open flush is an empty signals patch: a persistent SSE that has
  ;; sent nothing looks finished to the client and it reconnect-storms
  (is (= {} (:signals (capture {})))))
