(ns uno.michelada.saltrim.mcp-test
  "The MCP server: JSON-RPC shape, token-gated access, and the auto-fork rule
   (an agent's writes never touch main)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [mount.core :as mount]
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
      (is (= #{"saltrim_describe_sheet" "saltrim_read_range" "saltrim_write_cells"}
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
        branch (mcp/agent-branch *tok*)]
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
    (is (not= (mcp/agent-branch *tok*) (mcp/agent-branch "ffffffffffffffff")))))

(deftest reads-see-the-agent-branch-and-default-to-main
  (tool "saltrim_write_cells" {:cells [{:addr "A1" :src "7"}]})
  (let [branch (mcp/agent-branch *tok*)]
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
    (is (= (mcp/agent-branch *tok*) (:writes_go_to d)))
    (is (str/includes? (:note d) "main is never edited"))))
