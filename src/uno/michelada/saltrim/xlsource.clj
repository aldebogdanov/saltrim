(ns uno.michelada.saltrim.xlsource
  "Real Clojure source for a BORROWED stdlib function.

   The ƒ panel's copy button exists for one scenario: you import a workbook, or
   flatten a formula, end up with one large expression full of stdlib names, and
   want to run that calculation in an ordinary Clojure application where none of
   those names exist. For a hand-written function `stdlib/source-for` answers
   that completely — `product` comes with `nums` above it and compiles on its
   own.

   A borrowed function used to answer it with a note saying the work happens
   upstream, plus SaltRim's one-line delegation. That is true and useless: you
   cannot run `(excel/call \"ERFC\" args)` without the very dependency you were
   trying to leave behind, and you cannot READ what ERFC does. So this namespace
   goes and gets the implementation.

   rechentafel ships its sources (`.cljc`) inside its jar, so they are on the
   classpath. We read them with `tools.reader` in source-logging mode, which
   attaches the ORIGINAL TEXT of every form it reads as `:source` metadata —
   formatting, comments and alignment included. From there:

     - `(f/register! \"ERFC\" <impl> :arity [1 1])` gives the implementation as
       whatever expression it is: a `fn` literal, a `with-meta` around one, or a
       call to a local factory like `(n1 #(Math/sin (double %)))`;
     - every top-level definition of that same module the implementation
       mentions comes along, transitively, in file order;
     - the aliases the emitted text actually uses become a `require`.

   The implementation speaks rechentafel's tagged values (`{:t :num :v 30.0}`),
   so the paste also carries SaltRim's own two converters — read out of
   `excel.clj` by exactly the same machinery, so the copy button cannot hand
   over a bridge this build does not itself run — and a wrapper taking and
   returning plain Clojure values, with the ISO-date conversion for the
   date-shaped ones.

   What the paste deliberately does NOT reproduce is upstream's `f/call`:
   argument-count checking, error short-circuiting, and the element-wise
   broadcast of a scalar function over a range. Those belong to the registry
   rather than to the function, and a comment says so instead of a
   reimplementation pretending otherwise.

   Two functions (GAUSS, PHI) are registered inside a shared top-level `let`
   binding constants they both need. For those the whole block is emitted
   verbatim and the implementation taken back out of the registry it installs
   into — the algorithm is still right there to read, which is the point."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]))

;; --- reading a namespace's shipped source ----------------------------------

(def ^:private modules
  "rechentafel's function modules. Everything it registers strictly lives in one
   of these; the evaluator registers the lazy ones, which we do not expose."
  ["array" "database" "datetime" "engineering" "financial" "info" "logical"
   "lookup" "math" "misc" "stats" "text"])

(def ^:private excel-res "uno/michelada/saltrim/excel.clj")
(def ^:private stdlib-res "uno/michelada/saltrim/stdlib.clj")

(defn- read-source
  "Every top-level form of a classpath resource, as `[form text]`. Reader
   conditionals are resolved for Clojure; the text is the source verbatim."
  [res]
  (when-let [url (io/resource res)]
    (let [rdr  (rt/source-logging-push-back-reader (slurp url))
          opts {:eof ::eof :read-cond :allow :features #{:clj}}]
      (loop [out []]
        (let [[form text] (tr/read+string opts rdr)]
          (if (= form ::eof) out (recur (conj out [form text]))))))))

(def ^:private def-forms '#{def defn defn- defmacro definline})

(defn- read-one
  "Read one form of already-extracted source text. Same reader settings as the
   file walk — a definition may itself be inside a reader conditional."
  [text]
  (tr/read-string {:read-cond :allow :features #{:clj}} text))

(defn- ns-aliases-of
  "alias -> namespace, from a source file's own `ns` form."
  [forms]
  (let [nsf (some (fn [[f _]] (when (and (seq? f) (= 'ns (first f))) f)) forms)]
    (into {} (for [clause (rest nsf)
                   :when (and (seq? clause) (= :require (first clause)))
                   spec  (rest clause)
                   :when (and (vector? spec) (= :as (second spec)))]
               [(str (nth spec 2)) (str (first spec))]))))

(defn- load-source
  "A resource's forms, its top-level definitions by name, its `ns` aliases, and
   each definition's position (so a closure can be emitted in file order)."
  [res]
  (let [forms (read-source res)
        defs  (into {} (for [[f text] forms
                             :when (and (seq? f) (def-forms (first f))
                                        (symbol? (second f)))]
                         [(second f) text]))]
    {:forms   forms
     :defs    defs
     ;; the names the file itself forward-declares. Reproducing those beats
     ;; declaring everything: a blanket `(declare fact)` ahead of a
     ;; primitive-hinted `(defn fact ^double [^double n] …)` compiles and then
     ;; fails the recursive call with a cast to IFn$LD.
     :declared (set (for [[f _] forms
                          :when (and (seq? f) (= 'declare (first f)))
                          n (rest f)]
                      n))
     :aliases (ns-aliases-of forms)
     :order   (into {} (keep-indexed (fn [i [f _]]
                                       (when (and (seq? f) (def-forms (first f)))
                                         [(second f) i]))
                                     forms))}))

(def ^:private sources
  "Parsed source per resource, read once. Holding the parsed forms costs a few
   hundred KB of heap and saves re-reading 350KB of `.cljc` per lookup."
  (delay
    (into {} (for [res (concat (map #(str "rechentafel/fn/" % ".cljc") modules)
                               [excel-res stdlib-res])]
               [res (load-source res)]))))

(defn- src-of [res] (get @sources res))

;; --- locating one function --------------------------------------------------

(def ^:private registry
  "Excel name -> the module registering it and the index of its `register!`
   form. Direct top-level registrations only; see `nested` for the rest."
  (delay
    (into {} (for [m modules
                   :let [{:keys [forms]} (src-of (str "rechentafel/fn/" m ".cljc"))]
                   [i [f _]] (map-indexed vector forms)
                   :when (and (seq? f) (= 'f/register! (first f)) (string? (second f)))]
               [(str/upper-case (second f)) {:module m :index i}]))))

(def ^:private nested
  "Excel name -> the top-level form that registers it from INSIDE something
   else. Two of these (GAUSS, PHI), and worth having: that block is where the
   algorithm is written down."
  (delay
    (into {} (for [m modules
                   :let [{:keys [forms]} (src-of (str "rechentafel/fn/" m ".cljc"))]
                   [i [f _]] (map-indexed vector forms)
                   :when (and (seq? f) (not= 'f/register! (first f)))
                   sub (tree-seq coll? seq f)
                   :when (and (seq? sub) (= 'f/register! (first sub))
                              (string? (second sub)))]
               [(str/upper-case (second sub)) {:module m :index i}]))))

;; --- shaping the text -------------------------------------------------------

(defn- dedent
  "Re-indent a nested form's source for column 1. `:source` keeps the alignment
   the form had where it sat — thirteen spaces under a `register!` call — and at
   top level that reads as a mistake."
  [text col]
  (if (or (nil? col) (<= col 1))
    text
    (let [pad     (apply str (repeat (dec col) \space))
          [h & t] (str/split-lines text)]
      (str/join "\n" (cons h (map #(cond-> % (str/starts-with? % pad) (subs (count pad)))
                                  t))))))

(defn- form-end
  "Index just past the balanced form opening at `i`. A scanner rather than the
   reader, because the point is to cut TEXT, not to build a form; it has to know
   the four places a delimiter does not count — strings, regex literals,
   character literals and line comments."
  [^String s i]
  (let [n (.length s)]
    (loop [i i depth 0 state :code]
      (if (>= i n)
        i
        (let [c (.charAt s i)]
          (case state
            :code    (cond
                       (= c \")            (recur (inc i) depth :string)
                       (= c \;)            (recur (inc i) depth :comment)
                       (= c \\)            (recur (+ i 2) depth :code)
                       (#{\( \[ \{} c)     (recur (inc i) (inc depth) :code)
                       (#{\) \] \}} c)     (if (= 1 depth) (inc i) (recur (inc i) (dec depth) :code))
                       :else               (recur (inc i) depth :code))
            :string  (cond
                       (= c \\)            (recur (+ i 2) depth :string)
                       (= c \")            (recur (inc i) depth :code)
                       :else               (recur (inc i) depth :string))
            :comment (recur (inc i) depth (if (= c \newline) :code :comment))))))))

(defn- branch-text
  "The `:clj` (or `:default`) branch of a reader conditional, as source text.
   Read as an ordinary list so each branch keeps its own `:source`."
  [inner splice?]
  (let [rdr   (rt/source-logging-push-back-reader inner)
        pairs (partition 2 (tr/read {:read-cond :preserve} rdr))
        pick  (or (some (fn [[k v]] (when (= :clj k) v)) pairs)
                  (some (fn [[k v]] (when (= :default k) v)) pairs))
        text  (or (:source (meta pick)) (pr-str pick))]
    (if splice?
      (str/join " " (map #(or (:source (meta %)) (pr-str %)) pick))
      text)))

(defn- resolve-conditionals
  "Replace every `#?(…)` / `#?@(…)` in `text` with its Clojure branch.

   rechentafel's sources are `.cljc` and a handful of its helpers branch on the
   platform. Reader conditionals are only legal in a `.cljc` file, so a paste
   carrying one does not read at all in the ordinary `.clj` file this is going
   into — and the ClojureScript half is noise to someone who wanted the
   algorithm."
  [^String s]
  (let [n (.length s)]
    (loop [i 0 out (StringBuilder.) state :code]
      (if (>= i n)
        (str out)
        (let [c (.charAt s i)]
          (case state
            :code
            (cond
              (and (= c \#) (< (+ i 2) n) (= \? (.charAt s (inc i))))
              (let [splice? (= \@ (.charAt s (+ i 2)))
                    open    (str/index-of s "(" i)
                    end     (form-end s open)]
                ;; a branch may itself branch, so resolve what we just spliced in
                (recur end
                       (.append out (resolve-conditionals (branch-text (subs s open end) splice?)))
                       :code))

              (= c \")  (recur (inc i) (.append out c) :string)
              (= c \;)  (recur (inc i) (.append out c) :comment)
              (= c \\)  (recur (+ i 2) (.append out (subs s i (min n (+ i 2)))) :code)
              :else     (recur (inc i) (.append out c) :code))

            :string
            (cond
              (= c \\) (recur (+ i 2) (.append out (subs s i (min n (+ i 2)))) :string)
              (= c \") (recur (inc i) (.append out c) :code)
              :else    (recur (inc i) (.append out c) :string))

            :comment
            (recur (inc i) (.append out c) (if (= c \newline) :code :comment))))))))

(defn- indent [text n]
  (let [pad (apply str (repeat n \space))]
    (->> (str/split-lines text)
         (map #(cond-> % (seq %) (->> (str pad))))
         (str/join "\n"))))

(defn- closure
  "Every definition in this source that `form` reaches, transitively, in file
   order — which is dependency order already, since a Clojure file cannot call
   forward."
  [{:keys [defs order]} form]
  (loop [pending [form] seen #{}]
    (if-let [f (first pending)]
      (let [found (->> (tree-seq coll? seq f)
                       (filter symbol?)
                       (filter defs)
                       (remove seen)
                       distinct)]
        (recur (into (vec (rest pending)) (map #(read-one (defs %)) found))
               (into seen found)))
      (sort-by order seen))))

(defn- requires-for
  "The `require` line for the aliases `text` actually mentions."
  [text alias->ns]
  (let [used (for [[a n] (sort alias->ns)
                   :when (re-find (re-pattern (str "(?<![\\w.*+!\\-'?<>=/])"
                                                   (java.util.regex.Pattern/quote a) "/"))
                                  text)]
               (str "[" n " :as " a "]"))]
    (when (seq used)
      (str "(require '" (str/join "\n         '" used) ")"))))

;; --- the value bridge -------------------------------------------------------
;;
;; rechentafel speaks tagged maps and SaltRim speaks plain Clojure values, so a
;; pasted implementation is not callable without the two converters in between.
;; They are read out of `excel.clj` rather than restated here: the copy button
;; must not be able to hand over a bridge this build does not use.

(defn- own-source
  "The transitive closure of SaltRim's own definitions behind `entries`, as
   text. `excel/` qualification is stripped — in the paste those functions are
   right there, not in a namespace the reader has."
  [res entries]
  (let [{:keys [defs] :as src} (src-of res)
        names (closure src (map #(read-one (defs %)) entries))]
    [names (-> (str/join "\n\n" (map defs names))
               (str/replace "excel/" ""))]))

(defn- token-re
  "`name` as a whole Clojure symbol, not as part of a longer one."
  [name]
  (re-pattern (str "(?<![\\w.*+!'?<>=/-])" (java.util.regex.Pattern/quote (str name))
                   "(?![\\w.*+!'?<>=/-])")))

(defn- rename-all
  "Apply a symbol -> symbol rename across source text."
  [text renames]
  (reduce (fn [t [from to]] (str/replace t (token-re from) (str to))) text renames))

(defn- disambiguate
  "The names in `names` that `taken` already uses, mapped to a suffixed
   spelling. Everything lands in ONE namespace when pasted, and it does collide:
   `datetime.cljc` has its own `date->serial` / `serial->date`, working in
   LocalDates where SaltRim's work in ISO strings. Whichever definition came
   second used to win, and the paste then failed on a cast rather than on
   anything the reader could see."
  [names taken suffix]
  (let [taken (set taken)]
    (into {} (for [n names :when (taken n)] [n (symbol (str n suffix))]))))

(defn- align-aliases
  "Rewrite OUR namespace aliases to the ones the rechentafel module uses for the
   same namespace. Both halves speak `rechentafel.value`; `excel.clj` calls it
   `rv` and the function modules call it `val`, and requiring one namespace
   twice under two names reads as an accident rather than a boundary."
  [text ours theirs]
  (let [by-ns (into {} (for [[a n] theirs] [n a]))]
    (reduce (fn [t [a n]]
              (if-let [b (by-ns n)]
                (cond-> t (not= a b) (str/replace (re-pattern (str "(?<![\\w.])"
                                                                  (java.util.regex.Pattern/quote a)
                                                                  "/"))
                                                  (str b "/")))
                t))
            text ours)))

(defn- unique-name
  "`sym`-impl, pushed out of the way if a helper is already called that.
   rechentafel's own `norm-dist-impl` is exactly this collision: ours shadowed
   theirs and the wrapper then called itself with four arguments."
  [sym taken]
  (let [taken (set (map str taken))]
    (loop [n (str sym "-impl")]
      (if (taken n) (recur (str n "*")) n))))

;; --- emitting ---------------------------------------------------------------

(defn- wrapper
  "The `defn` a formula would call, in plain Clojure values. A date-shaped
   function converts ISO strings to serials on the way in and back on the way
   out, exactly as `stdlib/borrow` does."
  [sym impl-name {:keys [dates result]}]
  (if (or (seq dates) result)
    (str "(defn " sym " [& args]\n"
         "  (let [args (reduce (fn [a i] (cond-> a (< i (count a)) (update i to-serial)))\n"
         "                     (vec args) " (pr-str (vec (sort dates))) ")\n"
         "        out  (<-rv (" impl-name " (mapv ->rv args)))]\n"
         (if (= :date result) "    (serial->date out)))" "    out))"))
    (str "(defn " sym " [& args] (<-rv (" impl-name " (mapv ->rv args))))")))

(defn- header [sym xl nested?]
  (str ";; `" sym "` is Excel's " xl ", as rechentafel implements it — Apache-2.0,\n"
       ";; deps.edn  org.replikativ/rechentafel {:mvn/version \"0.1.5\"}\n"
       ";;\n"
       (when nested?
         (str ";; " xl " is registered inside the shared block below, so the\n"
              ";; implementation is taken back out of the registry it installs into.\n;;\n"))
       ";; rechentafel speaks tagged values ({:t :num :v 30.0}); the two converters\n"
       ";; below are SaltRim's own, so this takes and returns plain Clojure values.\n"
       ";; Upstream's `f/call` adds argument-count checking, error short-circuiting\n"
       ";; and — for a scalar function — an element-wise broadcast over a range;\n"
       ";; those belong to the registry rather than to the function itself."))

(defn source-for*
  "Standalone Clojure source for the borrowed function `sym`, which is Excel's
   `xl`. `shape` is `stdlib`'s date-shape entry for it, or nil. nil when the
   implementation cannot be located, so the caller can fall back."
  [sym xl shape]
  (when-let [{:keys [module index]} (or (@registry (str/upper-case xl))
                                        (@nested (str/upper-case xl)))]
    (let [res     (str "rechentafel/fn/" module ".cljc")
          {:keys [forms defs aliases] :as src} (src-of res)
          [form text] (nth forms index)
          nested? (not= 'f/register! (first form))
          impl    (when-not nested? (nth form 2 nil))
          helpers (closure src (if nested? form impl))
          dated?  (boolean (or (seq (:dates shape)) (:result shape)))

          ;; SaltRim's half: the value bridge always, the date bridge only for a
          ;; function whose signature speaks dates
          [bridge-names bridge-text] (own-source excel-res '[->rv <-rv])
          [date-names date-text]     (when dated?
                                       (own-source excel-res '[date->serial serial->date]))
          [ser-names ser-text]       (when dated?
                                       (own-source stdlib-res '[to-serial]))
          ours     (concat bridge-names date-names ser-names)
          ;; the name being defined wins over a helper that happens to share it:
          ;; `FACT` is implemented in terms of a private `fact`, and defining the
          ;; wrapper over the top of it left the impl calling a redefined var
          ;; through a primitive signature it no longer had
          theirs*  (disambiguate helpers [sym] "-rt")
          helpers' (map #(get theirs* % %) helpers)
          renames  (disambiguate ours helpers' "-sr")
          impl-nm  (unique-name sym (concat helpers' (vals renames) ours))

          body    (if nested?
                    (str (rename-all text theirs*)
                         "\n\n(def " impl-nm " (:fn (f/lookup \"" xl "\")))")
                    (str "(def " impl-nm "\n"
                         (indent (rename-all (dedent (or (:source (meta impl)) (pr-str impl))
                                                     (:column (meta impl)))
                                             theirs*)
                                 2)
                         ")"))
          ;; only the forward references the source files themselves make
          all     (merge renames theirs*)
          fwd     (filter (set (concat ours helpers))
                          (concat (:declared (src-of excel-res))
                                  (:declared (src-of stdlib-res))
                                  (:declared src)))
          ourfix  #(-> % (rename-all renames)
                         (align-aliases (:aliases (src-of excel-res)) aliases))
          pieces  (concat (when (seq fwd)
                            [(str "(declare " (str/join " " (map #(get all % %) fwd)) ")")])
                          [(ourfix bridge-text)]
                          (when dated? [(ourfix date-text) (ourfix ser-text)])
                          (map #(rename-all (defs %) theirs*) helpers)
                          [body (ourfix (wrapper sym impl-nm shape))])
          body*   (resolve-conditionals (str/join "\n\n" pieces))]
      (str/join "\n\n" (remove nil? [(header sym xl nested?)
                                     (requires-for body* (merge (:aliases (src-of excel-res))
                                                                aliases))
                                     body*])))))

(def source-for
  "`source-for*`, memoised. The ƒ panel asks for every borrowed function on
   every render of the definitions modal."
  (memoize source-for*))
