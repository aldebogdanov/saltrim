(ns uno.michelada.saltrim.room-lifecycle-test
  "Who may cause a sheet to be LOADED, and who releases it afterwards."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.web.collab :as collab]
            [uno.michelada.saltrim.web.state :as state]
            [mount.core :as mount]))

(use-fixtures :each (fn [t]
                      (db/start-mem!)
                      (reset! state/sheets* {})
                      (reset! state/sessions* {})
                      (try (t)
                           (finally
                             (doseq [[_ {:keys [sh]}] @state/sheets*] (sheet/close! sh))
                             (reset! state/sheets* {})
                             (mount/stop)))))

(defn- seed-alice-sheet! []
  (db/upsert-user! {:uid "alice" :name "Alice"})
  (db/upsert-user! {:uid "mallory" :name "Mallory"})
  (let [rec (state/sheet-rec "alice__budget" db/MAIN "alice")]
    (sheet/set-cell! (:sh rec) "A1" "42")
    (state/save-rec! ["alice__budget" db/MAIN] "alice")
    ;; start from cold, as a fresh process would
    (sheet/close! (:sh rec))
    (reset! state/sheets* {})))

(deftest a-stranger-cannot-make-us-load-a-sheet
  ;; the answer was always correct — but the sheet used to be LOADED (engine
  ;; built, definitions evaluated, ensure-sheet!/migrate written) before the ACL
  ;; was consulted, so any signed-in user could pin a stranger's sheet in memory
  ;; by guessing ?u=alice&s=budget
  (seed-alice-sheet!)
  (testing "denied, and nothing was loaded on the way to saying no"
    (is (nil? (state/accessible-rec "mallory" "alice__budget" db/MAIN nil)))
    (is (empty? @state/sheets*)))
  (testing "the owner still reaches it"
    (is (some? (state/accessible-rec "alice" "alice__budget" db/MAIN nil)))
    (is (= 1 (count @state/sheets*))))
  (testing "and so does someone actually granted access"
    (reset! state/sheets* {})
    (db/set-share! "alice__budget" "mallory" :user :read)
    (is (some? (state/accessible-rec "mallory" "alice__budget" db/MAIN nil)))))

(deftest a-room-no-session-owns-is-eventually-released
  ;; rooms are normally unloaded when their last session leaves, but an MCP call
  ;; or a bare GET loads one that no reap-session! will ever reach
  (seed-alice-sheet!)
  (let [room ["alice__budget" db/MAIN]]
    (state/sheet-rec "alice__budget" db/MAIN "alice")
    (is (contains? @state/sheets* room))
    (testing "a freshly touched room survives the sweep (never yank a load mid-handshake)"
      (collab/sweep!)
      (is (contains? @state/sheets* room)))
    (testing "once it has been idle past ROOM-IDLE-MS the sweep releases it"
      (swap! state/sheets* assoc-in [room :at]
             (- (state/now) state/ROOM-IDLE-MS 1))
      (collab/sweep!)
      (is (not (contains? @state/sheets* room))))
    (testing "and its content was saved on the way out"
      (is (= {"A1" {:value "42"}} (db/sheet-doc "alice__budget" db/MAIN))))))

(deftest a-room-with-a-live-session-is-left-alone
  (seed-alice-sheet!)
  (let [room ["alice__budget" db/MAIN]]
    (collab/ensure-session! "sid-1" "alice__budget" db/MAIN "alice")
    (swap! state/sheets* assoc-in [room :at] (- (state/now) state/ROOM-IDLE-MS 1))
    (collab/sweep!)
    (is (contains? @state/sheets* room) "a session still holds it")))
