(ns spikes.04-db-cell-storage
  "Spike: move sheet CELL storage from file EDN into Datahike as PER-PROPERTY,
   BRANCH-AWARE datoms — the foundation of git-like sheet branching (ROADMAP
   boss fight). Proves the schema + the `store` reimplementation strategy BEFORE
   touching app code.

   Model (decided 2026-06-21):
   - One entity per (sheet, branch, addr, PROPERTY). The cell's formula/literal
     is just the `:value` property; each style prop (:bg/:fg/:format/…) is its
     own property entity. Generic: a new style prop needs ZERO schema change.
   - Branch dimension baked into identity NOW (default \"main\"), so real
     branching layers on later with no key rework. Fork = copy a branch's
     cellprops under a new name; as-of = Datahike history per datom.
   - Hard cutover: import data/*.edn once, then DB is the only source.

   What this spike must prove:
   1. round-trip: write cellprops -> rebuild the {addr {:value :style}} document.
   2. save! must DIFF. FINDING (verified): Datahike with :keep-history? true
      records EVERY assertion in history — re-asserting an unchanged e-a-v is NOT
      a no-op, it logs a redundant history datom. So a blind whole-sheet
      re-transact on every edit would bloat history + drown real changes. The
      `store/save!` reimpl must compare runtime doc vs DB and transact ONLY
      changed props (+ retract removed). Proven: diff-save on an unchanged sheet
      = 0 history datoms; on a 1-cell change = only that cell's datom(s).
   3. per-property as-of: a cell value at an earlier tx is recoverable.
   4. branch dimension: the same addr lives independently under two branches;
      fork = copy a branch's cellprops under a new name.

   Run: clojure -M:nrepl --port 7888, then eval the (comment …) forms in order.")

;; --- proposed schema (subset; real version merges into db/schema) -----------

(def schema
  [{:db/ident :sheet/id      :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   ;; per (sheet, branch): the sheet-CONTENT scalars that vary by branch
   {:db/ident :branch/key    :db/valueType :db.type/string :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :branch/sheet  :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :branch/name   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :branch/dcw    :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :branch/drh    :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :branch/cols   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :branch/rows   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :branch/defs   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; per (sheet, branch, addr, prop): the unit of edit + history + branching
   {:db/ident :cellprop/key    :db/valueType :db.type/string  :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/sheet  :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/branch :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/addr   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/prop   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :cellprop/src    :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}])

;; --- helpers (shape of the real store reimpl) -------------------------------

(defn cp-key [sid branch addr prop] (str sid "|" branch "|" addr "|" (name prop)))

(defn cellprop-tx
  "Upsert tx-map for one (sheet,branch,addr,prop)->src, keyed by :cellprop/key.
   NOTE: re-transacting the same src is NOT free under :keep-history? true — it
   logs a redundant history datom. Only feed CHANGED props here (see diff-save!)."
  [sid branch addr prop src]
  {:cellprop/key (cp-key sid branch addr prop)
   :cellprop/sheet [:sheet/id sid]
   :cellprop/branch branch
   :cellprop/addr addr
   :cellprop/prop prop
   :cellprop/src src})

(defn doc->txes
  "A sheet document {addr {:value raw :style {prop raw}}} -> cellprop upsert
   tx-maps for (sid, branch). :value + each style prop becomes a property."
  [sid branch doc]
  (for [[addr {:keys [value style]}] doc
        [prop src] (cons [:value value] style)
        :when (some? src)]
    (cellprop-tx sid branch addr prop src)))

(defn load-doc
  "Rebuild {addr {:value raw :style {prop raw}}} for (sid, branch) from DB."
  [db sid branch]
  (->> (datahike.api/q '[:find ?addr ?prop ?src
                         :in $ ?sid ?br
                         :where [?sh :sheet/id ?sid]
                                [?c :cellprop/sheet ?sh]
                                [?c :cellprop/branch ?br]
                                [?c :cellprop/addr ?addr]
                                [?c :cellprop/prop ?prop]
                                [?c :cellprop/src ?src]]
                       db sid branch)
       (reduce (fn [acc [addr prop src]]
                 (if (= prop :value)
                   (assoc-in acc [addr :value] src)
                   (assoc-in acc [addr :style prop] src)))
               {})))

(comment
  (require '[datahike.api :as d])
  (require '[uno.michelada.saltrim.db :as db])
  (require '[clojure.edn :as edn])

  ;; fresh, isolated in-memory db (no H2 lock, no :8080) ----------------------
  (def cfg (db/mem-config))
  (d/create-database cfg)
  (def conn (d/connect cfg))
  (d/transact conn schema)
  (d/transact conn [{:sheet/id "dev-ann__budget"}])
  (def sid "dev-ann__budget")

  ;; 1. ROUND-TRIP -----------------------------------------------------------
  (def doc1 {"A1" {:value "99"}
             "B1" {:value "=(* #cell A1 2)"}
             "A2" {:value "=(+ 1 2)" :style {:bg "tomato" :align "center"}}})
  (d/transact conn (doc->txes sid "main" doc1))
  (load-doc @conn sid "main")
  ;; => doc1  (value + style props round-trip)

  ;; 2. SAVE! MUST DIFF (blind re-transact churns history) --------------------
  (defn hist-count [] (count (seq (d/datoms (d/history @conn) :eavt))))
  ;; blind: re-transact the SAME doc -> history GROWS (redundant assertions)
  (let [h0 (hist-count)] (d/transact conn (doc->txes sid "main" doc1)) (> (hist-count) h0))
  ;; => true  (NOT deduped — this is why save! can't blindly re-transact)

  ;; the real save!: compare runtime doc vs DB, transact ONLY changes + retracts
  (defn diff-save! [conn sid branch doc]
    (let [flat (fn [d] (into {} (for [[a {:keys [value style]}] d
                                      [p s] (cons [:value value] style) :when (some? s)] [[a p] s])))
          o (flat (load-doc @conn sid branch)) n (flat doc)
          changed (for [[[a p] s] n :when (not= s (get o [a p]))] (cellprop-tx sid branch a p s))
          removed (for [[[a p] _] o :when (not (contains? n [a p]))]
                    [:db/retractEntity [:cellprop/key (cp-key sid branch a p)]])]
      (when (seq (concat changed removed)) (d/transact conn (vec (concat changed removed))))
      {:changed (count changed) :removed (count removed)}))

  ;; identical -> ZERO work, ZERO history churn
  (let [h0 (hist-count) r (diff-save! conn sid "main" (load-doc @conn sid "main"))]
    (assoc r :history-delta (- (hist-count) h0)))
  ;; => {:changed 0 :removed 0 :history-delta 0}

  ;; change A1, drop B1 -> only those touched
  (diff-save! conn sid "main" (-> (load-doc @conn sid "main")
                                  (assoc-in ["A1" :value] "100") (dissoc "B1")))
  ;; => {:changed 1 :removed 1}
  (get-in (load-doc @conn sid "main") ["A2" :style])
  ;; => {:bg "tomato" :align "center"}

  ;; 3. PER-PROPERTY AS-OF: A1 :value before the 99->100 edit -----------------
  (d/q '[:find ?src ?tx :in $ ?k :where [?c :cellprop/key ?k] [?c :cellprop/src ?src ?tx]]
       (d/history @conn) (cp-key sid "main" "A1" :value))
  ;; => #{["99" <tx>] ["100" <tx>]}   (both values in history; as-of recovers "99")

  ;; 4. BRANCH DIMENSION: same addr, independent under "exp" -------------------
  (d/transact conn [(cellprop-tx sid "exp" "A1" :value "777")])
  [(get-in (load-doc @conn sid "main") ["A1" :value])
   (get-in (load-doc @conn sid "exp")  ["A1" :value])]
  ;; => ["100" "777"]   (branches isolated by key)

  ;; fork "main" -> "exp2": copy every cellprop under the new branch name ------
  (defn fork! [from to]
    (let [rows (d/q '[:find ?addr ?prop ?src :in $ ?sid ?br
                      :where [?sh :sheet/id ?sid] [?c :cellprop/sheet ?sh]
                             [?c :cellprop/branch ?br] [?c :cellprop/addr ?addr]
                             [?c :cellprop/prop ?prop] [?c :cellprop/src ?src]]
                    @conn sid from)]
      (d/transact conn (for [[addr prop src] rows] (cellprop-tx sid to addr prop src)))))
  (fork! "main" "exp2")
  (= (load-doc @conn sid "main") (load-doc @conn sid "exp2"))
  ;; => true   (fork is a pure copy; later edits diverge)

  (d/release conn)
  )
