(ns uno.michelada.saltrim.mcp
  "MCP (Model Context Protocol) server for SaltRim, served in-process over the
   existing http-kit server as one route: POST /mcp.

   Why in-process: sheet state lives in the in-memory `sheets*` rooms keyed
   [sheet-id branch]. A side-process poking Datahike would be a SECOND WRITER
   bypassing the room, the autosave and the broadcast — browser peers would see
   nothing and two engines on one (sheet,branch) would diverge. So every tool
   goes through the SAME seam `web.handlers` uses:
     sheet-rec -> set-cell! -> settle! -> save-rec! -> broadcast!
   which makes an agent a first-class collaborator the human WATCHES live.

   AGENT WRITES AUTO-FORK. A tool call never edits `main`. The first write on a
   credential forks main into that credential's own branch (see `agent-branch`)
   and every later write lands there, so the human reviews the agent's work
   through the existing owner-only 3-way merge (🌿 modal) instead of waking up to
   rewritten cells. Every result reports the branch it wrote to.

   AUTH: `Authorization: Bearer <token>` accepts two credentials (`credential`):

   - an ACCOUNT AGENT KEY (`srk_…`) authenticating a USER. One key reaches every
     sheet that user owns or was granted, so pointing an agent at a new sheet
     needs no config change. The sheet then comes from a tool argument — but
     `resolve-sheet` authorizes it against that user's real ACL on EVERY call,
     so the key widens reach without widening authority. Minted/rotated/revoked
     from the 🔑 panel; only its hash is stored.
   - a per-sheet CAPABILITY LINK (the original). The token alone picks the
     sheet and a tool argument can never name another one.

   Transport notes (proved in spikes/08-mcp-transport.clj): the protocol needs no
   library — a JSON-RPC codec, a dispatch case and one route. Request/response
   tools need no server->client notifications, so this is STATELESS: a plain JSON
   reply to each POST, no Mcp-Session-Id, no second SSE lifecycle beside the
   browser's own stream."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.util :as u]
            [uno.michelada.saltrim.version :as version]
            [uno.michelada.saltrim.web.collab :refer [broadcast!]]
            [uno.michelada.saltrim.web.state :refer [edit-lock save-rec! sheet-rec]]))

(def PROTOCOL-VERSION "2025-06-18")

(def MAX-READ-CELLS
  "Cap on one `read_range` rectangle. A range is expanded eagerly, and an agent
   that asks for A1:ZZ99999 would otherwise blow up both the server and its own
   context. Reported back as a truncation, not an error."
  2000)

(def MAX-WRITE-CELLS 1000)

(def ^:private mapper json/keyword-keys-object-mapper)

;; --- JSON-RPC 2.0 ----------------------------------------------------------

(def ^:private ERR-PARSE   -32700)
(def ^:private ERR-METHOD  -32601)
(def ^:private ERR-PARAMS  -32602)

(defn- rpc-result [id v] {:jsonrpc "2.0" :id id :result v})
(defn- rpc-error  [id code msg] {:jsonrpc "2.0" :id id :error {:code code :message msg}})

;; --- credential ------------------------------------------------------------

(defn- bearer
  "The Bearer token from the Authorization header, or nil."
  [req]
  (some-> (get-in req [:headers "authorization"])
          (str/replace #"(?i)^bearer\s+" "")
          str/trim
          not-empty))

(defn credential
  "Resolve a Bearer token to a credential, or nil when it grants nothing. Two
   kinds, tried in order:

   - `:account` — an AGENT KEY (`srk_…`, `auth/agent-key->uid`). Authenticates a
     USER, so every sheet they own or were granted is reachable with one
     credential and no per-sheet configuration. The sheet then comes from a tool
     argument, but `resolve-sheet` re-checks it against that user's real ACL on
     every call, so this widens reach WITHOUT widening authority.
   - `:link` — a per-sheet capability link (the original). The TOKEN alone picks
     the sheet; a tool argument can never name another one.

   `:uid` is the author recorded on cells this agent writes: the real user for
   an account key, a synthetic `mcp-<tok8>` for an anonymous link."
  [token]
  (or (when-let [uid (auth/agent-key->uid token)]
        {:kind :account :uid uid})
      (when-let [sheet-id (db/sheet-by-link-token token)]
        (when-let [level (db/access-level nil sheet-id token)]
          {:kind :link :sheet-id sheet-id :level level
           :uid (str "mcp-" (subs token 0 8))}))))

(defn- level-for
  "This credential's effective level on `sheet-id`: owners get :read-write,
   everyone else whatever the ACL grants them. nil = no access."
  [{:keys [uid]} sheet-id]
  (let [[owner _] (store/split-id sheet-id)]
    (if (= uid owner) :read-write (db/access-level uid sheet-id nil))))

(defn resolve-sheet
  "The sheet a call targets, as {:sheet-id :level} — the single authorization
   gate every tool goes through.

   For a LINK credential the token's own sheet wins and a mismatching `sheet`
   argument is refused outright. For an ACCOUNT credential the argument selects
   the sheet, but it is authorized against that user's ACL, so an agent key can
   only reach what its owner can. Throws with an actionable message otherwise."
  [{:keys [kind sheet-id] :as cred} requested]
  (let [requested (not-empty (str requested))]
    (if (= kind :link)
      (if (and requested (not= requested sheet-id))
        (throw (ex-info (str "this credential is scoped to the sheet \"" sheet-id
                             "\" and cannot reach \"" requested "\"") {}))
        {:sheet-id sheet-id :level (:level cred)})
      (cond
        (nil? requested)
        (throw (ex-info (str "which sheet? pass `sheet` — call saltrim_list_sheets "
                             "to see the ones you can reach") {}))
        (not (store/valid-id? requested))
        (throw (ex-info (str "not a valid sheet id: " (pr-str requested)) {}))
        :else
        (if-let [level (level-for cred requested)]
          {:sheet-id requested :level level}
          (throw (ex-info (str "no access to sheet \"" requested
                               "\" (call saltrim_list_sheets for the ones you can reach)")
                          {})))))))

(defn agent-branch
  "The branch this credential works on — stable across stateless calls, so an
   agent resumes its own work, and distinct per agent so two never collide.
   Never `main`.

   For an ACCOUNT key it is derived from the USER, not the key: rotating a key
   must not strand the branch the agent was working on (and no part of the
   secret belongs in a branch name). For a LINK there is no user identity to
   key on, so a prefix of the (capability, semi-public) token is used."
  [{:keys [kind uid]} token]
  (let [raw (if (= kind :account)
              (str "agent-" uid)
              (str "agent-" (subs token 0 8)))]
    ;; branch names are [A-Za-z0-9-]{1,32} (store/valid-branch?)
    (subs (str/replace raw #"[^A-Za-z0-9-]" "-") 0 (min 32 (count raw)))))

(defn- ensure-agent-branch!
  "Fork `main` into this credential's branch on first write, so agent edits never
   land on main. Idempotent. Returns the branch name."
  [sheet-id cred token]
  (let [b (agent-branch cred token)]
    (when-not (db/branch-exists? sheet-id b)
      (db/fork-branch! sheet-id db/MAIN b)
      (u/log "INFO" "mcp" "forked" sheet-id db/MAIN "->" b))
    b))

;; --- tool definitions ------------------------------------------------------

(def ^:private sheet-arg
  {:type "string"
   :description (str "storage id of the sheet, e.g. \"alice__budget\" (from "
                     "saltrim_list_sheets). Optional for a single-sheet link "
                     "credential, which is already scoped to its own sheet.")})

(def tools
  [{:name "saltrim_list_sheets"
    :description
    (str "List every SaltRim sheet this credential can reach, with your access "
         "level on each. CALL THIS FIRST — it gives you the sheet ids the other "
         "tools need. SaltRim is a REACTIVE spreadsheet: cells hold Clojure "
         "formulas that keep recalculating, not frozen values.")
    :inputSchema {:type "object" :properties {} :additionalProperties false}
    :annotations {:readOnlyHint true :destructiveHint false :idempotentHint true
                  :openWorldHint false}}

   {:name "saltrim_describe_sheet"
    :description
    (str "Describe one sheet: its id, your access level, the branch your writes "
         "go to, and the branches that exist.")
    :inputSchema {:type "object" :properties {:sheet sheet-arg}}
    :annotations {:readOnlyHint true :destructiveHint false :idempotentHint true
                  :openWorldHint false}}

   {:name "saltrim_read_range"
    :description
    (str "Read a rectangular range of cells. Returns each non-empty cell's computed "
         "VALUE plus, for formula cells, the formula SOURCE. Read before you write "
         "so you don't clobber existing work. Reads default to the 'main' branch — "
         "pass branch to read back your own edits.")
    :inputSchema
    {:type "object"
     :properties {:sheet  sheet-arg
                  :range  {:type "string"
                           :description "A1-style rectangle, e.g. \"A1:D20\" (or a single cell \"B7\")"}
                  :branch {:type "string"
                           :description "branch to read (default \"main\"); use your agent branch to see your own writes"}}
     :required ["range"]}
    :annotations {:readOnlyHint true :destructiveHint false :idempotentHint true
                  :openWorldHint false}}

   {:name "saltrim_write_cells"
    :description
    (str "Write many cells at once, and return what they compute to. Each cell's "
         "`src` is either a literal (\"42\", \"Revenue\") or a FORMULA starting with "
         "`=`, e.g. \"=(sum $A1:A10)\" or \"=(* $B2 1.2)\". "
         "PREFER FORMULAS over numbers you calculated yourself: the sheet is "
         "reactive, so a formula keeps recalculating for the user, while a pasted "
         "number goes stale the moment an input changes. "
         "Reference a cell with $A1 and a range with $A1:B9. "
         "YOUR WRITES DO NOT TOUCH main: they land on your own branch, which the "
         "sheet owner reviews and merges. The result reports that branch.")
    :inputSchema
    {:type "object"
     :properties {:sheet sheet-arg
                  :cells {:type "array"
                          :description "cells to write, applied in order"
                          :items {:type "object"
                                  :properties {:addr {:type "string" :description "A1-style address, e.g. \"B7\""}
                                               :src  {:type "string" :description "literal, or =formula"}}
                                  :required ["addr" "src"]}}}
     :required ["cells"]}
    :annotations {:readOnlyHint false :destructiveHint false :idempotentHint true
                  :openWorldHint false}}])

;; --- tool implementations --------------------------------------------------

(defn- room-of [sheet-id branch]
  (let [[owner _] (store/split-id sheet-id)]
    (sheet-rec sheet-id branch owner)))

(defn- cell-out [sh a]
  (let [v (sheet/value sh a)]
    (cond-> {:addr a :value (if (map? v) "#ERR" v)}
      (= :formula (sheet/kind sh a)) (assoc :src (sheet/raw sh a)))))

(defn- parse-range
  "\"A1:D20\" or \"B7\" -> [from to]. Throws with an actionable message."
  [range]
  (let [[a b] (str/split (str range) #":")
        b (or b a)]
    (when-not (and (addr/valid? a) (addr/valid? b))
      (throw (ex-info (str "bad range " (pr-str range)
                           " — use an A1-style rectangle like \"A1:D20\"") {})))
    [a b]))

(defn list-sheets
  "Every sheet this credential can reach. An ACCOUNT key sees the user's own
   sheets plus everything shared with them; a LINK is scoped to its one sheet."
  [{:keys [kind uid sheet-id level] :as cred}]
  (if (= kind :link)
    {:sheets [{:sheet sheet-id :name (second (store/split-id sheet-id))
               :level (name level) :owned false}]
     :note "This credential is a per-sheet link, so it reaches exactly this one sheet."}
    (let [owned  (for [id (db/sheets-of-owner uid)]
                   {:sheet id :name (second (store/split-id id))
                    :level "read-write" :owned true})
          shared (for [{:keys [sheet-id name level]} (db/sheets-shared-with uid)]
                   {:sheet sheet-id
                    :name (or (not-empty (str name)) (second (store/split-id sheet-id)))
                    :level (clojure.core/name level) :owned false})
          all    (vec (concat owned shared))]
      (cond-> {:sheets all}
        (empty? all)
        (assoc :note "No sheets yet — create one in the SaltRim UI first.")))))

(defn describe-sheet [cred {:keys [sheet]} token]
  (let [{:keys [sheet-id level]} (resolve-sheet cred sheet)]
    {:sheet sheet-id
     :name (second (store/split-id sheet-id))
     :level (name level)
     :writes_go_to (if (= :read-write level) (agent-branch cred token) nil)
     :branches (vec (db/branch-names sheet-id))
     :note (if (= :read-write level)
             (str "Your writes auto-fork onto \"" (agent-branch cred token)
                  "\"; the sheet owner reviews and merges them. main is never edited directly.")
             "Read-only access to this sheet — write tools will be refused.")}))

(defn read-range [cred {:keys [range branch] :as args}]
  (let [{:keys [sheet-id]} (resolve-sheet cred (:sheet args))
        branch (or (not-empty (str branch)) db/MAIN)
        _ (when-not (store/valid-branch? branch)
            (throw (ex-info (str "bad branch name " (pr-str branch)) {})))
        _ (when-not (db/branch-exists? sheet-id branch)
            (throw (ex-info (str "no branch \"" branch "\" — existing: "
                                 (str/join ", " (db/branch-names sheet-id))) {})))
        [a b] (parse-range range)
        ;; size the rectangle from its CORNERS first: `range-cells` materializes
        ;; one address per cell, so expanding "A1:XFD1048576" (17 billion) and
        ;; then taking a prefix would exhaust the heap before the cap could apply.
        ;; Shrink to the first whole ROWS that fit instead — a truncation an agent
        ;; can reason about, which is what the note below promises it.
        n     (addr/range-size a b)
        shown (if (<= n MAX-READ-CELLS)
                (addr/range-cells a b)
                (let [{ca :ci ra :ri} (addr/parse a)
                      {cb :ci rb :ri} (addr/parse b)
                      c0 (min ca cb) r0 (min ra rb)
                      c1 (min (max ca cb) (+ c0 MAX-READ-CELLS -1))
                      w  (inc (- c1 c0))
                      r1 (min (max ra rb) (+ r0 (dec (quot MAX-READ-CELLS w))))]
                  (addr/range-cells (addr/make c0 r0) (addr/make c1 r1))))
        {:keys [sh]} (room-of sheet-id branch)]
    (cond-> {:branch branch
             :cells (vec (keep #(when (some? (sheet/raw sh %)) (cell-out sh %)) shown))}
      (> n MAX-READ-CELLS)
      (assoc :truncated true
             :note (str "range covers " n " cells; only the first " MAX-READ-CELLS
                        " were scanned — read a smaller rectangle")))))

(defn write-cells
  "Apply the writes on this credential's OWN branch (forked from main on first
   use), settle the engine, persist, and broadcast to every browser session in
   that room."
  [cred {:keys [cells] :as args} token]
  (let [{:keys [sheet-id level]} (resolve-sheet cred (:sheet args))
        uid (:uid cred)]
  (when-not (= :read-write level)
    (throw (ex-info (str "read-only access to \"" sheet-id
                         "\" — this credential grants view access only") {})))
  (when-not (seq cells)
    (throw (ex-info "no cells given" {})))
  (when (> (count cells) MAX-WRITE-CELLS)
    (throw (ex-info (str "too many cells in one call (max " MAX-WRITE-CELLS ")") {})))
  (doseq [{:keys [addr]} cells]
    (when-not (addr/valid? addr)
      (throw (ex-info (str "bad cell address " (pr-str addr)
                           " — use A1 style, inside the grid") {}))))
  ;; canonicalize at the boundary so an agent writing "a1" and "A1" edits ONE
  ;; cell, and the value we read back is the one we just wrote (see addr/canon)
  (let [cells  (mapv #(update % :addr addr/canon) cells)
        branch (ensure-agent-branch! sheet-id cred token)
        room   [sheet-id branch]
        {:keys [sh]} (room-of sheet-id branch)]
    ;; same room lock the browser handlers take — an agent writing while a human
    ;; edits the same branch would otherwise interleave two read-modify-writes of
    ;; one engine
    (locking (edit-lock room)
      (doseq [{:keys [addr src]} cells]
        (sheet/set-cell! sh addr (str src)))
      (sheet/settle! sh)
      (save-rec! room uid))
    ;; nil editor-sid = authored by no browser session, so EVERY session in the
    ;; room (i.e. anyone viewing this branch) gets the patch live
    (broadcast! nil room sh (map :addr cells))
    {:sheet sheet-id
     :branch branch
     :cells (mapv #(cell-out sh (:addr %)) cells)
     :note (str "written to branch \"" branch "\" — main is unchanged; "
                "the sheet owner merges it from the 🌿 branches panel")})))

(defn- call-tool [cred token {:keys [name arguments]}]
  (let [args (or arguments {})]
    (case name
      "saltrim_list_sheets"    (list-sheets cred)
      "saltrim_describe_sheet" (describe-sheet cred args token)
      "saltrim_read_range"     (read-range cred args)
      "saltrim_write_cells"    (write-cells cred args token)
      (throw (ex-info (str "unknown tool: " name) {})))))

(defn- ok-content
  "An MCP tool result: the payload as text (every client reads this) and as
   structuredContent (clients that understand it)."
  [v]
  {:content [{:type "text" :text (json/write-value-as-string v)}]
   :structuredContent v})

(defn- err-content
  "A tool FAILURE is a result with isError — not a protocol error — so the agent
   can read the message and correct itself."
  [msg]
  {:isError true :content [{:type "text" :text (str "Error: " msg)}]})

;; --- dispatch --------------------------------------------------------------

(defn handle-rpc [cred token {:keys [method params id]}]
  (case method
    "initialize"
    (rpc-result id {:protocolVersion PROTOCOL-VERSION
                    :capabilities {:tools {:listChanged false}}
                    :serverInfo {:name "saltrim" :version (version/current)}})

    ("tools/list") (rpc-result id {:tools tools})

    "tools/call"
    (rpc-result id (try (ok-content (call-tool cred token params))
                        (catch Throwable e
                          (u/log "WARN" "mcp tool" (:name params) "—" (.getMessage e))
                          (err-content (.getMessage e)))))

    "ping" (rpc-result id {})

    (rpc-error id ERR-METHOD (str "unknown method: " method))))

(defn handle-mcp
  "POST /mcp — one JSON-RPC message in, one JSON message out (stateless).
   A NOTIFICATION (no :id) is answered 202 with no body, per the spec."
  [req]
  (let [token (bearer req)
        body  (try (some-> (:body req) slurp not-empty (json/read-value mapper))
                   (catch Throwable _ ::bad))]
    (cond
      (nil? token)
      {:status 401
       :headers {"Content-Type" "application/json"
                 "WWW-Authenticate" "Bearer"}
       :body (json/write-value-as-string
              (rpc-error nil ERR-PARAMS
                         (str "missing Authorization: Bearer <token>. Use an "
                              "account agent key (srk_…, from the 🔑 panel in "
                              "SaltRim — reaches all your sheets) or a "
                              "single-sheet capability link token.")))}

      (or (= ::bad body) (nil? body))
      {:status 400 :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string (rpc-error nil ERR-PARSE "invalid JSON"))}

      :else
      (if-let [cred (credential token)]
        (if (nil? (:id body))
          {:status 202}                                  ; notification: no body
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string (handle-rpc cred token body))})
        {:status 403 :headers {"Content-Type" "application/json"}
         :body (json/write-value-as-string
                (rpc-error (:id body) ERR-PARAMS
                           "that token grants no access (revoked or rotated?)"))}))))
