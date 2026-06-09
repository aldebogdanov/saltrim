(ns uno.michelada.clorax.store
  "Sheet persistence — saves the SOURCE document (not the Spindel graph), one
   EDN file per sheet id under data/. Behind save!/load/exists?, so the backend
   can become Datahike/SQL later without touching callers.

   Multi-tenancy: ids namespace sheets (e.g. \"tenant__sheet\"); add auth later."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [uno.michelada.clorax.sheet :as sheet]))

(def ^:private dir "data")
(def ^:private fmt 1)

(defn valid-id?
  "Allow only safe ids (no path traversal)."
  [id]
  (boolean (and id (re-matches #"[A-Za-z0-9_-]{1,64}" (str id)))))

(defn- file [id]
  (assert (valid-id? id) (str "bad sheet id: " (pr-str id)))
  (io/file dir (str id ".edn")))

(defn exists? [id]
  (and (valid-id? id) (.exists (file id))))

(defn save!
  "Persist a sheet's source document under `id`."
  [id sheet]
  (let [f (file id)]
    (io/make-parents f)
    (spit f (pr-str {:fmt fmt :cells (sheet/document sheet)})))
  id)

(defn load-sheet
  "Load and rebuild a sheet for `id`, or nil if none stored."
  [id]
  (when (exists? id)
    (let [{:keys [cells]} (edn/read-string (slurp (file id)))
          s (sheet/create-sheet)]
      (sheet/load-document! s cells)
      (sheet/settle! s)
      s)))
