;; Spike 12 — erasure under :keep-history?, i.e. can "delete my account" actually
;; delete anything?
;;
;; The problem: SaltRim runs Datahike with `:keep-history? true`, deliberately —
;; `as-of` time-travel, branch lineage and merge-base all read history. Every
;; delete path in `db.clj` was `:db/retractEntity`, and a retraction under
;; keep-history does NOT remove the datom, it records that it stopped being true.
;; So a "deleted" email is still queryable through `d/history`, and so is every
;; earlier address the user replaced along the way. That is the opposite of what
;; a deletion request asks for.
;;
;; The question this spike answers: does Datahike 0.8.1746 give us real erasure,
;; and does it reach history?
;;
;; Answer: yes — `:db/purge`, `:db.purge/entity`, `:db.purge/attribute` and
;; `:db.history.purge/before` are all in datahike.db.transaction/builtin-op?.
;; Eval the forms below at a dev REPL (see spikes/README.md).

(require '[datahike.api :as d])

;; --- a store shaped like ours (history ON) ---------------------------------

(def cfg {:store {:backend :memory :id #uuid "11111111-2222-3333-4444-555555555555"}
          :keep-history?      true
          :schema-flexibility :write
          :attribute-refs?    false})

(comment
  (d/delete-database cfg)
  (d/create-database cfg))

(def conn (d/connect cfg))

(d/transact conn [{:db/ident :user/uid   :db/valueType :db.type/string
                   :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                  {:db/ident :user/email :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :user/name  :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :token/hash :db/valueType :db.type/string
                   :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                  {:db/ident :token/user :db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/one}])

(d/transact conn [{:user/uid "github-1" :user/email "a@example.com" :user/name "A"}])
;; CHANGE it, so history holds a superseded value too. This is the case that
;; makes retraction useless: the old address was never "current" to begin with.
(d/transact conn [{:user/uid "github-1" :user/email "b@example.com"}])
(d/transact conn [{:token/hash "deadbeef" :token/user [:user/uid "github-1"]}])

(defn emails-now []     (d/q '[:find [?e ...] :where [_ :user/email ?e]] @conn))
(defn emails-history [] (d/q '[:find [?e ...] :where [_ :user/email ?e]] (d/history @conn)))
(defn tokens-history [] (d/q '[:find [?h ...] :where [_ :token/hash ?h]] (d/history @conn)))

(comment
  (emails-now)      ;=> ("b@example.com")
  (emails-history)  ;=> ("a@example.com" "b@example.com")   <- BOTH. the point.
  )

;; --- what a retraction actually does ---------------------------------------

(comment
  ;; retract the whole user and the address is still right there in history
  (let [eid (d/q '[:find ?e . :where [?e :user/uid "github-1"]] @conn)]
    (d/transact conn [[:db/retractEntity eid]])
    (emails-history))
  ;=> ("a@example.com" "b@example.com")
  )

;; --- what a purge does ------------------------------------------------------

(def uid-eid (d/q '[:find ?e . :where [?e :user/uid "github-1"]] @conn))

(comment
  ;; 1. one attribute off a live entity — tier 1, what identifies a person
  (d/transact conn [[:db.purge/attribute uid-eid :user/email]])
  (emails-now)      ;=> ()
  (emails-history)  ;=> ()      <- the superseded value went too

  ;; 2. a whole entity — tier 1 again, credentials
  (let [t (d/q '[:find ?e . :where [?e :token/hash "deadbeef"]] @conn)]
    (d/transact conn [[:db.purge/entity t]]))
  (tokens-history)  ;=> []

  ;; 3. the uid SURVIVES — tier 3, the opaque key we keep on purpose
  (d/q '[:find ?u . :where [?e :user/uid ?u]] @conn)   ;=> "github-1"
  )

;; --- what this fixed in the real code ---------------------------------------
;;
;; `db/delete-user!` purges (1) and (2) and leaves (3). `db/delete-sheet!` gained
;; a `purge?` arity used only by account deletion — the ordinary "delete this
;; sheet" button still retracts, because there history is a feature.
;;
;; Two traps worth knowing, both hit while writing this:
;;
;;  - `:db.purge/attribute` on an attribute the entity does NOT have is an error,
;;    not a no-op. `delete-user!` pulls first and only purges what is present
;;    (an avatar is often absent).
;;  - order matters inside a sheet: cellprops/branches/shares REF the sheet
;;    entity, so the sheet goes last, exactly as the retracting path already did.
;;
;; And the limit that no database can fix: purge does not reach BACKUPS. The
;; privacy notice states a 30-day window for that, which is the honest number.
