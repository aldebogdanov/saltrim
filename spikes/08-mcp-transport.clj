(ns spikes.08-mcp-transport
  "SPIKE 08 — an MCP server for SaltRim, in-process over the existing http-kit
   server.

   THE RISKY UNKNOWN is not mutating a sheet (we do that all day) — it's the
   TRANSPORT: can we serve MCP's JSON-RPC over the server we already run, and
   does a tool call reach the SAME seam the web handlers use, so an agent's edit
   is persisted, branch-scoped and BROADCAST to the humans in the room?

   Why in-process and not a side-process:
   sheet state lives in the in-memory `sheets*` rooms keyed [sheet-id branch]
   (plus Datahike). A separate process poking the db would be a SECOND WRITER
   bypassing the room, the edit-lock, the autosave and the broadcast — the
   browser peers would see nothing and two engines on one (sheet,branch) would
   diverge. So the MCP tools must call `sheet-rec` -> `set-cell!` -> `settle!`
   -> `save-rec!` -> `broadcast!`, exactly like `handlers/handle-cell`.

   What this spike proves, in order:
     1. JSON-RPC 2.0 codec + dispatch is a handful of pure functions.
     2. The MCP `initialize` / `tools/list` / `tools/call` handshake shape.
     3. A ring handler that answers all three — callable with a synthetic
        request, so it can be mounted in web.clj's router as one more route.
     4. A tool call mutates a LIVE room and broadcasts to peers (open the sheet
        in a browser and watch the cell change while you eval).
     5. Auth: the capability-link token already models exactly what an agent
        needs (one sheet, :read or :read-write, rotatable/revocable).

   Eval the forms in the (comment …) block one at a time at a dev REPL."
  (:require [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.web.state :as state]
            [uno.michelada.saltrim.web.collab :as collab]))

;; ---------------------------------------------------------------------------
;; 1. JSON-RPC 2.0 — the whole wire format, in about 20 lines.
;; ---------------------------------------------------------------------------

(def ^:private mapper json/keyword-keys-object-mapper)

(defn rpc-result [id v] {:jsonrpc "2.0" :id id :result v})
(defn rpc-error  [id code msg]
  {:jsonrpc "2.0" :id id :error {:code code :message msg}})

;; standard codes we actually need
(def ERR-PARSE -32700)
(def ERR-METHOD -32601)
(def ERR-PARAMS -32602)
(def ERR-INTERNAL -32603)

;; ---------------------------------------------------------------------------
;; 2. The MCP handshake.
;;
;; `initialize` answers with the protocol version the CLIENT asked for (echoing
;; a version we support), our capabilities and serverInfo. `notifications/*`
;; carry no :id — they are notifications and MUST get no response body.
;; ---------------------------------------------------------------------------

(def PROTOCOL-VERSION "2025-06-18")

(defn initialize-result [_params]
  {:protocolVersion PROTOCOL-VERSION
   :capabilities    {:tools {:listChanged false}}
   :serverInfo      {:name "saltrim" :version "0.1.0"}})

;; ---------------------------------------------------------------------------
;; 3. Tools.
;;
;; Batch-first: an agent that writes one cell per tool call burns context and
;; round-trips. `saltrim_write_cells` takes many cells at once and returns the
;; COMPUTED values after the engine settles — that read-back is the agent's
;; feedback loop (it wrote formulas; it needs to see what they evaluate to).
;; ---------------------------------------------------------------------------

(def tools
  [{:name "saltrim_read_range"
    :description (str "Read a rectangular range of cells from a SaltRim sheet. "
                      "Returns each cell's computed VALUE and, when it is a formula, "
                      "its SOURCE. Use this before writing to see what is already there.")
    :inputSchema {:type "object"
                  :properties {:sheet {:type "string" :description "storage id, e.g. alice__budget"}
                               :branch {:type "string" :description "branch name (default \"main\")"}
                               :range {:type "string" :description "A1:D10"}}
                  :required ["sheet" "range"]}
    :annotations {:readOnlyHint true :destructiveHint false :idempotentHint true}}

   {:name "saltrim_write_cells"
    :description (str "Write many cells at once. Each cell's `src` is either a literal "
                      "(\"42\", \"hello\") or a formula starting with = , e.g. \"=(sum $A1:A10)\". "
                      "Prefer FORMULAS over pre-computed numbers: the sheet is reactive, so a "
                      "formula keeps recalculating for the user. Returns the computed values after "
                      "the engine settles.")
    :inputSchema {:type "object"
                  :properties {:sheet {:type "string"}
                               :branch {:type "string"}
                               :cells {:type "array"
                                       :items {:type "object"
                                               :properties {:addr {:type "string"}
                                                            :src {:type "string"}}
                                               :required ["addr" "src"]}}}
                  :required ["sheet" "cells"]}
    :annotations {:readOnlyHint false :destructiveHint true :idempotentHint true}}])

;; --- tool implementations (the SAME seam handlers/handle-cell uses) ---------

(defn- room-of
  "Acquire (load-or-create) the live engine for [sheet-id branch] — this is the
   in-memory room the browser sessions share, NOT a private copy."
  [sheet-id branch]
  (let [[owner _] (store/split-id sheet-id)]
    (state/sheet-rec sheet-id branch owner)))

(defn read-range [{:keys [sheet branch range]}]
  (let [branch (or branch db/MAIN)
        {:keys [sh]} (room-of sheet branch)
        [a b] (clojure.string/split (str range) #":")]
    {:cells (vec (for [ad (addr/range-cells a (or b a))
                       :let [v (sheet/value sh ad)
                             src (sheet/raw sh ad)]
                       :when (some? src)]
                   (cond-> {:addr ad :value (if (map? v) "#ERR" v)}
                     (= :formula (sheet/kind sh ad)) (assoc :src src))))}))

(defn write-cells
  "Mutate through the room, settle, persist, and BROADCAST to the humans."
  [{:keys [sheet branch cells]} author]
  (let [branch (or branch db/MAIN)
        room   [sheet branch]
        {:keys [sh]} (room-of sheet branch)]
    (doseq [{:keys [addr src]} cells]
      (sheet/set-cell! sh addr (str src)))
    (sheet/settle! sh)
    (state/save-rec! room author)
    ;; nil editor-sid = "not one of the browser sessions", so EVERY session in
    ;; the room gets the patch — the agent's edit shows up live.
    (collab/broadcast! nil room sh (map :addr cells))
    {:cells (vec (for [{:keys [addr]} cells
                       :let [v (sheet/value sh addr)]]
                   {:addr addr :value (if (map? v) "#ERR" v)}))}))

(defn call-tool [{:keys [name arguments]} author]
  (case name
    "saltrim_read_range"  (read-range arguments)
    "saltrim_write_cells" (write-cells arguments author)
    (throw (ex-info (str "unknown tool: " name) {:tool name}))))

(defn- tool-content
  "MCP tool results are content blocks. Return the JSON as text AND as
   structuredContent so both old and new clients can read it."
  [v]
  {:content [{:type "text" :text (json/write-value-as-string v)}]
   :structuredContent v})

;; ---------------------------------------------------------------------------
;; 4. Dispatch + the ring handler (this is what web.clj would mount).
;; ---------------------------------------------------------------------------

(defn handle-rpc [{:keys [method params id]} author]
  (case method
    "initialize"  (rpc-result id (initialize-result params))
    "tools/list"  (rpc-result id {:tools tools})
    "tools/call"  (try (rpc-result id (tool-content (call-tool params author)))
                       (catch Throwable e
                         ;; tool failures are RESULTS with isError, not protocol errors,
                         ;; so the agent can read the message and retry
                         (rpc-result id {:isError true
                                         :content [{:type "text"
                                                    :text (str "Error: " (.getMessage e))}]})))
    (rpc-error id ERR-METHOD (str "unknown method: " method))))

(defn mcp-handler
  "POST /mcp — one JSON-RPC message in, one out. A NOTIFICATION (no :id) gets
   202 with no body. Auth: the capability-link token in the Authorization
   header resolves to a sheet + level, exactly like ?t= in the browser."
  [req]
  (let [body  (some-> (:body req) slurp (json/read-value mapper))
        token (some-> (get-in req [:headers "authorization"])
                      (clojure.string/replace #"^Bearer " ""))
        uid   (str "mcp:" (subs (str token) 0 (min 8 (count (str token)))))]
    (cond
      (nil? body) {:status 400 :body (json/write-value-as-string
                                      (rpc-error nil ERR-PARSE "invalid JSON"))}
      (nil? (:id body)) {:status 202}          ; notification — no response body
      :else
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string (handle-rpc body uid))})))

(defn- req
  "Build a synthetic ring request carrying a JSON-RPC message."
  [msg & [token]]
  {:request-method :post :uri "/mcp"
   :headers (cond-> {} token (assoc "authorization" (str "Bearer " token)))
   :body (java.io.ByteArrayInputStream.
          (.getBytes (json/write-value-as-string msg) "UTF-8"))})

(defn- call! [msg & [token]]
  (let [r (mcp-handler (req msg token))]
    (some-> (:body r) (json/read-value mapper))))

(comment
  ;; ── 0. bring the system up (dev/user.clj is auto-loaded) ────────────────
  (start)

  ;; ── 1. the handshake ────────────────────────────────────────────────────
  (call! {:jsonrpc "2.0" :id 1 :method "initialize"
          :params {:protocolVersion "2025-06-18"
                   :capabilities {}
                   :clientInfo {:name "claude-code" :version "1.0"}}})
  ;; => {:jsonrpc "2.0", :id 1,
  ;;     :result {:protocolVersion "2025-06-18",
  ;;              :capabilities {:tools {:listChanged false}},
  ;;              :serverInfo {:name "saltrim", :version "0.1.0"}}}
  ;; PROVES: the whole handshake is one pure function over a decoded map.

  ;; a notification (no :id) must produce NO body — 202 and silence
  (mcp-handler (req {:jsonrpc "2.0" :method "notifications/initialized"}))
  ;; => {:status 202}

  ;; an unknown method is a protocol error, not a crash
  (call! {:jsonrpc "2.0" :id 2 :method "resources/list"})
  ;; => {... :error {:code -32601, :message "unknown method: resources/list"}}

  ;; ── 2. tools/list ───────────────────────────────────────────────────────
  (map :name (:tools (:result (call! {:jsonrpc "2.0" :id 3 :method "tools/list"}))))
  ;; => ("saltrim_read_range" "saltrim_write_cells")

  ;; ── 3. a real tool call against a LIVE room ─────────────────────────────
  ;; Open http://localhost:8080/?s=mcpspike in a browser FIRST and leave it
  ;; open — you should SEE these cells appear without touching the page.
  (def sid (store/storage-id "dev-tester" "mcpspike"))
  (db/ensure-sheet! sid "dev-tester" "mcpspike")

  (call! {:jsonrpc "2.0" :id 4 :method "tools/call"
          :params {:name "saltrim_write_cells"
                   :arguments {:sheet sid
                               :cells [{:addr "A1" :src "10"}
                                       {:addr "A2" :src "32"}
                                       {:addr "A3" :src "=(sum $A1:A2)"}]}}})
  ;; => :structuredContent {:cells [{:addr "A1" :value 10}
  ;;                                {:addr "A2" :value 32}
  ;;                                {:addr "A3" :value 42}]}
  ;; PROVES: the agent wrote a FORMULA and got the computed value back — the
  ;; feedback loop — and the browser showed it live (broadcast!).

  ;; edit a dependency and the formula follows, for the agent too
  (call! {:jsonrpc "2.0" :id 5 :method "tools/call"
          :params {:name "saltrim_write_cells"
                   :arguments {:sheet sid :cells [{:addr "A1" :src "100"}]}}})
  (call! {:jsonrpc "2.0" :id 6 :method "tools/call"
          :params {:name "saltrim_read_range"
                   :arguments {:sheet sid :range "A1:A3"}}})
  ;; => A3 :value 132, :src "=(sum $A1:A2)"
  ;; PROVES: reads see the reactive recompute; formulas are preserved as source.

  ;; ── 4. a failing tool is a RESULT, not a protocol error ─────────────────
  (call! {:jsonrpc "2.0" :id 7 :method "tools/call"
          :params {:name "saltrim_write_cells"
                   :arguments {:sheet sid :cells [{:addr "B1" :src "=(bogus 1)"}]}}})
  ;; => :result {:isError true :content [{:type "text" :text "Error: …"}]}
  ;; so the agent reads the message and can retry (per MCP guidance).

  ;; ── 5. auth: the capability link IS the agent credential ────────────────
  ;; a link token already resolves to (sheet, level) and is rotatable/revocable
  (def tok (:token (db/set-link-level! sid :read-write)))   ; also: (db/link-grant sid)
  (db/sheet-by-link-token tok)        ; => sid
  (db/access-level nil sid tok)       ; => :read-write
  ;; => the real handler gates on this instead of trusting :sheet in the args.

  (stop))

;; ---------------------------------------------------------------------------
;; FINDINGS — all of the below were evaluated against a running system.
;;
;; 1. NO LIBRARY NEEDED. The whole transport is a codec + a `case` + one ring
;;    route (~60 lines above). `initialize` / `tools/list` / `tools/call` all
;;    answered correctly; a NOTIFICATION (no :id) correctly produced {:status
;;    202} with no body; an unknown method produced a -32601 protocol error.
;;    It mounts in web.clj's existing `case [(:request-method req) (:uri req)]`.
;;
;; 2. STATELESS JSON IS ENOUGH. A plain JSON response is a legal reply to a
;;    POST under streamable HTTP; SSE is only needed for server->client
;;    notifications, which request/response tools do not use. No Mcp-Session-Id,
;;    no second SSE channel. (SaltRim already runs its own SSE for the browser —
;;    keeping MCP stateless avoids two overlapping stream lifecycles.)
;;
;; 3. THE ROOM SEAM IS THE WHOLE POINT — VERIFIED IN A BROWSER.
;;    With the sheet open in a browser (1 registered session) and NOT touched,
;;    a `saltrim_write_cells` call made C1/C2 appear in the DOM with no reload:
;;      agent writes -> set-cell! -> settle! -> save-rec! -> broadcast!
;;    `broadcast!` with a nil editor-sid means "no browser session authored
;;    this", so every session in the room gets the patch. The agent is a
;;    first-class collaborator the human WATCHES, not a background job.
;;
;; 4. THE REACTIVE FEEDBACK LOOP WORKS FOR AGENTS.
;;    Writing "=(sum $A1:A2)" returned :value 42 in the SAME tool result (the
;;    engine settles first). Changing A1 to 100 then re-reading gave A3 = 132
;;    with :src "=(sum $A1:A2)" preserved. So an agent can write a FORMULA,
;;    see what it evaluates to, and the user keeps a live model — not the dead
;;    numbers a paste-the-answer agent leaves behind.
;;
;; 5. TOOL FAILURES ARE RESULTS, NOT PROTOCOL ERRORS.
;;    A bad formula came back as {:isError true :content [{:text "Error:
;;    Unable to resolve symbol: bogus"}]} — the agent can read it and retry.
;;    Same for an unknown tool name. The engine's own guards (cycle detection,
;;    SCI sandbox, caps) apply unchanged, so a hostile agent can't wedge it.
;;
;; 6. AUTH IS ALREADY BUILT. `db/set-link-level!` -> token;
;;    `db/sheet-by-link-token` resolved it to the sheet; `db/access-level`
;;    returned :read-write; a bogus token resolved to nil. The capability link
;;    IS the agent credential: one sheet, read or read-write, rotatable and
;;    revocable, with no new auth concepts.
;;
;; OPEN (for the real PR, not the spike):
;; - AUTH GATING: this spike trusts the :sheet argument. The real handler must
;;   resolve the TOKEN to a sheet + level (mirroring handlers/with-access) and
;;   refuse a :sheet the token doesn't grant — an agent token must not be able
;;   to name an arbitrary sheet. Write tools must also refuse :read level.
;; - UNDO: the per-session undo stack lives in `web` per browser TAB; an MCP
;;   writer has no tab, so agent edits are currently un-undoable by the human.
;;   Give the MCP session a synthetic session id with its own stack, or lean on
;;   branches (fork -> agent works -> human reviews the 3-way merge) — the
;;   latter is the better product answer and the real differentiator.
;; - PAGINATION/CAPS: `saltrim_read_range` must cap its rectangle (MCP guidance:
;;   respect a limit, report has_more). A1:ZZ99999 would currently expand.
;; - PROMPT INJECTION: cell content is untrusted input reaching an agent. Tool
;;   output should be framed as data, never as instructions.
;; ---------------------------------------------------------------------------
