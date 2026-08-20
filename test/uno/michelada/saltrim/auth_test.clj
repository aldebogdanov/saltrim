(ns uno.michelada.saltrim.auth-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mount.core :as mount]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]))

;; Users + tokens live in Datahike; each test runs against a fresh in-memory db.
(use-fixtures :each (fn [t] (db/start-mem!) (try (t) (finally (mount/stop)))))

(deftest dev-login-and-cookie-roundtrip
  ;; no provider env vars in the test JVM -> dev auth active by default
  (is (auth/dev-auth?))
  (let [{:keys [token uid error]} (auth/dev-login! "Test Üser 42")]
    (is (nil? error))
    (testing "uid is sanitized: prefix, [a-z0-9-] only, no underscores"
      (is (= "dev-test-ser-42" uid))
      (is (re-matches auth/uid-re uid)))
    (testing "profile stored"
      (is (= "Test Üser 42" (:name (auth/user-info uid)))))
    (testing "token resolves through the auth cookie"
      (let [req {:headers {"cookie" (str "other=1; "
                                         (second (re-find #"^(saltrim_auth=[a-f0-9]+)"
                                                          (auth/auth-cookie token))))}}]
        (is (= uid (auth/req->uid req)))))
    (testing "revoked token no longer authenticates"
      (auth/revoke-token! token)
      (is (nil? (auth/req->uid {:headers {"cookie" (str "saltrim_auth=" token)}}))))))

(deftest dev-login-rejects-blank
  (is (:error (auth/dev-login! "")))
  (is (:error (auth/dev-login! "!!!"))))

(deftest cookie-attributes
  (let [c (auth/auth-cookie (apply str (repeat 64 "a")))]
    (is (re-find #"HttpOnly" c))
    (is (re-find #"SameSite=Lax" c))
    (is (re-find #"Path=/" c)))
  (is (re-find #"Max-Age=0" (auth/clear-cookie))))

(deftest req->uid-ignores-garbage
  (is (nil? (auth/req->uid {:headers {}})))
  (is (nil? (auth/req->uid {:headers {"cookie" "saltrim_auth=nothex"}})))
  (is (nil? (auth/req->uid {:headers {"cookie" (str "saltrim_auth=" (apply str (repeat 64 "f")))}}))))

(deftest resolve-grantee-dev-uses-name
  ;; dev mode (no provider env) — a name resolves to the deterministic dev uid,
  ;; whether or not that person has logged in yet.
  (is (auth/dev-auth?))
  (is (= "dev-bob"          (auth/resolve-grantee "Bob")))
  (is (= "dev-test-ser-42"  (auth/resolve-grantee "Test Üser 42"))
      "same sanitization as dev-login")
  (is (nil? (auth/resolve-grantee "   ")))
  (is (nil? (auth/resolve-grantee "!!!"))))

(deftest unknown-provider-rejected
  (is (:error (auth/callback! :nope "code" "state" "state")))
  (is (nil? (auth/login-start! :github))))   ; not configured in test env

;; --- OAuth state is bound to the BROWSER, not just to the server -------------

(def ^:private fake-provider
  {:github {:label "GitHub" :client-id "id" :client-secret "sec"
            :authorize "https://example.test/authorize"
            :token "https://example.test/token"
            :userinfo "https://example.test/user"
            :scope "read:user" :prefix "gh"
            :extract (fn [u] {:ext-id (str (get u "id")) :name "Ann"})}})

(defmacro ^:private with-provider
  "Run `body` with a configured GitHub provider whose network calls are stubbed,
   so the state check is the only thing that can refuse."
  [& body]
  `(with-redefs [auth/providers (constantly fake-provider)
                 auth/exchange-code! (constantly "access-token")
                 auth/fetch-userinfo! (constantly {"id" "7"})]
     ~@body))

(deftest login-start-mints-a-nonce-for-the-url-and-the-cookie
  (with-provider
    (let [{:keys [url state]} (auth/login-start! :github)]
      (is (re-find #"[0-9a-f]{32}" state) "a SecureRandom hex nonce")
      (is (clojure.string/includes? url (str "&state=" state))
          "the same nonce the browser is about to be given")
      (testing "and the cookie carries it, locked to this origin"
        (let [c (auth/state-cookie state)]
          (is (clojure.string/includes? c (str "saltrim_oauth_state=" state)))
          (is (clojure.string/includes? c "HttpOnly"))
          (is (clojure.string/includes? c "SameSite=Lax")
              "Strict would be stripped on the provider's top-level redirect back"))))))

(deftest a-login-completes-only-in-the-browser-that-started-it
  ;; THE ATTACK: an attacker starts their own login, holds the valid state and
  ;; code, and walks a victim through the callback. Server-side-only nonce
  ;; validation passes, and the victim is silently signed in as the ATTACKER —
  ;; everything they then type lands in the attacker's account.
  (with-provider
    (testing "the browser that was given the nonce gets in"
      (let [{:keys [state]} (auth/login-start! :github)
            r (auth/callback! :github "code" state state)]
        (is (nil? (:error r)))
        (is (:token r))))
    (testing "a browser that was NOT given it does not"
      (let [{:keys [state]} (auth/login-start! :github)]
        (is (= "bad state (retry login)" (:error (auth/callback! :github "code" state nil)))
            "no state cookie at all — the victim's browser")
        (is (= "bad state (retry login)"
               (:error (auth/callback! :github "code" state "someone-elses-nonce"))))))
    (testing "and a spent nonce cannot be replayed, even by its own browser"
      (let [{:keys [state]} (auth/login-start! :github)]
        (is (nil? (:error (auth/callback! :github "code" state state))))
        (is (= "bad state (retry login)" (:error (auth/callback! :github "code" state state)))
            "consumed on first use")))
    (testing "a blank state is never valid, cookie or no cookie"
      (is (= "bad state (retry login)" (:error (auth/callback! :github "code" "" ""))))
      (is (= "bad state (retry login)" (:error (auth/callback! :github "code" nil nil)))))))
