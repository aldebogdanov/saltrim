(ns uno.michelada.saltrim.violations-test
  "The room-level violation diff. An assertion is a STATE — a cell does not
   violate once, it IS violating until fixed — but a toast is an EVENT. This is
   the piece that turns one into the other, so it decides what a user actually
   hears: too eager and every edit re-announces the same known problem, too lazy
   and a fresh one passes in silence."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.state :as state]))

(def ^:dynamic *sh* nil)
(def ^:dynamic *room* nil)

(use-fixtures :each
  (fn [t]
    (let [sh   (sheet/create-sheet)
          room ["viol-test__s" "main"]]
      ;; register the room the way `sheet-rec` would, without touching the db
      (swap! state/sheets* assoc room {:sh sh :owner "u"})
      (try (binding [*sh* sh *room* room] (t))
           (finally (swap! state/sheets* dissoc room)
                    (sheet/close! sh))))))

(defn- refresh [] (state/refresh-violations! *room* *sh*))

(defn- break! [addr]
  (sheet/set-cell! *sh* addr "-1")
  (sheet/set-style! *sh* addr sheet/assert-prop "=(> $val 0)")
  (sheet/settle! *sh*))

(defn- fix! [addr]
  (sheet/set-cell! *sh* addr "1")
  (sheet/settle! *sh*))

(deftest a-clean-sheet-reports-nothing
  (let [{:keys [now new fixed]} (refresh)]
    (is (= {} now))
    (is (= {} new))
    (is (= #{} fixed))))

(deftest a-new-violation-is-news-exactly-once
  (break! "A1")
  (let [{:keys [new now]} (refresh)]
    (is (= ["A1"] (keys new)))
    (is (= ["A1"] (keys now))))
  (testing "a second look at an UNCHANGED problem says nothing — this is what
            stops ten identical cards for one broken cell, since any edit
            anywhere re-checks the sheet"
    (let [{:keys [new now]} (refresh)]
      (is (= {} new))
      (is (= ["A1"] (keys now)) "still failing, just not news"))))

(deftest fixing-is-reported-and-then-forgotten
  (break! "A1")
  (refresh)
  (fix! "A1")
  (let [{:keys [now new fixed]} (refresh)]
    (is (= {} now))
    (is (= {} new))
    (is (= #{"A1"} fixed)))
  (testing "and the next look is quiet"
    (is (= #{} (:fixed (refresh))))))

(deftest a-changed-REASON-counts-as-news-again
  ;; the message is what the user acts on, so the same cell failing differently
  ;; is a different problem — silence here would leave stale advice on screen
  (break! "A1")
  (refresh)
  (sheet/set-cell! *sh* "A1" "abc")     ; now it THROWS rather than being false
  (sheet/settle! *sh*)
  (let [{:keys [new]} (refresh)]
    (is (= ["A1"] (keys new)))
    (is (re-find #"could not be checked" (get new "A1")))))

(deftest several-cells-breaking-at-once-are-all-reported
  ;; one edit can flip a whole column of dependents; the caller decides whether
  ;; to draw them individually or summarize, but the diff must not lose any
  (doseq [a ["A1" "A2" "A3" "A4"]] (break! a))
  (let [{:keys [new now]} (refresh)]
    (is (= ["A1" "A2" "A3" "A4"] (keys new)))
    (is (= 4 (count now)))))

(deftest one-cell-breaking-while-another-is-fixed-reports-both-halves
  (break! "A1")
  (break! "B1")
  (refresh)
  (fix! "A1")
  (break! "C1")
  (let [{:keys [now new fixed]} (refresh)]
    (is (= ["C1"] (keys new)) "only the new one is announced")
    (is (= #{"A1"} fixed))
    (is (= ["B1" "C1"] (keys now)) "B1 was failing before and still is")))

(deftest the-count-is-what-survives-a-reload
  ;; the toast is gone after F5; `now` is what the ⚠ badge renders from, and it
  ;; is recomputed from the sheet rather than remembered
  (break! "A1")
  (break! "B2")
  (refresh)
  (is (= 2 (count (state/violations *room*))))
  (testing "a fresh room with the same sheet arrives at the same answer"
    (swap! state/sheets* assoc-in [*room* :violations] {})
    (is (= 2 (count (:now (refresh)))))))
