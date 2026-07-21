(ns uno.michelada.saltrim.sse-test
  "The err/info toast channels are mutually exclusive (same screen corner): a
   handler setting one non-blank must not leave the other showing a stale
   message. Enforced centrally in `signals!` so every call site gets it for
   free — this locks that policy down at the wire level."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [starfederation.datastar.clojure.api :as d*]
            [uno.michelada.saltrim.web.sse :as sse]))

(defn- sent
  "The map `signals!` actually hands to patch-signals!, captured instead of
   opened over a real SSE connection."
  [m]
  (let [captured (atom nil)]
    (with-redefs [d*/patch-signals! (fn [_gen json-str] (reset! captured json-str))]
      (sse/signals! :fake-gen m))
    (json/read-value @captured json/keyword-keys-object-mapper)))

(deftest err-clears-a-stale-info
  (is (= {:err "boom" :info ""} (sent {:err "boom"}))))

(deftest info-clears-a-stale-err
  (is (= {:info "merged 3 cells" :err ""} (sent {:info "merged 3 cells"}))))

(deftest blank-err-does-not-clobber-info
  ;; clearing an error ({:err ""}) is not "setting an error" — a handler doing
  ;; so mid-flow must not blow away an info toast someone else just showed
  (is (= {:err ""} (sent {:err ""})) "no :info key injected for a blank err"))

(deftest blank-info-does-not-clobber-err
  (is (= {:info ""} (sent {:info ""}))))

(deftest explicit-value-is-never-overridden
  (testing "caller already cleared info alongside a real error — left alone"
    (is (= {:err "boom" :info "kept"} (sent {:err "boom" :info "kept"}))))
  (testing "same the other way"
    (is (= {:info "yay" :err "kept"} (sent {:info "yay" :err "kept"})))))

(deftest unrelated-signals-pass-through-untouched
  (is (= {:pcw "10" :prh "20" :err ""} (sent {:pcw "10" :prh "20" :err ""})))
  (is (= {:mergetake "" :mergefrom "" :branchpanel false :info "merged 1 cell" :err ""}
         (sent {:mergetake "" :mergefrom "" :branchpanel false :info "merged 1 cell"}))))

(deftest neither-channel-touched-when-neither-set
  (is (= {:pcw "10"} (sent {:pcw "10"}))))
