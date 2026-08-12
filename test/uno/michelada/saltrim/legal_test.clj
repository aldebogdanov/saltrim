(ns uno.michelada.saltrim.legal-test
  "The privacy notice makes claims ABOUT THE CODE: which fields are stored, what
   deletion removes, how long a token lives. Prose drifts and code moves, and a
   notice that has quietly stopped being true is worse than no notice at all —
   people rely on it, and it is the one document where being wrong is not merely
   untidy.

   So the facts in it are derived here from the things they describe: the schema,
   `delete-user!`'s own list of identifying attributes, and `TOKEN-IDLE-MS`. Add
   a personal-data field and this suite fails until the page mentions it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.web :as web]
            [uno.michelada.saltrim.web.render :as render]))

(def ^:private privacy (delay (render/privacy-page)))
(def ^:private terms   (delay (render/terms-page)))

(defn- says? [page & fragments]
  (every? #(str/includes? (str/lower-case page) (str/lower-case %)) fragments))

;; --- the pages have to be reachable by someone who has not signed in --------

(deftest the-legal-pages-are-public
  ;; a privacy notice behind a login is not a notice, and Google's OAuth consent
  ;; screen needs a URL that resolves for a signed-out visitor
  (doseq [uri ["/privacy" "/terms"]]
    (let [resp (#'web/app {:request-method :get :uri uri})]
      (is (= 200 (:status resp)) (str uri " with no cookie, no session"))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/html"))
      (is (str/starts-with? (:body resp) "<!doctype html>")))))

(deftest the-pages-carry-no-scripts
  ;; they must render when everything else is broken, and they are the one place
  ;; a third-party script would be least excusable
  (doseq [page [@privacy @terms]]
    (is (not (str/includes? page "<script")))
    (is (not (str/includes? page "/app.js")))
    (is (not (str/includes? page "datastar")))))

(deftest each-page-links-to-the-other
  (is (str/includes? @privacy "/terms"))
  (is (str/includes? @terms "/privacy")))

;; --- the claims, checked against the code -----------------------------------

(deftest every-identifying-field-erasure-removes-is-disclosed
  ;; `delete-user!` decides what counts as identifying; the notice has to list
  ;; the same set. A new one (say :user/phone) fails here until it is written up.
  (doseq [attr @#'db/identifying-attrs]
    (is (says? @privacy (name attr))
        (str "the privacy page must mention " attr " — delete-user! purges it"))))

(deftest what-the-schema-stores-about-a-person-is-disclosed
  (testing "the account fields"
    (is (says? @privacy "display name" "email address" "avatar")))
  (testing "the credentials, and that only hashes are kept"
    (is (says? @privacy "sha-256" "agent key")))
  (testing "the authorship stamp, which is easy to forget is personal data"
    (is (says? @privacy "which account last wrote each cell"))))

(deftest the-retention-numbers-come-from-the-code
  (let [days (quot db/TOKEN-IDLE-MS (* 24 60 60 1000))]
    (is (= 90 days) "if this changes, the sentence below has to change with it")
    (is (says? @privacy (str days " days")))))

(deftest the-uid-caveat-is-stated-plainly
  ;; the one part of erasure that is NOT complete. Claiming otherwise would be
  ;; the single most misleading thing this page could do.
  (is (says? @privacy "opaque account id"))
  (is (says? @privacy "github-12345") "with a concrete example of one")
  ;; no apostrophes in the needle — hiccup escapes them to &apos;
  (is (says? @privacy "author of cells you wrote in other people")))

(deftest where-the-data-lives-is-named
  (is (says? @privacy "vpsfree" "czech"))
  (is (says? @privacy "yugabytedb cloud" "eu-central-1" "frankfurt"))
  (is (says? @privacy "eea")))

(deftest the-controller-and-a-working-contact-are-named
  (doseq [page [@privacy @terms]]
    (is (says? page "saltrim")))
  (is (says? @privacy "aleksandr bogdanov"))
  (is (says? @privacy "privacy@michelada.uno")))

(deftest the-article-27-position-is-recorded
  ;; we rely on the Art. 27(2) exemption rather than appointing a representative;
  ;; the reasoning is part of the notice so it is not merely an unstated hope
  (is (says? @privacy "article 3(2)"))
  (is (says? @privacy "article 27"))
  (is (says? @privacy "no special-category data" "not large scale")))

(deftest the-backup-window-is-honest
  ;; purge cannot reach the provider's backups, so the page must not imply that
  ;; deletion is instant everywhere
  (is (says? @privacy "backups" "30 days")))

(deftest deletion-is-described-where-it-actually-lives
  (is (says? @privacy "🔑" "delete my account"))
  (is (says? @privacy "cannot be undone")))

(deftest the-cookie-position-is-stated
  (is (says? @privacy "no analytics"))
  (is (says? @privacy "cookie banner") "and why there isn't one"))

(deftest the-terms-disclaim-the-service-not-the-code
  (is (says? @terms "as is"))
  (is (says? @terms "mit licence") "the software's licence is separate")
  (is (says? @terms ".xlsx") "and it points at the export as the user's own backup")
  (testing "consumer rights are not waived away"
    (is (says? @terms "consumer"))))
