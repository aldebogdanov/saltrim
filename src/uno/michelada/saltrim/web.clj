(ns uno.michelada.saltrim.web
  "Web entry point: the http-kit router (`app`) dispatching to the handler
   namespaces, plus the mount states (`server`, `sweeper`) and `-main`. The
   request/render/collab/state logic lives in `uno.michelada.saltrim.web.*`."
  (:require
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [jsonista.core :as json]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.util :as util :refer [timed]]
            [uno.michelada.saltrim.xlsx :as xlsx]
            [mount.core :refer [defstate]]
            [uno.michelada.saltrim.mcp :refer [handle-mcp]]
            [uno.michelada.saltrim.web.state :refer [SWEEP-MS sessions* sheets*]]
            [uno.michelada.saltrim.web.collab :refer [sweep!]]
            [uno.michelada.saltrim.web.handlers :refer [auth-routes handle-branch handle-cell handle-celllayer handle-clear handle-copy handle-cut handle-defadd handle-defdel handle-deflock handle-defsave handle-defunlock handle-delete-sheet handle-deleteline handle-export handle-flatten handle-graph handle-agentkey handle-import handle-insert handle-merge handle-mergecells handle-paste handle-presence handle-props handle-redo handle-root handle-session-end handle-share handle-size handle-stream handle-style handle-undo handle-unmergecells handle-view handle-viewat]])
  (:gen-class))

(defn- app [req]
  (or
   (auth-routes req)
   (case [(:request-method req) (:uri req)]
    [:get "/"]            (handle-root req)
    [:get "/datastar.js"] (if-let [r (io/resource "public/datastar.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no datastar"})
    [:get "/app.js"]      (if-let [r (io/resource "public/app.js")]
                            {:status 200 :headers {"Content-Type" "text/javascript"}
                             :body (slurp r)}
                            {:status 404 :body "no app.js"})
    ;; brand wordmark (login page)
    [:get "/SaltRim.png"] (if-let [r (io/resource "SaltRim.png")]
                            {:status 200 :headers {"Content-Type" "image/png"
                                                   "Cache-Control" "max-age=86400"}
                             :body (io/input-stream r)}
                            {:status 404 :body "no logo"})
    ;; 1200x630 social-preview card, referenced by og:image
    [:get "/SaltRim-opengraph.png"] (if-let [r (io/resource "SaltRim-opengraph.png")]
                                       {:status 200 :headers {"Content-Type" "image/png"
                                                              "Cache-Control" "max-age=86400"}
                                        :body (io/input-stream r)}
                                       {:status 404 :body "no opengraph image"})
    ;; explicit <link rel="icon" type="image/png">
    [:get "/favicon.png"] (if-let [r (io/resource "favicon.png")]
                            {:status 200 :headers {"Content-Type" "image/png"
                                                   "Cache-Control" "max-age=86400"}
                             :body (io/input-stream r)}    ; binary — not slurp
                            {:status 404 :body "no favicon"})
    ;; multi-res .ico: the browser's automatic /favicon.ico request (covers
    ;; pages without the <link>, e.g. login) plus the explicit <link> below
    [:get "/favicon.ico"] (if-let [r (io/resource "favicon.ico")]
                            {:status 200 :headers {"Content-Type" "image/x-icon"
                                                   "Cache-Control" "max-age=86400"}
                             :body (io/input-stream r)}
                            {:status 404 :body "no favicon"})
    ;; iOS/iPadOS home-screen + bookmark icon
    [:get "/apple-touch-icon.png"] (if-let [r (io/resource "apple-touch-icon.png")]
                                     {:status 200 :headers {"Content-Type" "image/png"
                                                            "Cache-Control" "max-age=86400"}
                                      :body (io/input-stream r)}
                                     {:status 404 :body "no icon"})
    [:get "/stream"]         (handle-stream req)
    [:get "/export.xlsx"]    (handle-export req)
    [:post "/import"]        (handle-import req)
    [:post "/session/end"]   (handle-session-end req)
    ;; MCP (agents): JSON-RPC in, JSON out, authorized by a sheet link token.
    ;; Stateless — no session, no SSE; see the mcp ns.
    [:post "/mcp"]           (handle-mcp req)
    ;; dev-only diagnostics: exposed only under the name-only dev provider
    [:get "/debug"]       (if-not (auth/dev-auth?)
                            {:status 404 :body "not found"}
                            {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/write-value-as-string
                                    {:sessions (count @sessions*)
                                     :loaded-sheets (vec (keys @sheets*))
                                     :detail (mapv (fn [[sid s]]
                                                     {:sid (subs sid 0 (min 6 (count sid)))
                                                      :sheet (:sheet s)
                                                      :uid (:uid s)
                                                      :gen? (boolean (:gen s))
                                                      :view (:view s)})
                                                   @sessions*)})})
    [:post "/cell"]       (handle-cell req)
    [:post "/flatten"]    (handle-flatten req)
    [:post "/style"]      (handle-style req)
    [:post "/undo"]       (handle-undo req)
    [:post "/redo"]       (handle-redo req)
    [:post "/clear"]      (handle-clear req)
    [:post "/copy"]       (handle-copy req)
    [:post "/cut"]        (handle-cut req)
    [:post "/paste"]      (handle-paste req)
    [:post "/size"]       (handle-size req)
    [:post "/insert"]     (handle-insert req)
    [:post "/deleteline"] (handle-deleteline req)
    [:post "/agentkey"]     (handle-agentkey req)
    [:post "/celllayer"]    (handle-celllayer req)
    [:post "/mergecells"]   (handle-mergecells req)
    [:post "/unmergecells"] (handle-unmergecells req)
    [:post "/props"]      (handle-props req)
    [:post "/delete-sheet"] (handle-delete-sheet req)
    [:post "/deflock"]    (handle-deflock req)
    [:post "/defunlock"]  (handle-defunlock req)
    [:post "/defsave"]    (handle-defsave req)
    [:post "/defadd"]     (handle-defadd req)
    [:post "/defdel"]     (handle-defdel req)
    [:post "/view"]       (handle-view req)
    [:post "/viewat"]     (handle-viewat req)
    [:post "/presence"]   (handle-presence req)
    [:post "/share"]      (handle-share req)
    [:post "/branch"]     (handle-branch req)
    [:post "/merge"]      (handle-merge req)
    [:post "/graph"]      (handle-graph req)
    {:status 404 :body "not found"})))

;; --- security headers -----------------------------------------------------

(def security-headers
  "Sent on every response. Two of these earn their keep specifically here:

   `Content-Security-Policy` is the second layer under the style-value check in
   `web.render` — a cell's computed style is spliced into a `style` attribute, so
   `img-src`/`connect-src` of `'self'` mean that even a value which slipped past
   that check cannot fetch from, or talk to, a third-party host. `frame-ancestors
   'none'` stops the app being framed and clicked through.

   `Referrer-Policy: no-referrer` matters because a share URL *is* the
   credential (`/?t=<token>`): the token must never ride a Referer header off
   this origin. Browsers default to `strict-origin-when-cross-origin`, which is
   already safe, but the URL is too sensitive to leave to a default.

   `'unsafe-eval'` is not optional: Datastar compiles every `data-*` expression
   with `new Function`. `'unsafe-inline'` in style-src likewise — the page ships
   one inline <style> and every cell carries an inline `style` attribute.

   `script-src` is `'self'` alone: every script the page loads is one we serve
   (`/datastar.js`, `/app.js`). It used to also allow `https://cdn.jsdelivr.net`,
   which is where the Datastar bundle came from — so the policy had to trust a
   third-party origin to run code with full access to the sheet."
  {"Content-Security-Policy"
   (str/join "; " ["default-src 'self'"
                   "script-src 'self' 'unsafe-eval'"
                   "style-src 'self' 'unsafe-inline'"
                   "img-src 'self' data:"
                   "font-src 'self'"
                   "connect-src 'self'"
                   "form-action 'self'"
                   "base-uri 'none'"
                   "object-src 'none'"
                   "frame-ancestors 'none'"])
   "Referrer-Policy"        "no-referrer"
   "X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"})       ; for engines predating frame-ancestors

(defn- wrap-security-headers
  "Add `security-headers` to every response, without overriding a header the
   handler set itself (SSE responses carry their own Content-Type/Cache-Control)."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (cond-> resp
        (map? resp) (update :headers #(merge security-headers %))))))

(def MAX-BODY
  "Largest request body http-kit will accept.

   It has to sit ABOVE `xlsx/max-bytes`, the .xlsx upload cap the import modal
   actually tells the user about. http-kit's own default is 8 MiB — exactly
   `max-bytes` — so an oversized workbook was rejected by the server with a bare
   413 BEFORE the handler ran, and the friendly \"file too large (max 8 MB)\"
   message was unreachable. The headroom also covers multipart framing, which is
   part of the body but not part of the file."
  (+ xlsx/max-bytes (* 4 1024 1024)))

(defn port
  "HTTP port — SALTRIM_PORT env or 8080."
  []
  (or (some-> (System/getenv "SALTRIM_PORT") parse-long) 8080))

(defn- start-sweeper-pool!
  "A scheduled pool that reaps idle/orphan sessions on an interval."
  []
  (doto (java.util.concurrent.Executors/newScheduledThreadPool 1)
    (.scheduleAtFixedRate ^Runnable (fn [] (try (sweep!) (catch Throwable _)))
                          SWEEP-MS SWEEP-MS java.util.concurrent.TimeUnit/MILLISECONDS)))

;; --- mount states ---------------------------------------------------------
;; Each state's VALUE is the live resource (no side atoms): `sweeper` is the
;; scheduled pool, `server` is http-kit's stop-fn. db's `conn` state starts
;; first (web requires db), so the order is conn → sweeper → server, reversed on
;; stop. (sessions*/sheets* stay atoms — they're in-memory caches, not lifecycle.)

(defstate sweeper
  :start (timed "session sweeper" (start-sweeper-pool!))
  :stop  (timed "session sweeper" (.shutdownNow ^java.util.concurrent.ExecutorService sweeper)))

(defstate server
  :start (timed (str "http server :" (port))
           (let [stop (http/run-server (-> #'app
                                           wrap-params
                                           wrap-keyword-params
                                           wrap-cookies
                                           wrap-security-headers)
                                       {:port (port) :max-body MAX-BODY})]
                  (util/log "  serving http://localhost:" (port) "·"
                            (if-let [ps (seq (keys (auth/providers)))]
                              (str "auth: " (str/join ", " (map name ps))) "auth: none")
                            (if (auth/dev-auth?) "(+ dev login)" ""))
                  stop))
  ;; http-kit's run-server returns a stop-fn; the live sessions' streams die with
  ;; it, so drop the cache too.
  :stop  (timed "http server" (do (server) (reset! sessions* {}))))

;; Lifecycle is owned by the mount `system` ns; -main delegates there (resolved
;; at runtime to avoid a compile-time cycle, since system requires web).
(defn -main [& _]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable (requiring-resolve 'uno.michelada.saltrim.system/stop!)))
  ((requiring-resolve 'uno.michelada.saltrim.system/start!))
  @(promise))

