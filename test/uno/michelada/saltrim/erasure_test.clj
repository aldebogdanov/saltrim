(ns uno.michelada.saltrim.erasure-test
  "Account erasure has to survive `:keep-history? true`, which is on deliberately
   (as-of, branch lineage and merge-base all read history). A retraction under
   keep-history does not remove a datom, it records that it stopped being true —
   so every assertion here that matters is made against `d/history`, not against
   the current db. Checking the current db would pass for the broken version.

   See spikes/12-purge-erasure.clj for the same thing as a REPL walkthrough."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [mount.core :as mount]
            [uno.michelada.saltrim.db :as db]))

(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

(defn- in-history [attr v]
  (d/q '[:find [?e ...] :in $ ?a ?v :where [?e ?a ?v]] (d/history @db/conn) attr v))

(defn- ever-had? [attr v] (boolean (seq (in-history attr v))))

(defn- populate!
  "Ann owns two sheets with cells; one is shared with Bob. Ann also holds a grant
   on a sheet of Bob's. Her email CHANGES, so history carries a superseded one."
  []
  (db/upsert-user! {:uid "gh-ann" :name "Ann" :email "old@x.io" :provider "github"})
  (db/upsert-user! {:uid "gh-ann" :name "Ann" :email "ann@x.io" :avatar "https://x.io/a.png"})
  (db/upsert-user! {:uid "gh-bob" :name "Bob" :email "bob@x.io" :provider "github"})
  (db/put-token! "ann-token" "gh-ann")
  (db/put-agent-key! "ann-key" "gh-ann")
  (doseq [[id nm] [["gh-ann__budget" "budget"] ["gh-ann__notes" "notes"]]]
    (db/ensure-sheet! id "gh-ann" nm)
    (db/save-doc! id "main" {"A1" {:value "1" :bg "#fff"}} "gh-ann"))
  (db/ensure-sheet! "gh-bob__plan" "gh-bob" "plan")
  (db/set-share! "gh-ann__budget" "gh-bob" :user :read-write)   ; Bob can reach Ann's
  (db/set-share! "gh-bob__plan" "gh-ann" :user :read))          ; Ann can reach Bob's

(deftest the-plan-names-what-other-people-would-lose
  (populate!)
  (let [{:keys [sheets shared tokens agent-keys grants]} (db/user-erasure-plan "gh-ann")]
    (is (= ["gh-ann__budget" "gh-ann__notes"] sheets))
    (is (= ["gh-ann__budget"] shared) "only the one somebody else can reach")
    (is (= 1 tokens))
    (is (= 1 agent-keys))
    (is (= 1 grants) "the grant Ann holds on Bob's sheet")
    (testing "planning changes nothing"
      (is (= "ann@x.io" (:email (db/user-info "gh-ann")))))))

(deftest erasure-reaches-history-not-just-the-current-value
  (populate!)
  (testing "before: history carries the CURRENT and the SUPERSEDED email"
    (is (ever-had? :user/email "ann@x.io"))
    (is (ever-had? :user/email "old@x.io") "the address she changed away from"))
  (db/delete-user! "gh-ann")
  (testing "identifying attributes are gone from history too"
    (is (not (ever-had? :user/email "ann@x.io")))
    (is (not (ever-had? :user/email "old@x.io")) "including the superseded one")
    (is (not (ever-had? :user/name "Ann")))
    (is (not (ever-had? :user/avatar "https://x.io/a.png"))))
  (testing "credentials are gone from history too"
    (is (not (ever-had? :token/hash "ann-token")))
    (is (not (ever-had? :agentkey/hash "ann-key")))
    (is (nil? (db/token-uid "ann-token")) "and stop authenticating")
    (is (nil? (db/agent-key-uid "ann-key")))))

(deftest owned-sheets-go-with-their-content
  (populate!)
  (db/delete-user! "gh-ann")
  (is (empty? (db/sheets-of-owner "gh-ann")))
  (testing "the cells are purged, not merely retracted"
    (is (not (ever-had? :cellprop/key "gh-ann__budget|main|A1|value")))
    (is (not (ever-had? :cellprop/key "gh-ann__budget|main|A1|bg"))))
  (testing "so there is no as-of to travel back to"
    (is (empty? (db/branch-revisions "gh-ann__budget" "main")))))

(deftest what-belongs-to-other-people-survives
  (populate!)
  (db/delete-user! "gh-ann")
  (testing "Bob is untouched"
    (is (= "Bob" (:name (db/user-info "gh-bob"))))
    (is (= ["gh-bob__plan"] (db/sheets-of-owner "gh-bob"))))
  (testing "the grant Ann HELD on Bob's sheet is revoked"
    (is (empty? (db/sheet-grants "gh-bob__plan")))))

(deftest the-opaque-uid-is-kept-on-purpose
  ;; tier 3: the uid is baked into :sheet/id, hence into every :cellprop/key, and
  ;; :cellprop/author carries it on cells in sheets that are not the deleted
  ;; user's to rewrite. Once nothing maps it to a person it identifies nobody.
  (populate!)
  (db/delete-user! "gh-ann")
  (is (ever-had? :user/uid "gh-ann") "the key stays")
  (let [u (db/user-info "gh-ann")]
    (is (nil? (:name u)))
    (is (nil? (:email u)))
    (is (nil? (:avatar u))))
  (testing "signing in again starts a fresh account under the same key"
    (db/upsert-user! {:uid "gh-ann" :name "Ann" :email "ann@x.io"})
    (is (= "Ann" (:name (db/user-info "gh-ann"))))
    (is (empty? (db/sheets-of-owner "gh-ann")) "with none of the old sheets back")))

(deftest erasing-an-account-with-nothing-in-it-is-fine
  (db/upsert-user! {:uid "gh-solo" :name "Solo"})
  (let [plan (db/delete-user! "gh-solo")]
    (is (= [] (:sheets plan)))
    (is (= 0 (:tokens plan)))
    (is (nil? (:name (db/user-info "gh-solo"))))))

;; --- idle tokens ------------------------------------------------------------

(deftest idle-tokens-expire-and-used-ones-do-not
  (db/upsert-user! {:uid "gh-ann" :name "Ann"})
  (db/put-token! "fresh" "gh-ann")
  (db/put-token! "stale" "gh-ann")
  ;; age one of them past the window
  (let [eid (d/q '[:find ?t . :in $ ?h :where [?t :token/hash ?h]] @db/conn "stale")]
    (d/transact db/conn [{:db/id eid
                          :token/last-seen (- (System/currentTimeMillis)
                                              db/TOKEN-IDLE-MS 1000)}]))
  (is (= 1 (db/sweep-tokens!)))
  (is (= "gh-ann" (db/token-uid "fresh")))
  (is (nil? (db/token-uid "stale")))
  (testing "an expired credential leaves no trace to correlate against"
    (is (not (ever-had? :token/hash "stale")))))

(deftest touching-a-token-keeps-it-alive
  (db/upsert-user! {:uid "gh-ann" :name "Ann"})
  (db/put-token! "t" "gh-ann")
  (let [eid  (d/q '[:find ?t . :in $ ?h :where [?t :token/hash ?h]] @db/conn "t")
        old  (- (System/currentTimeMillis) db/TOKEN-IDLE-MS 1000)]
    (d/transact db/conn [{:db/id eid :token/last-seen old}])
    (db/touch-token! "t")
    (is (= 0 (db/sweep-tokens!)) "using it postponed the sweep")
    (is (= "gh-ann" (db/token-uid "t")))
    (testing "and the touch is lazy — a fresh token is not rewritten"
      (let [seen (d/q '[:find ?s . :in $ ?h
                        :where [?t :token/hash ?h] [?t :token/last-seen ?s]]
                      @db/conn "t")]
        (db/touch-token! "t")
        (is (= seen (d/q '[:find ?s . :in $ ?h
                           :where [?t :token/hash ?h] [?t :token/last-seen ?s]]
                         @db/conn "t")))))))
