(ns uno.michelada.saltrim.errors
  "What a broken cell IS.

   Every failure inside a formula arrives as a Throwable — Excel's own errors
   (`excel/call` names them in `ex-data`), a `deleted-ref` after a column was
   removed, or a plain Java exception from Clojure code doing something to the
   wrong type. Historically all three collapsed to `{:error <whatever message>}`
   and rendered as a blanket `#ERR`, which told the reader nothing: a cast
   failure showed the same three characters as a divide by zero, and its actual
   message was a sentence about `java.lang.String` and the bootstrap loader.

   So a cell error is now a CLASSIFIED value: `{:error <message> :code <kw>}`.
   The code is a small closed set — Excel's, because those names are what a
   spreadsheet user already reads — and it drives what the cell displays, while
   the message stays as the detail behind it (the tooltip, the toast). Formulas
   can branch on the code too: `error-type`, `if-error` and `if-na` in the
   stdlib all read it.

   Classification never fails: an exception we don't recognise is `:error`,
   which displays as the old `#ERR`."
  (:require [clojure.string :as str]))

(def display-names
  "Code -> what the cell shows. Excel's spellings, because a spreadsheet user
   already knows them on sight; `:timeout` and `:error` are ours (Excel has no
   equivalent of a runaway formula, and `#ERR` is the honest answer for an
   exception we couldn't place)."
  {:div0    "#DIV/0!"
   :value   "#VALUE!"
   :ref     "#REF!"
   :name    "#NAME?"
   :num     "#NUM!"
   :na      "#N/A"
   :null    "#NULL!"
   :spill   "#SPILL!"
   :calc    "#CALC!"
   :timeout "#TIMEOUT!"
   :error   "#ERR"})

(def codes
  "The codes a formula can test for, in the order the help panel lists them."
  (vec (keys display-names)))

(defn- from-message
  "Last resort: recognise the failures that arrive as ordinary Java exceptions
   with no data attached. Matching on message text is fragile in general, but
   these are stable JDK/SCI strings and the fallback is merely a less specific
   code, never a wrong answer."
  [^Throwable e]
  (let [m (or (.getMessage e) "")]
    (cond
      (str/includes? m "Divide by zero")          :div0
      (str/includes? m "Unable to resolve symbol") :name
      (str/includes? m "cannot be cast")          :value
      :else                                       nil)))

(defn classify
  "Throwable -> a code from `display-names`. Checks, in order: a code the
   thrower stated outright; an Excel error named by `excel/call`; a
   `deleted-ref`; the exception class; then the message. Anything unrecognised
   is `:error`."
  [^Throwable e]
  (let [d (ex-data e)]
    (or (when (contains? display-names (:code d)) (:code d))
        (:excel-error d)
        (when (:ref d) :ref)
        (from-message e)
        (condp instance? e
          ArithmeticException       :div0
          ClassCastException        :value
          NumberFormatException     :value
          NullPointerException      :value
          IllegalArgumentException  :value
          IndexOutOfBoundsException :ref
          nil)
        :error)))

(defn of
  "Throwable -> the `{:error … :code …}` map a cell reports. The message is kept
   verbatim: for an Excel error it already IS the display name, and for a Java
   exception it is the only detail the reader gets."
  [^Throwable e]
  {:error (or (.getMessage e) (str (class e)))
   :code  (classify e)})

(defn err
  "The same map built from a code and message directly — for failures that were
   never a Throwable (a wedged sheet, a compile error replayed from `meta`)."
  ([code] (err code (display-names code)))
  ([code message] {:error message :code (or code :error)}))

(defn label
  "What the CELL shows for an error value: the short spreadsheet name. Falls
   back to `#ERR` for a value with no code — including one loaded from an older
   session, before codes existed."
  [v]
  (display-names (:code v) "#ERR"))

(defn detail
  "The line behind the label — what a tooltip or toast should say. Empty when
   the message is just the label repeated (every Excel error), so a caller can
   skip an unhelpful \"#N/A: #N/A\"."
  [v]
  (let [m (:error v)]
    (when (and (string? m) (not= m (label v)) (not (str/blank? m)))
      m)))
