(ns uno.michelada.saltrim.mcp-test
  "The MCP server: JSON-RPC shape, token-gated access, and the auto-fork rule
   (an agent's writes never touch main)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [mount.core :as mount]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.mcp :as mcp]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.web.state :as state]))

(def ^:dynamic *sid* nil)
(def ^:dynamic *tok* nil)

;; Fresh in-memory Datahike per test (same pattern as db-test), plus a clean
;; room registry so one test's loaded engine can't leak into the next.
(use-fixtures :each
  (fn [t]
    (db/start-mem!)
    (reset! state/sheets* {})
    (let [sid (store/storage-id "agentuser" "book")]
      (db/ensure-sheet! sid "agentuser" "book")
      (let [tok (:token (db/set-link-level! sid :read-write))]
        (try (binding [*sid* sid *tok* tok] (t))
             (finally (reset! state/sheets* {}) (mount/stop)))))))

(defn- req [msg & [token]]
  {:request-method :post :uri "/mcp"
   :headers (cond-> {} token (assoc "authorization" (str "Bearer " token)))
   :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string msg) "UTF-8"))})

(defn- call [msg & [token]]
  (let [r (mcp/handle-mcp (req msg token))]
    (assoc (select-keys r [:status])
           :body (some-> (:body r) (json/read-value json/keyword-keys-object-mapper)))))

(defn- tool [nm args & [token]]
  (-> (call {:jsonrpc "2.0" :id 1 :method "tools/call"
             :params {:name nm :arguments args}}
            (or token *tok*))
      :body :result))

;; --- protocol ---------------------------------------------------------------

(deftest handshake-and-dispatch
  (let [{:keys [status body]} (call {:jsonrpc "2.0" :id 1 :method "initialize"
                                     :params {:protocolVersion mcp/PROTOCOL-VERSION}}
                                    *tok*)]
    (is (= 200 status))
    (is (= mcp/PROTOCOL-VERSION (get-in body [:result :protocolVersion])))
    (is (= "saltrim" (get-in body [:result :serverInfo :name]))))
  (testing "a notification (no id) gets 202 and NO body — required by the spec"
    (is (= {:status 202 :body nil}
           (call {:jsonrpc "2.0" :method "notifications/initialized"} *tok*))))
  (testing "unknown method is a JSON-RPC error, not a crash"
    (is (= -32601 (get-in (:body (call {:jsonrpc "2.0" :id 2 :method "resources/list"} *tok*))
                          [:error :code]))))
  (testing "tools are advertised with schemas"
    (let [ts (get-in (:body (call {:jsonrpc "2.0" :id 3 :method "tools/list"} *tok*)) [:result :tools])]
      (is (= #{"saltrim_list_sheets" "saltrim_describe_sheet"
               "saltrim_read_range" "saltrim_write_cells"}
             (set (map :name ts))))
      (is (every? #(and (:description %) (:inputSchema %)) ts)))))

;; --- auth: the TOKEN decides the sheet, never an argument -------------------

(deftest token-gates-access
  (testing "no credential at all"
    (is (= 401 (:status (call {:jsonrpc "2.0" :id 1 :method "tools/list"})))))
  (testing "a bogus/revoked token is refused"
    (is (= 403 (:status (call {:jsonrpc "2.0" :id 1 :method "tools/list"} "deadbeefdeadbeef")))))
  (testing "a valid token resolves to exactly its own sheet"
    (let [c (mcp/credential *tok*)]
      (is (= *sid* (:sheet-id c)))
      (is (= :read-write (:level c)))))
  (testing "revoking the link kills the credential"
    (db/set-link-level! *sid* nil)
    (is (nil? (mcp/credential *tok*)))))

(deftest read-only-token-cannot-write
  (db/set-link-level! *sid* :read)
  (let [r (tool "saltrim_write_cells" {:cells [{:addr "A1" :src "1"}]})]
    (is (:isError r))
    (is (str/includes? (get-in r [:content 0 :text]) "read-only")))
  (testing "but it can still read"
    (is (not (:isError (tool "saltrim_read_range" {:range "A1:B2"}))))))

;; --- the auto-fork rule -----------------------------------------------------

(deftest agent-writes-never-touch-main
  (let [r (:structuredContent (tool "saltrim_write_cells"
                                    {:cells [{:addr "A1" :src "10"}
                                             {:addr "A2" :src "=(* $A1 4)"}]}))
        branch (mcp/agent-branch (mcp/credential *tok*) *tok*)]
    (testing "the write landed on the agent's own forked branch"
      (is (= branch (:branch r)))
      (is (not= db/MAIN (:branch r)))
      (is (db/branch-exists? *sid* branch)))
    (testing "and main is untouched"
      (is (empty? (db/sheet-doc *sid* db/MAIN))))
    (testing "the agent gets COMPUTED values back — its reactive feedback loop"
      (is (= [{:addr "A1" :value 10}
              {:addr "A2" :value 40 :src "=(* $A1 4)"}]
             (mapv #(select-keys % [:addr :value :src]) (:cells r)))))))

(deftest fork-is-idempotent-and-per-credential
  (tool "saltrim_write_cells" {:cells [{:addr "A1" :src "1"}]})
  (let [n (count (db/branch-names *sid*))]
    (tool "saltrim_write_cells" {:cells [{:addr "A2" :src "2"}]})
    (is (= n (count (db/branch-names *sid*))) "second write reuses the same branch"))
  (testing "a different token would work on its own branch"
    (is (not= (mcp/agent-branch (mcp/credential *tok*) *tok*) (mcp/agent-branch {:kind :link} "ffffffffffffffff")))))

(deftest reads-see-the-agent-branch-and-default-to-main
  (tool "saltrim_write_cells" {:cells [{:addr "A1" :src "7"}]})
  (let [branch (mcp/agent-branch (mcp/credential *tok*) *tok*)]
    (is (= [] (:cells (:structuredContent (tool "saltrim_read_range" {:range "A1:A1"}))))
        "main is empty — the agent's write isn't there")
    (is (= [{:addr "A1" :value 7}]
           (mapv #(select-keys % [:addr :value])
                 (:cells (:structuredContent
                          (tool "saltrim_read_range" {:range "A1:A1" :branch branch})))))
        "reading the agent branch shows it")))

;; --- errors are RESULTS the agent can act on -------------------------------

(deftest tool-failures-are-readable-results
  (doseq [[args frag] [[{:cells [{:addr "nope" :src "1"}]} "A1 style"]
                       [{:cells []} "no cells"]]]
    (let [r (tool "saltrim_write_cells" args)]
      (is (:isError r))
      (is (str/includes? (get-in r [:content 0 :text]) frag))))
  (testing "a bad formula reports the engine's own message, not a 500"
    (let [r (tool "saltrim_write_cells" {:cells [{:addr "A1" :src "=(bogus 1)"}]})]
      (is (:isError r))))
  (testing "a bad range is actionable"
    (is (str/includes? (get-in (tool "saltrim_read_range" {:range "??"}) [:content 0 :text])
                       "A1-style")))
  (testing "an unknown tool name"
    (is (:isError (tool "no_such_tool" {})))))

(deftest read-range-is-capped
  (let [r (:structuredContent (tool "saltrim_read_range" {:range "A1:ZZ9999"}))]
    (is (:truncated r))
    (is (str/includes? (:note r) "smaller"))))

(deftest describe-tells-the-agent-where-writes-go
  (let [d (:structuredContent (tool "saltrim_describe_sheet" {}))]
    (is (= *sid* (:sheet d)))
    (is (= "read-write" (:level d)))
    (is (= (mcp/agent-branch (mcp/credential *tok*) *tok*) (:writes_go_to d)))
    (is (str/includes? (:note d) "main is never edited"))))

;; --- account-level agent key ------------------------------------------------
;; A per-USER credential: one key reaches every sheet its owner can, so adding a
;; sheet needs no config change. Reach widens; AUTHORITY does not — every call is
;; still checked against that user's real ACL.

(defn- account-fixture
  "Two real users, each owning a sheet, plus an agent key for the first. Returns
   {:key :mine :theirs :uid}."
  []
  (db/upsert-user! {:uid "alice" :name "Alice"})
  (db/upsert-user! {:uid "bob" :name "Bob"})
  (let [mine   (store/storage-id "alice" "budget")
        theirs (store/storage-id "bob" "secret")]
    (db/ensure-sheet! mine "alice" "budget")
    (db/ensure-sheet! theirs "bob" "secret")
    {:key (auth/mint-agent-key! "alice") :mine mine :theirs theirs :uid "alice"}))

(deftest account-key-authenticates-a-user
  (let [{:keys [key uid]} (account-fixture)
        c (mcp/credential key)]
    (is (= :account (:kind c)))
    (is (= uid (:uid c)) "writes are authored by the real user, not a synthetic id")
    (is (str/starts-with? key "srk_") "the secret is self-identifying in configs")
    (testing "a bogus key authenticates nothing"
      (is (nil? (mcp/credential "srk_deadbeef"))))))

(deftest account-key-lists-every-reachable-sheet
  (let [{:keys [key mine theirs]} (account-fixture)
        r (:structuredContent (tool "saltrim_list_sheets" {} key))
        ids (set (map :sheet (:sheets r)))]
    (is (contains? ids mine) "own sheet is listed")
    (is (not (contains? ids theirs)) "someone else's sheet is NOT listed")
    (testing "a sheet shared with me shows up too"
      (db/set-share! theirs "alice" :user :read)
      (let [r2 (:structuredContent (tool "saltrim_list_sheets" {} key))]
        (is (contains? (set (map :sheet (:sheets r2))) theirs))))))

(deftest account-key-reaches-many-sheets-without-reconfiguration
  (let [{:keys [key mine]} (account-fixture)
        other (store/storage-id "alice" "forecast")]
    (db/ensure-sheet! other "alice" "forecast")
    (doseq [s [mine other]]
      (let [r (:structuredContent (tool "saltrim_write_cells"
                                        {:sheet s :cells [{:addr "A1" :src "7"}]} key))]
        (is (= s (:sheet r)) "the same key wrote to a different sheet")
        (is (= 7 (:value (first (:cells r)))))
        (is (not= db/MAIN (:branch r)) "still auto-forks — main is never touched")))))

(deftest account-key-cannot-reach-what-its-owner-cannot
  ;; the whole safety argument for a broader token: reach follows the ACL
  (let [{:keys [key theirs]} (account-fixture)]
    (doseq [[nm args] [["saltrim_read_range"  {:sheet theirs :range "A1:A1"}]
                       ["saltrim_write_cells" {:sheet theirs :cells [{:addr "A1" :src "x"}]}]
                       ["saltrim_describe_sheet" {:sheet theirs}]]]
      (let [r (tool nm args key)]
        (is (:isError r) (str nm " must refuse a sheet the user has no grant on"))
        (is (str/includes? (get-in r [:content 0 :text]) "no access"))))
    (testing "a read-only grant still refuses writes"
      (db/set-share! theirs "alice" :user :read)
      (is (not (:isError (tool "saltrim_read_range" {:sheet theirs :range "A1:A1"} key))))
      (let [w (tool "saltrim_write_cells" {:sheet theirs :cells [{:addr "A1" :src "x"}]} key)]
        (is (:isError w))
        (is (str/includes? (get-in w [:content 0 :text]) "read-only"))))))

(deftest account-key-asks-which-sheet-when-omitted
  (let [{:keys [key]} (account-fixture)
        r (tool "saltrim_read_range" {:range "A1:A1"} key)]
    (is (:isError r))
    (is (str/includes? (get-in r [:content 0 :text]) "saltrim_list_sheets")
        "the error points the agent at the tool that fixes it")))

(deftest rotating-a-key-revokes-the-old-one
  (let [{:keys [key uid]} (account-fixture)
        key2 (auth/mint-agent-key! uid)]
    (is (not= key key2))
    (is (nil? (mcp/credential key)) "the old key stops working the moment a new one is minted")
    (is (= uid (:uid (mcp/credential key2))))
    (testing "revoking leaves no key at all"
      (auth/revoke-agent-key! uid)
      (is (nil? (mcp/credential key2)))
      (is (nil? (auth/agent-key-info uid))))))

(deftest rotating-a-key-keeps-the-agent-branch
  ;; the branch must follow the USER, not the key: deriving it from the token
  ;; meant rotating stranded whatever the agent had been building (and put four
  ;; characters of the secret into a branch name).
  (let [{:keys [key uid mine]} (account-fixture)
        b1 (mcp/agent-branch (mcp/credential key) key)]
    (tool "saltrim_write_cells" {:sheet mine :cells [{:addr "A1" :src "1"}]} key)
    (let [key2 (auth/mint-agent-key! uid)
          b2   (mcp/agent-branch (mcp/credential key2) key2)]
      (is (= b1 b2) "same branch after rotation — the agent resumes its work")
      (is (not (str/includes? b1 (subs key 4 12))) "no secret material in the branch name")
      (tool "saltrim_write_cells" {:sheet mine :cells [{:addr "A2" :src "2"}]} key2)
      (is (= #{db/MAIN b1} (set (db/branch-names mine)))
          "the rotated key wrote to the SAME branch, not a new orphan"))))

(deftest key-info-never-exposes-the-secret
  (let [{:keys [key uid]} (account-fixture)
        info (auth/agent-key-info uid)]
    (is (some? (:created-at info)))
    (is (not (str/includes? (pr-str info) key)) "the secret is not recoverable from the DB")))

(deftest a-link-credential-stays-confined-to-its-sheet
  ;; the account key widens reach; the OLD per-sheet link must not inherit that
  (let [{:keys [mine]} (account-fixture)
        r (tool "saltrim_read_range" {:sheet mine :range "A1:A1"})]  ; *tok* = link for *sid*
    (is (:isError r))
    (is (str/includes? (get-in r [:content 0 :text]) "scoped to the sheet"))))
