(ns uno.michelada.saltrim.addr
  "Spreadsheet addressing. Address = COL + ROW, e.g. \"A1\", \"AAB1234\".
   COL = bijective base-26 letters (A=1, Z=26, AA=27, ...). ROW = 1-based int.
   No colon in an address — colon is reserved for ranges (\"A1:C3\").

   Two id spaces:
   - address string \"AAB1234\"  (canonical: signal/spin id, user-facing)
   - 0-based [col-idx row-idx]   (grid geometry / viewport math)"
  (:require [uno.michelada.saltrim.constants :refer [MAX-COLS MAX-ROWS]]))

;; (int \A) and (int char) compile to a bit-or in ClojureScript (chars are just
;; strings there), so spell the code points out and read them portably.
(def ^:private A-code 65)

(defn- ch-code
  "Code point of a single character (a Character in CLJ, a 1-char string in CLJS)."
  [ch]
  #?(:clj (int ^char ch) :cljs (.charCodeAt (str ch) 0)))

(defn col->idx
  "Column letters -> 0-based index. \"A\"->0, \"Z\"->25, \"AA\"->26."
  [col]
  (let [col #?(:clj (.toUpperCase ^String col) :cljs (.toUpperCase col))]
    (dec (reduce (fn [acc ch] (+ (* acc 26) (inc (- (ch-code ch) A-code))))
                 0 col))))

(defn idx->col
  "0-based index -> column letters. 0->\"A\", 25->\"Z\", 26->\"AA\"."
  [idx]
  (loop [n (inc idx) out ""]
    (if (pos? n)
      (let [r (mod (dec n) 26)]
        (recur (quot (dec n) 26) (str (char (+ A-code r)) out)))
      out)))

(def ^:private addr-re #"([A-Za-z]+)([0-9]+)")

;; Longest COL/ROW strings we will even convert to a number. The grid caps
;; (MAX-COLS = XFD, MAX-ROWS = 1048576) are 3 letters and 7 digits, so anything
;; longer is out of bounds by definition — and refusing it on the STRING keeps
;; `parse` away from inputs that overflow the integer conversion (a 20-digit row
;; threw NumberFormatException, which is not a shape any caller expects).
(def ^:private MAX-COL-CHARS 3)
(def ^:private MAX-ROW-CHARS 7)

(defn parse
  "\"AAB1234\" -> {:col \"AAB\" :row 1234 :ci <0-based> :ri <0-based>}.
   Only defined for addresses `valid?` accepts — callers gate on that."
  [addr]
  (let [[_ col row] (re-matches addr-re addr)
        row-n #?(:clj  (Long/parseLong row)
                 :cljs (js/parseInt row))]
    {:col col :row row-n :ci (col->idx col) :ri (dec row-n)}))

(defn make
  "0-based col/row indices -> canonical address string."
  [ci ri]
  (str (idx->col ci) (inc ri)))

(defn valid?
  "Is `addr` a cell address this grid can actually hold? Syntax is only half of
   it — the address must also be IN BOUNDS:

   - the row is 1-based, so `A0` names no cell (it parses to row index -1, which
     renders nowhere but stores and persists happily — an invisible ghost);
   - both indices must fall inside MAX-COLS/MAX-ROWS, so an address can never be
     stored that the geometry pass cannot lay out.

   This is the single gate that keeps unrenderable addresses out of a sheet; the
   engine (`sheet/write-cell!`, `sheet/set-style!`) refuses anything it rejects."
  [addr]
  (boolean
   (when-let [[_ col row] (re-matches addr-re (str addr))]
     (and (<= (count col) MAX-COL-CHARS)
          (<= (count row) MAX-ROW-CHARS)
          (let [r #?(:clj (Long/parseLong row) :cljs (js/parseInt row))]
            (and (<= 1 r MAX-ROWS)
                 (< (col->idx col) MAX-COLS)))))))

(defn canon
  "Canonical form of a `valid?` address: \"a1\" -> \"A1\". Addresses are keys —
   in `:meta`, in the style map, in the `<sheet>|<branch>|<addr>|<prop>` datom
   key — so an uncanonicalized write makes \"a1\" a SECOND cell that the renderer
   (which always asks for the canonical form) never draws."
  [a]
  (let [{:keys [ci ri]} (parse a)] (make ci ri)))

(defn range-size
  "How many cells the inclusive rectangle a..b covers, computed from the corner
   INDICES. Callers cap ranges with this BEFORE `range-cells` materializes them —
   counting afterwards would already have paid the cost the cap exists to refuse."
  [a b]
  (let [{ca :ci ra :ri} (parse a)
        {cb :ci rb :ri} (parse b)]
    (* (inc (abs (- ca cb))) (inc (abs (- ra rb))))))

(defn range-cells
  "Inclusive rectangle from address a to address b, ROW-MAJOR.
   \"A1\" \"A3\" -> [A1 A2 A3] ; \"A1\" \"C1\" -> [A1 B1 C1]
   \"A1\" \"B2\" -> [A1 B1 A2 B2]."
  [a b]
  (let [{ca :ci ra :ri} (parse a)
        {cb :ci rb :ri} (parse b)
        [c0 c1] (sort [ca cb])
        [r0 r1] (sort [ra rb])]
    (vec (for [r (range r0 (inc r1))
               c (range c0 (inc c1))]
           (make c r)))))
