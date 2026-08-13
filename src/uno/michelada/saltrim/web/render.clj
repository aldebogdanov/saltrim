(ns uno.michelada.saltrim.web.render
  "Server-rendered HTML/SVG: the grid window, the page shell, every modal, the
   share/branch/graph/history fragments, and the auth pages. Pure output — reads
   state for presence, never pushes (that is `collab`)."
  (:require
            [clojure.string :as str]
            [hiccup2.core :as h]
            [jsonista.core :as json]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.auth :as auth]
            [uno.michelada.saltrim.db :as db]
            [uno.michelada.saltrim.errors :as errors]
            [uno.michelada.saltrim.excel :as excel]
            [uno.michelada.saltrim.fmt :as fmt]
            [uno.michelada.saltrim.graph :as graph]
            [uno.michelada.saltrim.merge :as mrg]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.stdlib :as lib]
            [uno.michelada.saltrim.store :as store]
            [uno.michelada.saltrim.version :as version]
            [uno.michelada.saltrim.xlsx :as xlsx]
            [uno.michelada.saltrim.constants :refer [CW RH GUT HDR OVER BAR]]
            [uno.michelada.saltrim.web.geom :refer [axis-x axis-y col-w covered in-window? rgba row-h span-px total-px url-decode url-encode view-base window]]
            [uno.michelada.saltrim.web.state :refer [def-editor-of owner-of session-view sessions* sheets*]]))

(def no-autofill
  "Attrs telling every browser/password-manager these are spreadsheet cells, not
   a login form — Safari's Keychain card was popping up over the in-cell editor
   (any text input can trigger it once the origin has a saved credential, not
   just fields that look like a username). autocomplete=\"off\" is the one Safari
   itself honors for non-login fields; the rest are the union of what other
   password managers look for (LastPass/1Password/Bitwarden don't share one)."
  {:autocomplete "off" :autocorrect "off" :autocapitalize "off" :spellcheck "false"
   :data-lpignore "true" :data-1p-ignore "true" :data-bwignore "true"})

(defn js-str
  "`v` as a JavaScript string LITERAL, for a value interpolated into a
   `data-on:*` handler.

   Hiccup's escaping does not protect those: the HTML parser un-escapes an
   attribute before the JS engine ever sees it, so `&#39;` arrives back as `'`
   and closes the string. Every value we splice today is sanitized far away
   (uid charset, chunk id, canonical address, prop keyword) — this makes that
   safety local instead of depending on a regex in another namespace."
  [v]
  (json/write-value-as-string (str v)))

(defn- display [sh a]
  (let [v    (sheet/value sh a)
        mask (sheet/style-value sh a :format)]   ; nil / string / {:error}
    (cond (nil? v) ""
          ;; an error shows WHICH failure it is (#DIV/0!, #N/A, …); the message
          ;; behind it rides the tooltip — see `cell-html`
          (map? v) (errors/label v)
          (string? mask) (fmt/apply-mask mask v)
          :else (str v))))

(defn- cell-id [a] (str "c_" a))

(def style-css
  "Reactive style prop -> CSS declaration. The whole supported set lives here;
   adding a property is one entry (+ a control in the style panel). Each value is
   a literal or an =-formula computed per cell into a CSS string."
  (array-map :bg           "background-color"
             :fg           "color"
             :weight       "font-weight"
             :slant        "font-style"
             :align        "text-align"
             :bordertop    "border-top"
             :borderright  "border-right"
             :borderbottom "border-bottom"
             :borderleft   "border-left"))

(def border-sides
  "Border side selector -> the concrete style props it writes. The style bar
   offers ONE `border` entry plus this side dropdown (four separate props behind
   it, so each side stays an independently reactive property). The option VALUE
   is the comma-joined prop list, which is also what the client reads back into
   the source box (first prop of the group) — one source of truth for the map."
  (array-map :all        [:bordertop :borderright :borderbottom :borderleft]
             :vertical   [:borderleft :borderright]
             :horizontal [:bordertop :borderbottom]
             :top        [:bordertop]
             :bottom     [:borderbottom]
             :left       [:borderleft]
             :right      [:borderright]))

(def border-prop
  "The pseudo-prop the style bar sends for a border; expanded via `border-sides`."
  :border)

(def ^:private border-prop-set (set (apply concat (vals border-sides))))

(defn border-props
  "The concrete props behind a `border` write: the comma-joined prop list carried
   by the side dropdown's value (e.g. \"bordertop,borderbottom\"). nil when the
   list is empty or names anything that isn't a border prop."
  [side]
  (let [ps (->> (str/split (str side) #",")
                (remove str/blank?)
                (mapv keyword))]
    (when (and (seq ps) (every? border-prop-set ps))
      ps)))

(def value-props
  "Presentational props consumed by transforming the displayed VALUE (not CSS).
   :format is a number mask (see the fmt ns). Same reactive literal-or-=formula
   model as style props — just applied in `display` instead of as a CSS decl."
  [:format])

(def meta-props
  "Per-cell METADATA props: stored + persisted like a style prop (so they ride
   the per-property datom model — branch/merge/as-of/undo for free) but neither
   rendered as CSS nor applied to the value. `:label` NAMES the cell — a short
   identifier used as its node label in the dependency-graph view. `:comment` is
   free prose ABOUT the cell (a note to a reader, or an audit trail left by the
   .xlsx importer): it never names anything, it only marks the cell and shows on
   hover."
  [:label :comment])

(defn prop-allowed? [p]
  (or (contains? style-css p) (some #{p} value-props) (some #{p} meta-props)
      ;; `:assert` rides the same per-property path but is NOT in `meta-props`,
      ;; so it stays out of the style-bar dropdown (it has its own input) while
      ;; still being writable through /style — which is what lets undo, paste and
      ;; the 3-way merge move it like any other prop.
      (= sheet/assert-prop p)))

;; --- CSS value safety ----------------------------------------------------
;;
;; A style prop's computed value is spliced into the cell's `style` attribute as
;; `<css-prop>:<value>`. Hiccup escapes quotes, so the value cannot break OUT of
;; the attribute — but a `;` starts a NEW declaration inside it, which is enough
;; to hand one collaborator arbitrary CSS on everyone else's cells:
;;
;;   bg = red;position:fixed;…;background-image:url(https://evil/leak.png)
;;
;; That is a tracking beacon fired from every viewer's browser (IP, UA, timing)
;; and a cell that can cover its neighbours. Formula-computed styles make it
;; invisible in the source, so the check has to sit on the COMPUTED string.
;;
;; None of the supported props (a colour, a weight, an alignment, a CSS border)
;; has any use for these characters, so an unsafe value is refused rather than
;; sanitized — silently dropping half a value would be more confusing than
;; saying no. `Content-Security-Policy` (see `web/security-headers`) is the
;; second layer: even a value that slipped through cannot reach a third-party
;; host.

(def ^:private css-unsafe-re
  ;; `;` `{` `}` end or nest a declaration; `<` `>` `\` and control characters
  ;; have no business in a CSS value; the rest are how a value fetches or
  ;; evaluates something (`url(...)`, `image-set(...)`, `@import`, legacy IE
  ;; `expression(...)`), plus `/*`, which could comment away what follows it.
  #"(?i)[;{}<>\\\x00-\x1f\x7f]|url\s*\(|image-set\s*\(|expression\s*\(|@import|/\*")

(defn css-value-ok?
  "Is `v` safe to splice into a cell's `style` attribute as one declaration?"
  [v]
  (and (string? v) (nil? (re-find css-unsafe-re v))))

(def ^:private css-unsafe-msg
  "not allowed in a style value (no ; { } < > \\ url( @import or comments)")

(defn css-errors
  "Seq of [prop msg] for `addr`'s style props whose COMPUTED value is unsafe to
   render — reported through the same toast as a style formula that throws, so a
   refused value is never silently invisible."
  [sh addr]
  (keep (fn [prop]
          (let [v (sheet/style-value sh addr prop)]
            (when (and (string? v) (not (css-value-ok? v)))
              [prop css-unsafe-msg])))
        (filter style-css (keys (sheet/style-srcs sh addr)))))

(def ^:private style-read-js
  "Datastar expression: load the selected cell's source for the CURRENT prop into
   the style box (`c` = that cell's element). For `border` the shown prop is the
   first side of the picked group — $borderside carries the comma-joined list."
  (str "$stylesrc=c.dataset.sty?(JSON.parse(c.dataset.sty)"
       "[$styleprop==='border'?$borderside.split(',')[0]:$styleprop]||''):''"))

(def ^:private assert-read-js
  "Datastar expression: load the selected cell's ASSERTION into the ⊨ box (`c` =
   that cell's element, already in scope at every call site). Same shape as
   `style-read-js` — the cell's `data-sty` already carries every prop it has, so
   nothing extra rides on the wire for this."
  "$assertsrc=c.dataset.sty?(JSON.parse(c.dataset.sty)['assert']||''):''")

(def style-bar-props
  "The prop dropdown's entries: every writable prop, with the four border sides
   collapsed behind the single `border` pseudo-prop (the side dropdown picks
   which of them a write lands on)."
  (concat (remove border-prop-set (keys style-css)) [border-prop] value-props meta-props))

(defn- cell-style-decls
  "Inline CSS for `a`'s style props (only those resolving to a SAFE string;
   errors, blanks and values that would inject extra declarations are skipped
   here and reported via the toast — see `css-value-ok?`)."
  [sh a]
  (apply str (keep (fn [[prop css]]
                     (let [v (sheet/style-value sh a prop)]
                       (when (css-value-ok? v) (str ";" css ":" v))))
                   style-css)))

(defn- cell-input
  "Minimal per-cell HTML: a DISPLAY div (not an input), positioned WINDOW-RELATIVE
   to (cbase,rbase). data-raw carries the source for the floating editor. A single
   click selects; Enter/double-click opens the editor (handled in /app.js). No
   per-cell input -> no 500 live <input>s. `span` = [rows cols] when this cell is
   a merge ANCHOR — it is then drawn spanning the whole rectangle (its covered
   neighbours are simply not rendered)."
  [sh a ci ri cbase rbase span]
  (let [disp (display sh a)
        raw  (or (sheet/raw sh a) disp)
        srcs (sheet/style-srcs sh a)       ; {prop raw} -> echoed into the style bar
        note (let [c (sheet/style-value sh a :comment)] (when (string? c) (not-empty c)))
        ;; the detail behind an error label — a cast failure's Java sentence, say.
        ;; Nothing for a plain Excel error, whose message IS the label already.
        why  (errors/detail (sheet/value sh a))
        ;; a failing assertion marks the cell (bottom-left wedge — `:comment`
        ;; owns the top-right one, and a cell can carry both)
        bad  (sheet/assert-violation sh a)
        ;; a merge anchor spans its rectangle; otherwise one cell (override OR the
        ;; sheet default). width/height always emitted so geometry is fully
        ;; data-driven: a default-size change re-renders and applies live.
        [w h] (if span
                (span-px sh ci ri (first span) (second span))
                [(col-w sh ci) (row-h sh ri)])]
    [:div {:id (cell-id a)
           :class (str "cell" (when note " noted") (when span " merged")
                       (when bad " badassert")
                       (when (= sheet/layer-over (get srcs sheet/layer-prop)) " over"))
           :data-raw raw
           ;; both can be present: the comment is what the cell is FOR, the
           ;; violation what is wrong with it right now
           :title (not-empty (str/join "\n" (remove nil? [note why (when bad (str "⚠ " bad))])))
           :data-sty (when (seq srcs) (json/write-value-as-string srcs))
           ;; the cell fills its WHOLE slot (it used to stop a pixel short, which
           ;; is what left a gap between two cells sharing a bg) and the grid
           ;; lines are painted beneath it — see `gridlines-html`
           :style (str (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx"
                               (- (axis-x sh ci) (axis-x sh cbase))
                               (- (axis-y sh ri) (axis-y sh rbase))
                               (long w) (long h))
                       (cell-style-decls sh a))}
     disp]))

(defn cells-html [sh cis ris]
  (let [anchors (sheet/merge-spans sh)
        hidden  (covered anchors)          ; cells swallowed by a merge -> not drawn
        cb (first cis) rb (first ris)]
    (str (h/html (for [ri ris ci cis
                       :let  [a (addr/make ci ri)]
                       :when (not (contains? hidden a))]
                   (cell-input sh a ci ri cb rb (get anchors a)))))))

(defn gridlines-html
  "The grid itself, as its OWN layer under the cells: one thin div per column and
   per row boundary of the rendered window.

   The lines used to be four borders on each cell, and each cell was drawn one
   pixel smaller than its slot — so between two neighbours sat a cell border, a
   one-pixel gap showing the page through, and the next cell's border. Invisible
   on an empty grid; on two adjacent cells sharing a `bg` it was a three-pixel
   scar through what should be one block of colour.

   Now a cell fills its whole slot and paints over the lines beneath it: two
   coloured neighbours meet seamlessly, and an unstyled (transparent) cell lets
   the same lines through as before. The band is reproduced exactly — a 3px
   element whose left/right (or top/bottom) border is the grid colour and whose
   middle pixel is transparent, which is pixel-for-pixel what the two borders and
   the gap used to draw. Each boundary is drawn once instead of twice, so this is
   also ~1/17th of the elements the per-cell borders needed."
  [sh cis ris]
  (let [xb (axis-x sh (first cis))
        yb (axis-y sh (first ris))
        ;; every rendered column's left edge, plus the far edge of the last one
        xs (map #(- (axis-x sh %) xb) (concat cis [(inc (last cis))]))
        ys (map #(- (axis-y sh %) yb) (concat ris [(inc (last ris))]))
        w  (last xs)
        h  (last ys)]
    (str (h/html
          (concat
           (for [x xs] [:div {:class "gl gv" :style (format "left:%dpx;height:%dpx;" (- (long x) 2) h)}])
           (for [y ys] [:div {:class "gl gh" :style (format "top:%dpx;width:%dpx;" (- (long y) 2) w)}]))))))

(defn colhead-html [sh cis]
  (let [xb (axis-x sh (first cis))]
    (str (h/html
          (for [ci cis :let [w (col-w sh ci)]]
            [:div {:style (format (str "position:absolute;left:%dpx;top:0;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-x sh ci) xb) w HDR HDR)}
             (addr/idx->col ci)
             ;; drag handle on the right edge -> resize this column (/app.js)
             [:div {:class "colgrip" :data-ci ci}]])))))

(defn rowhead-html [sh ris]
  (let [yb (axis-y sh (first ris))]
    (str (h/html
          (for [ri ris :let [h (row-h sh ri)]]
            [:div {:style (format (str "position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx;"
                                       "line-height:%dpx;text-align:center;background:#f3f3f3;"
                                       "border:1px solid #e0e0e0;font:12px sans-serif;box-sizing:border-box;")
                                  (- (axis-y sh ri) yb) GUT h h)}
             (inc ri)
             [:div {:class "rowgrip" :data-ri ri}]])))))

(defn meta-html
  "Hidden element carrying, to /app.js: the logical scroll totals (size the
   scrollbars) and the rendered window's base index cb/rb. /app.js translates the
   layers relative to cb/rb — patched together with #cells, so the transform
   always matches the displayed content (no jump while a fetch is in flight)."
  [sh {:keys [r0 c0] :as view}]
  (let [[tw th] (total-px sh view)
        [cb rb] (view-base view)]
    (str (h/html [:div {:id "meta" :data-tw tw :data-th th :data-cb cb :data-rb rb
                        ;; per-sheet default axis sizes (client geometry base)
                        :data-dcw (sheet/default-col-w sh) :data-drh (sheet/default-row-h sh)
                        ;; sparse axis-size overrides so /app.js computes offsets
                        :data-colw (json/write-value-as-string (sheet/col-widths sh))
                        :data-rowh (json/write-value-as-string (sheet/row-heights sh))
                        ;; merge spans {anchor-addr [rows cols]} so /app.js can
                        ;; step selection/nav over a merged block as one cell
                        :data-merges (json/write-value-as-string (sheet/merge-spans sh))
                        :style "display:none;"}]))))

(defn cells-layer-html
  "The whole cell layer: the grid lines and the cells, as SIBLINGS.

   They used to be two layers, grid under cells, which fixed the seam between two
   coloured neighbours but made the choice permanent — a styled cell always hid
   the grid beneath it. Most of the time you want the opposite: the fill tinting
   the row while the table's own ruling still reads. One layer lets each cell
   pick, because z-index only orders siblings: a separate layer is its own
   stacking context and nothing inside it can cross out.

   Order here is DOM order only; what actually decides is z-index (see the
   `.gl` / `.cell` / `.cell.over` rules)."
  [sh cis ris]
  (str (gridlines-html sh cis ris) (cells-html sh cis ris)))

(defn- grid-layers
  "Logical-scroll viewport: fills the page below the toolbars, overflow hidden.
   Clipped header strips + a cell area, each holding an absolutely-positioned
   layer that /app.js translates by the sub-cell offset. Plus the corner, the
   totals #meta, and two custom scrollbars. No giant spacer -> no row cap, no
   precision wobble."
  [sh view]
  (let [[cis ris] (window sh view)
        clip "position:absolute;overflow:hidden;"]
    (h/html
     [:div {:id "viewport"
            :data-cw CW :data-rh RH :data-gut GUT :data-hdr HDR
            :data-over OVER :data-bar BAR
            ;; Selection (single + range + multi-range), double-click-to-edit and
            ;; keyboard all live in app.cljs now — it owns the selection state that
            ;; the #selrange overlay + clipboard read. A plain click still ends up
            ;; setting $sel + mirroring the cell into the bars + posting presence,
            ;; via the sr-select bridge; Shift extends a range, Ctrl/⌘ adds one.
            ;; user-select:none kills the browser's blue text-highlight while
            ;; drag-selecting cells (mouse events still fire); #editor re-enables it
            ;; so typing/selecting inside a cell still works.
            ;; flex:1 + min-height:0 = fill whatever the toolbars leave, exactly
            ;; (a fixed vh left a dead strip under the grid). min-height:0 is what
            ;; lets a flex child shrink below its content.
            :style (str "position:relative;flex:1;min-height:0;border:1px solid #ccc;"
                        "overflow:hidden;user-select:none;-webkit-user-select:none;")}
      (h/raw (meta-html sh view))
      ;; corner
      [:div {:id "corner"
             :style (format (str "position:absolute;left:0;top:0;z-index:4;width:%dpx;height:%dpx;"
                                 "background:#e8e8e8;border:1px solid #e0e0e0;box-sizing:border-box;")
                            GUT HDR)}]
      ;; column header strip (clipped; translated in X)
      [:div {:id "colclip" :style (format "%sleft:%dpx;top:0;right:%dpx;height:%dpx;z-index:3;"
                                          clip GUT BAR HDR)}
       [:div {:id "colstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "colhead"} (h/raw (colhead-html sh cis))]]]
      ;; row header strip (clipped; translated in Y)
      [:div {:id "rowclip" :style (format "%sleft:0;top:%dpx;bottom:%dpx;width:%dpx;z-index:3;"
                                          clip HDR BAR GUT)}
       [:div {:id "rowstrip" :style "position:absolute;left:0;top:0;will-change:transform;"}
        [:div {:id "rowhead"} (h/raw (rowhead-html sh ris))]]]
      ;; cell area (clipped; translated in X+Y)
      [:div {:id "cellclip" :style (format "%sleft:%dpx;top:%dpx;right:%dpx;bottom:%dpx;"
                                           clip GUT HDR BAR BAR)}
       ;; The grid lines and the cells share ONE layer, so a cell can sit either
       ;; side of them by z-index (see `cells-layer-html`). Separate layers would
       ;; each be their own stacking context, and no per-cell z-index could ever
       ;; cross between them.
       [:div {:id "cells" :style "position:absolute;left:0;top:0;will-change:transform;"}
        (h/raw (cells-layer-html sh cis ris))]
       ;; multi-cell selection highlight — drawn CLIENT-side by app.cljs from its
       ;; selection state (ranges + multi-range), translated with #cells. Local
       ;; only: peers see your active cell via presence, not the whole marquee.
       [:div {:id "selrange" :style (str "position:absolute;left:0;top:0;z-index:1;"
                                         "pointer-events:none;will-change:transform;")}]
       ;; THIS user's own selection / editing marker — server-rendered from the
       ;; session's :cursor/:editing (no per-cell client JS). pointer-events:none
       ;; so it never blocks typing in the cell beneath. Translated with #cells.
       [:div {:id "self" :style (str "position:absolute;left:0;top:0;z-index:2;"
                                     "pointer-events:none;will-change:transform;")}]
       ;; collaborator cursors / edit-locks; translated with #cells by /app.js.
       ;; container ignores pointer events — only an editing marker re-enables
       ;; them to block the cell beneath.
       [:div {:id "peers" :style (str "position:absolute;left:0;top:0;z-index:3;"
                                      "pointer-events:none;will-change:transform;")}]
       ;; the single floating editor, translated with #cells. app.cljs positions
       ;; it over the active cell and moves focus in; everything else is
       ;; declarative: data-bind:v shares $v with the formula bar, data-show
       ;; reveals it on $edit, Enter/blur commit (@post '/cell' + drop the edit
       ;; lock), Esc cancels. preventDefault is INLINE for Enter/Esc only — a
       ;; `__prevent` MODIFIER fires on every keydown and would block all typing.
       ;; `__stop` keeps these keys from the document-level nav handler.
       [:div {:id "editlayer" :style "position:absolute;left:0;top:0;will-change:transform;"}
        ;; Visibility is $celledit (set ONLY by start-edit!, which also positions +
        ;; sizes it over the cell) — NOT $edit, which the formula bar also sets for
        ;; presence/peer-lock. Sharing $edit would pop this box, unpositioned and
        ;; at its default size, on every formula-bar focus.
        [:input (merge no-autofill
                {:id "editor" :data-bind:v "" :data-show "$celledit"
                 :data-on:keydown__stop
                 (str "evt.key==='Enter' ? (evt.preventDefault(),$cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                      " : evt.key==='Escape' ? (evt.preventDefault(),$edit=false,$celledit=false,@post('/presence')) : null")
                 :data-on:blur "$celledit && ($cell=$sel,@post('/cell'),$edit=false,$celledit=false,@post('/presence'))"
                 :style "display:none;"})]]]
      ;; custom scrollbars
      [:div {:id "vbar" :style (format (str "position:absolute;right:0;top:%dpx;bottom:%dpx;width:%dpx;"
                                            "background:#f0f0f0;z-index:5;") HDR BAR BAR)}
       [:div {:id "vthumb"
              :style "position:absolute;left:1px;right:1px;top:0;height:30px;background:#bbb;border-radius:6px;"}]]
      [:div {:id "hbar" :style (format (str "position:absolute;left:%dpx;bottom:0;right:%dpx;height:%dpx;"
                                            "background:#f0f0f0;z-index:5;") GUT BAR BAR)}
       [:div {:id "hthumb"
              :style "position:absolute;top:1px;bottom:1px;left:0;width:30px;background:#bbb;border-radius:6px;"}]]
      ;; single moving guide line shown while dragging a header grip
      [:div {:id "rzguide"}]])))

(declare share-html)

(def ^:private help-h3  "margin:.8rem 0 .25rem;font:600 13px sans-serif;")
(def ^:private help-p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);")
(def ^:private help-kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;")

;; The help modal is split across help-sections-* fns: as a single defn its body
;; compiled to one method that overran the JVM's 64KB bytecode limit ("Method
;; code too large"). Each part is now its own (small) method, spliced by help-html.
(defn- help-sections-a []
  (let [h3 help-h3 p help-p kbd help-kbd]
    (list
            [:div {:style h3} "Cells & formulas"]
            [:p {:style p} "Type a value, or start with " [:span {:style kbd} "="]
             " for a formula (Clojure s-expressions). Reference cells with "
             [:span {:style kbd} "$A1"] " and ranges with " [:span {:style kbd} "$A1:A3"]
             " — shorthand for the reader tags " [:span {:style kbd} "#cell A1"]
             " / " [:span {:style kbd} "#cells A1:A3"] "."]
            [:p {:style p} "e.g. " [:span {:style kbd} "=(+ $A1 $B1)"] " · "
             [:span {:style kbd} "=(reduce + $A1:A3)"]]
            [:p {:style p} [:span {:style kbd} "$A1:B2"] " is FLAT and row-major — "
             [:span {:style kbd} "[A1 B1 A2 B2]"] " — which is what you want for "
             [:span {:style kbd} "sum"] ", " [:span {:style kbd} "map"] " and the rest. "
             "When a function needs to know the rectangle's SHAPE, write "
             [:span {:style kbd} "#area A1:B2"] " instead: it gives you a vector of "
             "ROWS, " [:span {:style kbd} "[[A1 B1] [A2 B2]]"] ". Excel's "
             [:span {:style kbd} "xl/"] " functions are defined over rectangles, so "
             [:span {:style kbd} "=(xl/TRANSPOSE #area A1:B2)"] " is right where the "
             "flat form would quietly transpose a single column."]
            [:p {:style p} "It is ordinary Clojure inside: " [:span {:style kbd} "let"] ", "
             [:span {:style kbd} "fn"] " and the " [:span {:style kbd} "#(…)"]
             " literal all work — e.g. "
             [:span {:style kbd} "=(reduce + (map #(* % %) $A1:A10))"]
             ". (The reader is EDN, so " [:span {:style kbd} "'x"] " isn't available; write "
             [:span {:style kbd} "(quote x)"] ".)"]
            [:p {:style p} "Relative refs point by offset from the cell itself: "
             [:span {:style kbd} "$<col><row>"] " where each part is "
             [:span {:style kbd} "_"] " (same), " [:span {:style kbd} "+N"] ", or "
             [:span {:style kbd} "-N"] ". They survive copy/paste, so they fill series. "
             "e.g. " [:span {:style kbd} "=(inc $_-1)"] " copied down counts up; "
             [:span {:style kbd} "=(+ $-2_ $-1_)"] " copied across is Fibonacci."]
            [:p {:style p} "Dynamic refs compute the ADDRESS at runtime — a reactive INDIRECT: "
             [:span {:style kbd} "$(expr)"] " where the expression yields "
             [:span {:style kbd} "\"A5\""] " or a range " [:span {:style kbd} "\"A1:B3\""] ". "
             "e.g. " [:span {:style kbd} "=$(str \"A\" $B1)"] " reads column A at the row B1 names; "
             [:span {:style kbd} "=(sum $(str \"A1:A\" $B1))"] " sums a range whose extent follows B1. "
             "Reacts to the address inputs AND the target's value. The computed target doesn't "
             "shift on paste (refs inside the expression do); a bad address or a cycle through "
             "the target shows " [:span {:style kbd} "#ERR"] ". The 🕸 graph draws these edges dashed."]
            [:p {:style p} "Built-in functions: math (" [:span {:style kbd} "sum"] ", "
             [:span {:style kbd} "round"] ", " [:span {:style kbd} "sqrt"] "…), stats ("
             [:span {:style kbd} "mean"] ", " [:span {:style kbd} "median"] ", "
             [:span {:style kbd} "stdev"] "), text (" [:span {:style kbd} "upper"] ", "
             [:span {:style kbd} "join"] ", " [:span {:style kbd} "split"] "…), date ("
             [:span {:style kbd} "today"] ", " [:span {:style kbd} "year"] ", "
             [:span {:style kbd} "days-between"] ")."]
            [:p {:style p} "A blank cell reads as " [:span {:style kbd} "nil"] ". Aggregates ("
             [:span {:style kbd} "sum"] ", " [:span {:style kbd} "mean"] ", …) ignore blanks, so "
             [:span {:style kbd} "=(sum $B1:B20)"] " works even with empty rows. In plain arithmetic "
             "wrap a maybe-blank cell to treat it as zero: " [:span {:style kbd} "=(+ (or $B5 0) 1)"] "."]

            [:div {:style h3} "When a cell fails"]
            [:p {:style p} "A broken cell shows WHICH failure it is — "
             [:span {:style kbd} "#DIV/0!"] ", " [:span {:style kbd} "#VALUE!"] ", "
             [:span {:style kbd} "#N/A"] ", " [:span {:style kbd} "#REF!"] ", "
             [:span {:style kbd} "#NAME?"] ", " [:span {:style kbd} "#NUM!"] " — with the details on "
             "hover. " [:span {:style kbd} "#ERR"] " is the catch-all for anything else, and "
             [:span {:style kbd} "#TIMEOUT!"] " means a formula never finished. Errors travel: a cell "
             "reading a broken one breaks the same way."]
            [:p {:style p} "Formulas can handle a failure instead of showing it: "
             [:span {:style kbd} "=(if-error (/ $A1 $B1) 0)"] " falls back to 0, "
             [:span {:style kbd} "if-na"] " catches ONLY a lookup miss (so a real bug still surfaces), "
             "and " [:span {:style kbd} "error-type"] " / " [:span {:style kbd} "error?"] " let you "
             "branch: " [:span {:style kbd} "=(if (= :na (error-type (vlookup …))) \"none\" \"ok\")"] ". "
             "These guard the expression you wrap — they can't catch an error that arrived from "
             "another cell, which reaches this one before the formula runs."]

            [:div {:style h3} "Reusable functions (ƒ)"]
            [:p {:style p} "The " [:span {:style kbd} "ƒ"] " button (top bar) opens this sheet's "
             "definitions library: write your own functions/constants as separate entries and call "
             "them from any cell. e.g. add "
             [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"] " then use "
             [:span {:style kbd} "=(margin $A1 $B1)"] ". Each entry collapses to name "
             "badges; Edit expands it and " [:span {:style kbd} "⤢"] " opens a big editor (also next "
             "to the formula and style bars). While you edit one it's locked for other "
             "collaborators; saving recompiles every cell."]

            [:div {:style h3} "Styling a cell"]
            [:p {:style p} "Use the third toolbar row: pick a property, type a value or an "
             [:span {:style kbd} "="] "-formula, press Enter. "
             [:span {:style kbd} "$val"] " is the selected cell's own value, so styles can react to it. "
             "Dynamic refs " [:span {:style kbd} "$(expr)"] " aren't supported in style formulas yet."]
            [:p {:style p} "Properties: " [:span {:style kbd} "bg"] " (background), "
             [:span {:style kbd} "fg"] " (text color), " [:span {:style kbd} "weight"] " (e.g. bold), "
             [:span {:style kbd} "slant"] " (e.g. italic), " [:span {:style kbd} "align"] " (left/right/center), "
             [:span {:style kbd} "border"] " (a CSS border like " [:span {:style kbd} "1px solid black"]
             ") — picking " [:span {:style kbd} "border"] " reveals a second dropdown for the side(s) it "
             "applies to: all, vertical, horizontal, top, bottom, left, right."]
            [:p {:style p} "e.g. bg " [:span {:style kbd} "=(if (> $val 100) \"tomato\" \"white\")"]]
            [:p {:style p} "Select a range first and a style applies to every cell in it."]
            [:p {:style p} "A fill sits " [:b "under"] " the grid lines, so the table's ruling still "
             "reads across it. " [:span {:style kbd} "over grid"] " (format row) lifts the selected "
             "cells above the lines instead — neighbours sharing a fill then become one solid block "
             "of colour. " [:span {:style kbd} "under grid"] " puts them back."]

            [:div {:style h3} "Labels and comments"]
            [:p {:style p} "Two more properties in the same row describe the cell rather than paint it. "
             [:span {:style kbd} "label"] " NAMES it, and a name is a reference: label a cell "
             [:span {:style kbd} "rate"] " and any formula can say " [:span {:style kbd} "$rate"]
             " instead of its address. The label also replaces the address in the 🕸 dependency graph."]
            [:p {:style p} "Put the " [:b "same"] " label on several cells and the name means all of them, "
             "in row-major order — that is a named range: " [:span {:style kbd} "=(sum $sales)"]
             ". If they form a full rectangle, " [:span {:style kbd} "#area sales"]
             " gives it as rows, for " [:span {:style kbd} "transpose"] " / "
             [:span {:style kbd} "matmul"] " and friends."]
            [:p {:style p} "A name follows its cell: insert a row above it and every "
             [:span {:style kbd} "$rate"] " still finds it. A name nothing carries is "
             [:span {:style kbd} "#NAME?"] " — write the formula first and label the cell after, if you like. "
             "An address always wins, so a cell labelled " [:span {:style kbd} "q1"]
             " is not reachable as " [:span {:style kbd} "$q1"] "."]
            [:p {:style p} [:span {:style kbd} "comment"] " is a note ABOUT the cell: it gets "
             "a small corner flag and shows the text on hover. The .xlsx importer leaves its audit trail "
             "as comments, and turns a workbook's defined names into labels."]

            [:div {:style h3} "Insert / delete rows and columns"]
            [:p {:style p} "The " [:span {:style kbd} "insert"] " buttons (format row) add a blank row/column "
             "next to the selected cell. Cells shift and formula references follow the shift; it's one "
             [:span {:style kbd} "Ctrl/⌘+Z"] " to undo."]
            [:p {:style p} "The " [:span {:style kbd} "delete"] " buttons remove the row/column the selected "
             "cell is on. Cells after it shift back, and ranges that crossed it lose exactly that one cell — "
             "but a formula that pointed " [:b "at"] " a deleted cell becomes "
             [:span {:style kbd} "#REF!"] " rather than quietly reading whichever cell moved into its place. "
             "One " [:span {:style kbd} "Ctrl/⌘+Z"] " restores the whole line, values and styling included."]

            [:div {:style h3} "Merge cells"]
            [:p {:style p} "Select a range and press " [:span {:style kbd} "⛶ merge"] " (format row): the "
             "top-left cell swallows the rest of the rectangle into one big cell, keeping its own address. "
             "The swallowed cells are only " [:b "hidden"] " — their values and formulas are kept, so a "
             "formula that references one still works, and " [:span {:style kbd} "unmerge"] " (or "
             [:span {:style kbd} "Ctrl/⌘+Z"] ") brings them back unchanged. A merged cell navigates and "
             "edits as a single cell."]

            [:div {:style h3} "Dependency graph"]
            [:p {:style p} "The " [:span {:style kbd} "🕸"] " button draws how cells feed each other "
             "(an arrow points from a cell to the cells that use it); click a node to select it. "
             "Set a cell's " [:span {:style kbd} "label"] " (style row) to name its node."]

            [:div {:style h3} "Assertions"]
            [:p {:style p} "The " [:span {:style kbd} "⊨"] " button on the formula row gives the selected "
             "cell a claim about its own value — " [:span {:style kbd} "=(< $val 100)"] ", "
             [:span {:style kbd} "=(some? $val)"] " to require it be filled in. It is checked every time "
             "the sheet recomputes, so a cell can start failing because something it depends on changed, "
             "not because anyone touched it."]
            [:p {:style p} "An assertion never blocks an edit — it reports. The cell gets a red corner mark, "
             "a gold message appears the moment it starts failing (click it to jump there), and a "
             [:span {:style kbd} "⚠"] " counter in the toolbar lists everything currently failing, including "
             "cells far outside the screen. Blank the box to remove the claim."])))

(defn- help-sections-b []
  (let [h3 help-h3 p help-p kbd help-kbd]
    (list
            [:div {:style h3} "Number format"]
            [:p {:style p} "Property " [:span {:style kbd} "format"] " takes a mask applied to numeric values: "
             [:span {:style kbd} "0.00"] " → 1234.50 · " [:span {:style kbd} "#,##0"] " → 1,234,567 · "
             [:span {:style kbd} "0.0%"] " → 25.0% · " [:span {:style kbd} "$#,##0.00"] " → $1,234.50"]

            [:div {:style h3} "Column & row size"]
            [:p {:style p} "Drag the trailing edge of a column header (or the bottom edge of a row "
             "number) to resize it. Sizes are saved with the sheet. Dragging "
             [:b "snaps"] " to multiples of the sheet default (1×, 2×, 3×…); hold "
             [:span {:style kbd} "Alt"] " while dragging to size freely."]
            [:p {:style p} "Owners can set the sheet-wide default column width / row height in "
             [:span {:style kbd} "⚙"] " (Sheet properties, top bar)."]

            [:div {:style h3} "Navigation"]
            [:p {:style p} "Click to select · double-click or " [:span {:style kbd} "Enter"]
             " to edit · arrows / " [:span {:style kbd} "Tab"] " to move · the address box jumps to a cell."]

            [:div {:style h3} "Selecting ranges"]
            [:p {:style p} [:span {:style kbd} "Shift"] "+click or " [:span {:style kbd} "Shift"]
             "+arrows extends a range · " [:span {:style kbd} "Ctrl/⌘"] "+click adds another range · "
             [:span {:style kbd} "Delete"] " clears the selected cells (undoable)."]

            [:div {:style h3} "Copy / paste"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+C"] " copy · " [:span {:style kbd} "Ctrl/⌘+X"]
             " cut · " [:span {:style kbd} "Ctrl/⌘+V"] " paste at the selected cell. Pasted formulas "
             "shift their references relative to the move (copy " [:span {:style kbd} "=(+ $A1 1)"]
             " down a row pastes " [:span {:style kbd} "=(+ $A2 1)"] ")."]
            [:p {:style p} "Select a range before pasting to " [:b "fill"] " it: a single copied cell "
             "lands in every selected cell (with relative refs re-resolved), so e.g. one "
             [:span {:style kbd} "=(inc $_-1)"] " pasted down a column is a running counter."]

            [:div {:style h3} "Undo / redo"]
            [:p {:style p} [:span {:style kbd} "Ctrl/⌘+Z"] " undoes your last edit · "
             [:span {:style kbd} "Ctrl/⌘+Shift+Z"] " (or " [:span {:style kbd} "Ctrl+Y"] ") redoes. "
             "Undo only affects your own edits — a cell a collaborator changed after you is left alone."]

            [:div {:style h3} "Flatten a formula"]
            [:p {:style p} "Select a formula cell and press " [:span {:style kbd} "⧉"]
             " (formula bar): every formula it references is inlined in place, recursively, "
             "into one self-contained expression — references to plain values stay references — "
             "and the result is simplified toward idiomatic Clojure (constants folded, nested "
             [:span {:style kbd} "(+ (+ a b) c)"] " flattened, " [:span {:style kbd} "(+ x 1)"]
             " → " [:span {:style kbd} "(inc x)"] "…). The result opens in the big editor for "
             "review — nothing changes until you press Apply (a normal, undoable edit)."]
            [:p {:style p} "Tick " [:span {:style kbd} "strict"] " to keep only rewrites that "
             "preserve error behavior exactly — e.g. without it " [:span {:style kbd} "(+ x 0)"]
             " becomes " [:span {:style kbd} "x"] ", which turns an error over a blank cell into "
             "a blank."]

            [:div {:style h3} "Branches"]
            [:p {:style p} "A branch is a parallel version of the sheet you can edit without touching the "
             "others — like git for spreadsheets. The " [:span {:style kbd} "🌿"] " picker (top bar) "
             "switches branches; people on different branches don't see each other's cells. The owner's "
             [:span {:style kbd} "⑂"] " button forks the current branch into a new one, deletes a "
             "non-main branch, or merges another branch in — a 3-way merge that auto-applies "
             "non-overlapping changes and lets you resolve conflicts cell by cell. The "
             [:span {:style kbd} "🕘"] " button opens an earlier revision as a read-only snapshot "
             "(time-travel); Back to live returns to the current sheet."]

            [:div {:style h3} "Sharing"]
            [:p {:style p} "The link / lock button (top bar, owner only) shares the sheet by capability "
             "link or with specific people, at view or edit level."]

            [:div {:style h3} "Export to Excel"]
            [:p {:style p} "The " [:span {:style kbd} "⬇ xlsx"] " button downloads the sheet as an "
             ".xlsx file, " [:b "live where it can be"] ": a formula whose functions have Excel "
             "equivalents is written as a real Excel formula, so the workbook "
             [:b "recalculates in Excel"] " — change an input there and the results follow. "
             "SaltRim's own answer is stored as the cached value, so it opens showing the "
             "right numbers straight away."]
            [:p {:style p} "A formula with " [:b "no Excel spelling"] " — one calling your own "
             "ƒ definitions, a dynamic " [:span {:style kbd} "$(…)"] " reference, or any Clojure "
             "Excel has no function for — falls back to its " [:b "computed value"] " for that "
             "cell only, so a mostly-translatable sheet exports mostly live. Either way the "
             "original Clojure source is attached as a cell comment (saying so when the formula "
             "didn't cross), and styling and number format come along. Borders do not."]

            [:div {:style h3} "Import from Excel"]
            [:p {:style p} "The " [:span {:style kbd} "⬆ xlsx"] " button imports an Excel workbook: "
             "each tab becomes a " [:b "new sheet"] " of yours. Unlike export, import is " [:b "live"]
             ": Excel formulas are " [:b "translated to Clojure"] " (SUM → "
             [:span {:style kbd} "sum"] ", AVERAGE → " [:span {:style kbd} "mean"] ", IF → "
             [:span {:style kbd} "if"] ", IFERROR → " [:span {:style kbd} "if-error"]
             "…) and keep recomputing. Values, styling, number formats and column/row sizes carry over; "
             "dates become ISO strings (" [:span {:style kbd} "2024-03-15"] ")."]
            [:p {:style p} "Anything untranslatable (cross-sheet references, named ranges, exotic "
             "functions) is imported as its last " [:b "computed value"] ", with the original Excel "
             "formula kept as the cell's " [:span {:style kbd} "comment"] ". Every translated formula is "
             "verified against Excel's own cached value — mismatches are demoted to values the same "
             "way, so an imported sheet is always correct. The import report lists all of it."])))

(defn- help-sections-c []
  (let [h3 help-h3 p help-p kbd help-kbd]
    (list
            [:div {:style h3} "AI agents (MCP)"]
            [:p {:style p} "SaltRim speaks the Model Context Protocol, so an AI agent can work in your "
             "sheets as a collaborator (served at " [:span {:style kbd} "POST /mcp"] "). Press "
             [:span {:style kbd} "🔑"] " (top bar) to mint an " [:b "agent key"] " — one credential that "
             "reaches every sheet you can, so a new sheet needs no reconfiguring. It carries "
             [:b "your"] " access and nothing more. The secret is shown once; rotate or revoke it from "
             "the same panel at any time."]
            [:p {:style p} "Agent writes never touch " [:span {:style kbd} "main"] " — the first write "
             "auto-forks it into the agent's own branch, which you review and merge (or delete) from the "
             [:span {:style kbd} "🌿"] " branches panel, the same as any human's fork. Because the sheet "
             "is reactive, agents write " [:b "formulas"] ", not pasted numbers, so the model keeps "
             "recalculating after the agent is done."])))

(defn- help-html
  "In-app end-user reference, toggled by $help. Mirrors README's user guide.
   Pure server-rendered HTML shown/hidden by Datastar data-show — no app.js.
   Body split across help-sections-* to stay under the JVM 64KB method limit."
  []
  (str (h/html
        [:div {:id "helpwrap" :data-show "$help"
               :data-on:click "$help=false"
               :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                           "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
         [:div {:data-on:click "evt.stopPropagation()"
                :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                            "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:38rem;width:100%;"
                            "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
          [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
           [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "SaltRim — quick guide"]
           [:button {:class "btn" :data-on:click "$help=false" :title "close"} "✕"]]
          (help-sections-a)
          (help-sections-b)
          (help-sections-c)
          [:div {:style (str "margin-top:1rem;padding-top:.6rem;border-top:1px solid var(--line);"
                             "font:11px sans-serif;color:#9aa1a9;text-align:center;")}
           "SaltRim " (version/current) " · "
           [:a {:href "https://github.com/aldebogdanov/saltrim" :target "_blank" :rel "noopener"
                :style "color:inherit;"} "GitHub"]
           " · "
           [:a {:href "/privacy" :target "_blank" :style "color:inherit;"} "Privacy"]
           " · "
           [:a {:href "/terms" :target "_blank" :style "color:inherit;"} "Terms"]
           " · © 2026 "
           [:a {:href "mailto:sasha_bogdanov_dev@yahoo.com"
                :style "color:inherit;"} "Aleksandr Bogdanov"]
           ", MIT License"]]])))

(declare deflib-html bigedit-html)

(def ^:private stdlib-reference
  "Read-only reference of the built-in functions (always available, can't be
   edited), grouped by category, as SYMBOLS — each one gets its own chip with a
   description and a copyable example, so the panel is a reference rather than a
   word list. The hand-written groups are spelled out here; the borrowed ones
   come from `stdlib/catalog-syms`, so the panel cannot drift from what is
   actually installed."
  (concat
   [["core math"    '[sum product abs ceil floor round sqrt pow exp ln log10 sign pi]]
    ["matrices"     '[transpose matmul det inverse]]
    ["core stats"   '[mean avg median variance stdev xmin xmax]]
    ["core text"    '[upper lower trim join split str-replace
                      starts-with? ends-with? includes? blank?]]
    ["core date"    '[today year month day days-between]]
    ["excel-compat" '[excel-truthy xround xdate xvlookup as-rows]]
    ["errors"       '[if-error if-na error-type error?]]]
   (for [[cat syms] lib/catalog-syms]
     ;; the matrix four are listed above under their own heading
     [cat (remove '#{det inverse} syms)])))

(def ^:private borrowed-syms
  "The stdlib names whose IMPLEMENTATION is Excel's, borrowed through
   rechentafel — as opposed to the ones written here.

   Per SYMBOL rather than per group, because the two do not line up: `det` and
   `inverse` sit under `matrices` next to `transpose` and `matmul` but are
   borrowed, and the borrowed `matrix` group holds `linest` and `trend`. A chip
   that claimed otherwise would be wrong about the two things the distinction
   decides — whether the semantics were chosen here, and whether ⧉ gives you a
   few self-contained lines or 4KB plus a dependency."
  (set lib/borrowed-syms))

(def ^:private xl-only-names
  "The Excel functions with NO Clojure spelling — `xl/` and only `xl/`.

   The panel used to list all 411 exposed names here, right under a stdlib that
   already covers 267 of them, which reads as a wholesale duplicate and raises
   the fair question of why both exist. They exist for different jobs: an
   imported formula is translated to the Clojure name whenever there is one (the
   stdlib's ~238 borrowed, plus the importer's ~36 hand-mapped), and `xl/` is
   what is left over — the reason a workbook full of unfamiliar functions still
   imports as something that RECALCULATES."
  (set (remove (into (set lib/borrowed-names) xlsx/hand-mapped)
               excel/exposed-names)))

(defn- fn-chip
  "One function in the reference: its name, a hover tooltip carrying the
   description and a runnable example, and a button that copies the function's
   SOURCE.

   Source rather than the example, because of what people actually need it for:
   you import a workbook or flatten a formula, end up with one large expression
   full of `sum` / `xround` / `xvlookup`, and want to run that calculation in an
   ordinary Clojure application where none of those names exist. The example is
   for reading; the source is for taking away. `stdlib/source-for` brings the
   private helpers along, so what lands in the clipboard compiles on its own.

   The tooltip is pure CSS (`content: attr(data-tip)`) — no per-chip markup and
   nothing to position — and the copy is one delegated listener in `app.cljs`,
   so 284 of these cost 284 spans and zero handlers.

   A hand-written function's source rides along in `data-copy`, a few lines each.
   A BORROWED one carries only its name in `data-src`, and `app.cljs` asks
   `/fnsrc` for it: those are rechentafel's real implementations with the
   helpers they need, ~5KB apiece and 1.2MB over the whole panel — a page-load
   cost every user would pay for the one function somebody eventually copies."
  [sym]
  (let [{:keys [desc eg src fetch]} (lib/docs-for sym)
        own? (not (borrowed-syms sym))]
    [:span {:class (str "fnref" (when own? " own"))
            :data-tip (str desc "\n\n" eg)}
     [:span {:class "fnmark" :title (if own?
                                      "SaltRim's own — semantics we chose"
                                      "borrowed from Excel, implemented by rechentafel")}
      (if own? "◆" "◇")]
     (str sym)
     (when (or src fetch)
       [:button (cond-> {:class "fncopy" :title (str "copy the source of " sym)}
                  src   (assoc :data-copy src)
                  fetch (assoc :data-src (str sym)))
        "⧉"])]))

(defn- defs-html
  "The definitions LIBRARY modal, toggled by $defspanel. The editable library
   (#deflib) is a server-rendered fragment of chunks — each edited and locked
   independently for collaboration, all merged into the sheet's program. Below it
   is the read-only built-in stdlib reference. Pure server HTML + Datastar."
  [storage-id]
  (let [p   "margin:.2rem 0;font:13px sans-serif;color:var(--fg);"
        kbd "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"]
    (str (h/html
          [:div {:id "defswrap" :data-show "$defspanel"
                 :data-on:click "$defspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:44rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Definitions library"]
             [:button {:class "btn" :data-on:click "$defspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Functions and constants reusable by every formula in this sheet, kept as "
             "separate entries. Editing one locks it for other collaborators; they all merge (in order) "
             "into the sheet's program. Same sandbox as formulas — pure, no host interop."]
            [:p {:style p} "e.g. " [:span {:style kbd} "(defn margin [rev cost] (/ (- rev cost) rev))"]
             " → in a cell " [:span {:style kbd} "=(margin $A1 $B1)"]]
            ;; dynamic, per-session library fragment (pushed on changes)
            [:div {:id "deflib"} (h/raw (deflib-html nil storage-id))]
            ;; read-only built-ins
            [:details {:style "margin-top:.9rem;"}
             [:summary {:style "font:600 13px sans-serif;cursor:pointer;color:var(--muted);"}
              "Built-in functions (read-only)"]
             [:p {:style (str p "margin-left:.4rem;color:var(--muted);")}
              "Hover a name for what it does and an example. "
              [:span {:style kbd} "⧉"] " copies its "
              [:b "source"] " — helpers included, ready to paste into a Clojure "
              "project, for when a flattened or imported formula has to run "
              "outside SaltRim."]
             ;; the mark is not decoration: it tells you whether the semantics
             ;; were chosen here, and what ⧉ is about to put on your clipboard
             [:p {:style (str p "margin-left:.4rem;color:var(--muted);")}
              [:span {:class "fnmark" :style "color:var(--accent);"} "◆"]
              " " [:b "ours"] " — written here, and the source is a few "
              "self-contained lines. "
              [:span {:class "fnmark"} "◇"]
              " " [:b "borrowed"] " from Excel and implemented by "
              [:a {:href "https://github.com/replikativ/rechentafel" :target "_blank"
                   :rel "noopener" :style "color:var(--accent);"} "rechentafel"]
              " (Apache-2.0): same name, Excel's numerics, and the source is "
              "that implementation plus the value bridge — a few KB, and it "
              "needs the library on your classpath."]
             (for [[cat syms] stdlib-reference]
               [:div {:style "margin:.35rem 0 .1rem .4rem;"}
                [:div {:style "font:600 12px sans-serif;color:var(--muted);margin-bottom:.15rem;"}
                 cat]
                [:div (map fn-chip syms)]])]
            ;; Excel interop. Deliberately second, deliberately folded, and
            ;; deliberately not called a stdlib: formulas are Clojure, and this
            ;; is the boundary for what comes out of (and goes back into) .xlsx.
            [:details {:style "margin-top:.4rem;"}
             [:summary {:style "font:600 13px sans-serif;cursor:pointer;color:var(--muted);"}
              (str "Excel interop — " (count xl-only-names) " more, under xl/")]
             [:p {:style (str p "margin-left:.4rem;color:var(--muted);")}
              "Only what has no Clojure name. Of Excel's "
              (count excel/exposed-names) " functions, " (- (count excel/exposed-names)
                                                            (count xl-only-names))
              " are already listed above and an import translates to those — "
              [:span {:style kbd} "PMT(…)"] " arrives as " [:span {:style kbd} "(pmt …)"]
              ", not as " [:span {:style kbd} "xl/PMT"] ". These "
              (count xl-only-names) " are the remainder, reachable under an "
              [:span {:style kbd} "xl/"] " prefix — " [:span {:style kbd} "=(xl/DSUM …)"]
              " — so an imported formula that uses one stays live instead of "
              "collapsing to the number it last computed."]
             [:p {:style (str p "margin-left:.4rem;color:var(--muted);")}
              "Ranges arrive as a column; reshape with "
              [:span {:style kbd} "xl/as-rows"] " when a function wants a table: "
              [:span {:style kbd} "=(xl/VLOOKUP $A1 (xl/as-rows 2 $B1:C9) 2 false)"]
              ". Dates here are Excel serials, not ISO strings: "
              [:span {:style kbd} "=(xl/YEAR (xl/date->serial $A1))"] "."]
             (for [[cat names] excel/catalog
                   :let [names (filter xl-only-names names)]
                   :when (seq names)]
               [:p {:style (str p "margin-left:.4rem;")}
                [:b cat] ": " [:span {:style kbd} (str/join " " names)]])]]]))))

(defn- props-html
  "Owner-only Sheet properties modal, toggled by $propspanel. Today: the sheet's
   DEFAULT column width / row height (px) — the size of any unsized column/row.
   Built as a labelled grid so more sheet-wide settings slot in as new rows.
   $pcw/$prh are server-seeded with the current values; Apply posts /props.
   Also carries an owner-only danger zone: a two-step delete of the whole sheet
   ($delconfirm arms it) that posts /delete-sheet."
  [sname]
  (let [p     "margin:.2rem 0 .7rem;font:13px sans-serif;color:var(--muted);"
        lbl   "font:13px sans-serif;color:var(--fg);align-self:center;"
        num   "font:13px monospace;padding:5px 6px;border:1px solid var(--line);border-radius:var(--radius);background:var(--panel);width:6rem;"]
    (str (h/html
          [:div {:id "propswrap" :data-show "$propspanel"
                 :data-on:click "$propspanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:30rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Sheet properties"]
             [:button {:class "btn" :data-on:click "$propspanel=false" :title "close"} "✕"]]
            [:p {:style p} "Sheet-wide defaults. Individual columns/rows you've dragged keep their own size."]
            [:div {:style "display:grid;grid-template-columns:auto 1fr;gap:.55rem .8rem;align-items:center;"}
             [:label {:style lbl :for "pcw"} "Default column width"]
             [:input {:id "pcw" :type "number" :min "24" :max "2000" :step "1"
                      :data-bind:pcw "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]
             [:label {:style lbl :for "prh"} "Default row height"]
             [:input {:id "prh" :type "number" :min "16" :max "1000" :step "1"
                      :data-bind:prh "" :style num
                      :data-on:keydown "evt.key==='Enter' && @post('/props')"}]]
            [:div {:style "margin-top:1rem;text-align:right;"}
             [:button {:class "btn primary" :data-on:click "@post('/props'), $propspanel=false"} "Apply"]]
            ;; danger zone: delete the whole sheet (all branches/cells/shares).
            ;; Two-step — $delconfirm arms an explicit confirm before /delete-sheet.
            [:div {:style (str "margin-top:1.4rem;border-top:1px solid var(--line);padding-top:.9rem;")}
             [:div {:style "font:600 13px sans-serif;color:#c0392b;margin-bottom:.35rem;"} "Danger zone"]
             [:div {:data-show "!$delconfirm"}
              [:p {:style p} "Delete this sheet and everything in it — all branches, cells and shares. This can't be undone."]
              [:button {:class "btn" :style "border-color:#c0392b;color:#c0392b;"
                        :data-on:click "$delconfirm=true"} "Delete sheet…"]]
             [:div {:data-show "$delconfirm"}
              [:p {:style p} "Permanently delete " [:b (str "“" sname "”")]
               "? Every branch, cell and share is removed for good."]
              [:div {:style "display:flex;gap:.5rem;justify-content:flex-end;"}
               [:button {:class "btn" :data-on:click "$delconfirm=false"} "Cancel"]
               [:button {:class "btn" :style "background:#c0392b;border-color:#c0392b;color:#fff;"
                         :data-on:click "@post('/delete-sheet'), $delconfirm=false, $propspanel=false"}
                "Delete permanently"]]]]]]))))

(declare fmt-edited)

(defn- agentkey-html
  "The 🔑 account panel: mint / rotate / revoke the ACCOUNT agent key — the MCP
   credential that reaches every sheet you can, so pointing an agent at a new
   sheet needs no config change (a capability link is per-sheet and would).

   The secret only exists in the response that mints it (the DB keeps a hash),
   so it is shown once in $agentkey with a copy button and a warning; reopening
   the panel later can only say a key EXISTS, never what it is. `info` =
   {:created-at :last-used} or nil."
  [info]
  (let [p     "margin:.2rem 0 .7rem;font:13px sans-serif;color:var(--muted);"
        kbd   "font:12px monospace;background:var(--panel);border:1px solid var(--grid);border-radius:3px;padding:0 4px;"
        field (str "font:13px sans-serif;padding:.35rem .5rem;border:1px solid var(--line);"
                   "border-radius:var(--radius);background:var(--bg);color:var(--fg);")
        code (str "width:100%;box-sizing:border-box;font:12px monospace;padding:.5rem .6rem;"
                  "border:1px solid var(--accent);border-radius:var(--radius);"
                  "background:var(--accent-bg);color:var(--fg);word-break:break-all;")]
    (str (h/html
          [:div {:id "agentwrap" :data-show "$agentpanel"
                 :data-on:click "$agentpanel=false, $agentkey=''"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:6vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:34rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "🔑 Agent key (MCP)"]
             [:button {:class "btn" :data-on:click "$agentpanel=false, $agentkey=''" :title "close"} "✕"]]
            [:p {:style p} "One key that lets an AI agent reach " [:b "every sheet you can"]
             " over the Model Context Protocol — unlike a per-sheet share link, you don't "
             "reconfigure anything when you make a new sheet."]
            [:p {:style p} "It carries " [:b "your"] " access and nothing more: an agent can only "
             "touch sheets you own or were granted, and its writes still auto-fork onto their own "
             "branch for you to review in " [:span {:style kbd} "🌿"] "."]

            ;; the freshly minted secret — the only time it is ever readable
            [:div {:data-show "$agentkey != ''"}
             [:p {:style "margin:.2rem 0 .3rem;font:600 13px sans-serif;color:var(--fg);"}
              "Copy it now — it won't be shown again:"]
             [:div {:style code :data-text "$agentkey"}]
             [:div {:style "display:flex;gap:.4rem;justify-content:flex-end;margin:.4rem 0 .2rem;"}
              [:button {:class "btn"
                        :data-on:click "navigator.clipboard.writeText($agentkey)"} "copy key"]]
             [:p {:style p} "Add it to your MCP client config as the "
              [:span {:style kbd} "Authorization: Bearer"] " header, e.g."]
             [:pre {:style (str code "white-space:pre-wrap;border-color:var(--grid);"
                                "background:var(--panel);")}
              (str "\"saltrim\": {\n"
                   "  \"command\": \"npx\",\n"
                   "  \"args\": [\"-y\", \"mcp-remote@latest\",\n"
                   "           \"" (auth/base-url) "/mcp\",\n"
                   "           \"--transport\", \"http-only\",\n"
                   "           \"--header\", \"Authorization:${AUTH_TOKEN}\"],\n"
                   "  \"env\": { \"AUTH_TOKEN\": \"Bearer <the key above>\" }\n"
                   "}")]]

            ;; status + actions
            [:div {:data-show "$agentkey == ''"}
             (if info
               [:p {:style p} "A key is active"
                (when-let [c (fmt-edited (:created-at info))] (str " (created " c ")"))
                (if-let [u (fmt-edited (:last-used info))]
                  (str ", last used " u ".")
                  ", never used yet.")
                " The secret can't be shown again — rotate to get a new one, which "
                [:b "immediately stops the old key from working"] "."]
               [:p {:style p} "No key yet."])]

            [:div {:style "display:flex;gap:.5rem;justify-content:flex-end;margin-top:.9rem;"}
             (when info
               [:span {:data-show "$agentkey == ''"}
                [:span {:data-show "!$agentrevoke"}
                 [:button {:class "btn" :style "border-color:var(--danger);color:var(--danger);"
                           :data-on:click "$agentrevoke=true"} "Revoke"]]
                [:span {:data-show "$agentrevoke" :style "display:inline-flex;gap:.4rem;"}
                 [:button {:class "btn" :data-on:click "$agentrevoke=false"} "Cancel"]
                 [:button {:class "btn" :style "background:var(--danger);border-color:var(--danger);color:#fff;"
                           :data-on:click "$agentact='revoke', @post('/agentkey'), $agentrevoke=false"}
                  "Revoke for good"]]])
             [:button {:class "btn primary" :data-on:click "$agentact='mint', @post('/agentkey')"}
              (if info "Rotate key" "Create key")]]

            ;; --- erase this account ------------------------------------------
            ;; Lives in the 🔑 panel because that IS the account panel; nothing
            ;; else on the page is about the person rather than the sheet.
            ;; Two steps on purpose: the first asks the server what would go (so
            ;; the warning can NAME the sheets other people are using), the
            ;; second needs the word DELETE typed. Nothing here is recoverable —
            ;; it purges history too, so there is no as-of to fall back to.
            [:div {:style (str "margin-top:1.2rem;padding-top:.9rem;"
                               "border-top:1px solid var(--line);")}
             [:div {:data-show "!$acctplan"}
              [:button {:class "btn" :style "border-color:var(--danger);color:var(--danger);"
                        :data-on:click "$acctact='plan', @post('/delete-account')"}
               "Delete my account…"]]
             [:div {:data-show "$acctplan"}
              [:p {:style (str p "color:var(--danger);")}
               [:b "This erases your account."] " Your "
               [:span {:data-text "$acctsheets"}] " sheet(s) and everything in them "
               "are removed for good — including their history, so there is no "
               "time-travel back to them."]
              [:p {:data-show "$acctshared != ''" :style (str p "color:var(--danger);")}
               "Other people are working on: " [:b {:data-text "$acctshared"}]
               ". They lose access immediately."]
              [:p {:style p}
               "We keep one thing: the opaque account id your login provider gave "
               "us. It is what your name is attached to on cells in other people's "
               "sheets, and it identifies nobody once your name, email and avatar "
               "are gone."]
              [:div {:style "display:flex;gap:.5rem;align-items:center;justify-content:flex-end;"}
               ;; "" not true: hiccup renders a `true` attribute value as the
               ;; attribute's own NAME, and Datastar then sees a key in
               ;; `data-bind:acctword` AND a value, throws KeyAndValueProvided,
               ;; and aborts init for the whole page. Every other data-bind
               ;; here is "" for that reason.
               [:input {:type "text" :placeholder "type DELETE" :data-bind:acctword ""
                        :style (str field "width:9rem;")}]
               [:button {:class "btn" :data-on:click "$acctplan=false, $acctword=''"} "Cancel"]
               [:button {:class "btn"
                         :style "background:var(--danger);border-color:var(--danger);color:#fff;"
                         :data-on:click "$acctact='confirm', @post('/delete-account')"}
                "Erase everything"]]]]]]))))

(defn- import-html
  "Import-.xlsx modal, toggled by $importpanel. The file can't ride Datastar's
   JSON signals, so the form posts with `contentType:'form'` — Datastar sends the
   multipart FormData verbatim (and pre-empts the native submit) and /import
   answers over SSE, so the report lands in #importreport instead of navigating
   away. $imported swaps the form out for that report; re-opening resets it.
   Each workbook tab becomes a new sheet owned by the user."
  []
  (let [p "margin:.2rem 0 .7rem;font:13px sans-serif;color:var(--muted);"
        inp "font:13px monospace;padding:5px 6px;border:1px solid var(--line);border-radius:var(--radius);background:var(--panel);"]
    (str (h/html
          [:div {:id "importwrap" :data-show "$importpanel"
                 :data-on:click "$importpanel=false"
                 :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:30rem;width:100%;"
                              "max-height:88vh;overflow:auto;padding:1.1rem 1.3rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.3rem;"}
             [:h2 {:style "margin:0;font:600 18px sans-serif;flex:1;"} "Import Excel workbook"]
             [:button {:class "btn" :data-on:click "$importpanel=false" :title "close"} "✕"]]
            [:div {:data-show "!$imported"}
             [:p {:style p} "Each workbook tab becomes a " [:b "new sheet"] " of yours. Formulas are "
              [:b "translated"] " to Clojure where possible; anything untranslatable keeps its "
              "computed value with the original formula as a comment. You'll get a full report."]
             ;; data-on:submit on a <form> is preventDefault-ed by Datastar, so the
             ;; native navigation never happens. datastar-fetch is dispatched on
             ;; document for EVERY @post, hence the evt.detail.el===el filter; driving
             ;; $importing off the lifecycle (not the submit) means a failed
             ;; checkValidity — which returns before any fetch — can't wedge it true.
             [:form {:method "post" :action "/import" :enctype "multipart/form-data"
                     :data-on:submit "@post('/import', {contentType:'form'})"
                     :data-on:datastar-fetch
                     "evt.detail.el===el && ($importing = evt.detail.type==='started')"
                     :style "display:flex;flex-direction:column;gap:.6rem;"}
              [:input {:type "file" :name "file" :accept ".xlsx" :required true :style inp}]
              [:input {:type "text" :name "name" :placeholder "sheet name (optional — defaults to the file name)"
                       :maxlength "32" :pattern "[A-Za-z0-9-]*" :style inp}]
              [:div {:style "text-align:right;"}
               [:button {:type "submit" :class "btn primary" :data-attr:disabled "$importing"}
                [:span {:data-text "$importing ? 'importing…' : 'import'"} "import"]]]]]
            [:div {:id "importreport"}]]]))))

;; The report's tables live inside the import modal, so they inherit the app's
;; theme vars — only the table/code chrome needs styling, scoped to #importreport
;; so it can't leak into the grid.
(def ^:private report-css
  (str "#importreport table{border-collapse:collapse;width:100%;margin:.4rem 0 .8rem;}"
       "#importreport th,#importreport td{border:1px solid var(--grid);padding:.3rem .5rem;"
       "font:12px sans-serif;text-align:left;}"
       "#importreport th{background:var(--panel);}"
       "#importreport code{font-family:monospace;background:var(--panel);padding:0 .2rem;}"
       "#importreport h3{margin:.7rem 0 .2rem;font:600 13px sans-serif;}"
       "#importreport p{margin:.2rem 0 .5rem;font:13px sans-serif;}"))

(defn import-error-html
  "Inner #importreport fragment for a failed /import."
  [msg]
  (str (h/html
        [:div {:id "importreport"}
         [:style (h/raw report-css)]
         [:p {:style "color:#c0392b;font:600 13px sans-serif;margin:.2rem 0 .4rem;"} "Import failed"]
         [:p [:code (str msg)]]])))

(defn import-report-html
  "Inner #importreport fragment for a successful /import: per sheet a link +
   counts + the audit lists (untranslated fallbacks, verify-demotions, dropped
   masks). Shown in the import modal — $imported swaps out the upload form."
  [{:keys [sheets]}]
  (str (h/html
        [:div {:id "importreport"}
         [:style (h/raw report-css)]
         [:h3 {:style "margin-top:0;"}
          "Imported " (count sheets) (if (= 1 (count sheets)) " sheet" " sheets")]
         (for [{:keys [sname tab cells formulas fallbacks demoted masks-dropped]} sheets]
           ;; xlsx's :formulas counts only the SUCCESSFULLY translated ones (the
           ;; fallback path never sets :original), so the whole-sheet total is
           ;; :formulas + fallbacks, and only the demotions come back out of the
           ;; live count. Subtracting fallbacks here too double-counted them —
           ;; it under-reported "translated live", and went negative once the
           ;; demotions overlapped.
           [:div
            [:h3 [:a {:href (str "/?s=" sname)} sname]
             [:span {:style "font:12px sans-serif;color:var(--muted);font-weight:400;"}
              "  (tab “" tab "”)"]]
            [:p cells " cells · " (+ formulas (count fallbacks)) " formulas ("
             (- formulas (count demoted)) " translated live, "
             (count fallbacks) " untranslated, " (count demoted) " demoted to values)"]
            (when (seq fallbacks)
              (list [:h3 "Untranslated — imported as their computed value"]
                    [:table
                     [:tr [:th "cell"] [:th "Excel formula"] [:th "why"]]
                     (for [{:keys [addr formula reason]} fallbacks]
                       [:tr [:td addr] [:td [:code "=" formula]] [:td reason]])]))
            (when (seq demoted)
              (list [:h3 "Demoted — translated but disagreed with Excel's value"]
                    [:table
                     [:tr [:th "cell"] [:th "Excel formula"] [:th "kept value"]]
                     (for [{:keys [addr formula cached]} demoted]
                       [:tr [:td addr] [:td [:code "=" formula]] [:td (str cached)]])]))
            (when (seq masks-dropped)
              [:p "Number formats not carried over: "
               (interpose ", " (map (fn [m] [:code m]) masks-dropped))])])
         [:p {:style "color:var(--muted);"}
          "Untranslated and demoted cells keep the original Excel formula as the cell's "
          [:code "comment"] " (the corner flag on the cell; hover to read it, or open it in the style row)."]
         [:div {:style "text-align:right;"}
          [:button {:class "btn" :data-on:click "$imported=false"
                    :style "margin:.2rem .4rem 0 0;"} "import another"]
          (for [{:keys [sname]} sheets]
            [:a {:class "btn primary" :href (str "/?s=" sname)
                 :style "margin:.2rem 0 0 .4rem;text-decoration:none;"} "open " sname])]])))

(defn- sheet-picker
  "Dropdown for switching sheets, grouped into 'your sheets' (👤) and 'shared
   with you' (✎ edit / 👁 view). Selecting one navigates to it. A foreign sheet
   reached by a public link (in neither group) shows as a leading ↗ option."
  [uid storage-id sname]
  (let [names       (store/list-names uid)
        [owner _]   (store/split-id storage-id)
        own?        (= owner uid)
        mine        (if own? (distinct (cons sname names)) names)
        shared      (db/sheets-shared-with uid)
        cur-shared? (some #(= storage-id (:sheet-id %)) shared)]
    [:select {:id "sheetpicker" :class "tool" :title "your sheets"
              :data-on:change "el.value && (location.href='/?'+el.value)"
              :style "max-width:11rem;"}
     (when (and (not own?) (not cur-shared?))
       [:option {:value "" :selected true} (str "↗ " sname)])
     [:optgroup {:label "your sheets"}
      (for [n mine]
        [:option {:value (str "s=" n) :selected (and own? (= n sname))} (str "👤 " n)])]
     (when (seq shared)
       [:optgroup {:label "shared with you"}
        (for [{:keys [sheet-id name level]} shared
              :let [[o nm] (store/split-id sheet-id)
                    icon   (if (= level :read-write) "✎" "👁")]]
          [:option {:value (str "u=" o "&s=" nm) :selected (= sheet-id storage-id)}
           (str icon " " (if (str/blank? name) nm name))])])]))

(defn- branch-bar
  "Branch picker + owner-only fork/delete (a 🌿 dropdown + ⑂ modal). A branch is
   a parallel copy of the sheet you edit independently. Switching navigates (full
   reload, like the sheet picker), preserving the sheet URL. Fork/delete post to
   /branch; on success the server sets $goto and the page navigates."
  [uid storage-id sname branch link-token owner?]
  (let [names (db/branch-names storage-id)
        ;; the sheet's own URL (owners reach theirs by ?s=; a link visitor keeps
        ;; ?t=; a shared viewer keeps ?u=&s=). The picker appends &b=.
        base  (cond link-token (str "/?t=" link-token)
                    owner?      (str "/?s=" sname)
                    :else       (str "/?u=" (first (store/split-id storage-id)) "&s=" sname))
        overlay (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")
        modal   (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                     "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:24rem;width:100%;padding:1rem 1.1rem;"
                     "font:13px sans-serif;color:var(--fg);")
        field   (str "font:13px sans-serif;padding:6px 8px;border:1px solid var(--line);"
                     "border-radius:var(--radius);box-sizing:border-box;")]
    (list
     [:select {:id "branchpicker" :class "tool" :title "branch — a parallel version of this sheet"
               ;; navigate to the picked branch (main → no &b=, keeps URLs clean)
               :data-on:change (str "el.value && (location.href='" base "'"
                                    " + (el.value==='" db/MAIN "' ? '' : '&b='+el.value))")
               :style "max-width:9rem;"}
      (for [n names]
        [:option {:value n :selected (= n branch)} (str "🌿 " n)])]
     (when owner?
       (list
        [:button {:class "btn" :data-on:click "$branchpanel=true"
                  :title "branches: fork / delete"} "⑂"]
        [:div {:data-show "$branchpanel" :data-on:click "$branchpanel=false" :style overlay}
         [:div {:data-on:click "evt.stopPropagation()" :style modal}
          [:div {:style "display:flex;align-items:center;margin-bottom:.5rem;"}
           [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Branches"]
           [:button {:class "btn" :data-on:click "$branchpanel=false" :title "close"} "✕"]]
          [:p {:style "color:var(--muted);margin:.2rem 0 .7rem;"}
           "On branch " [:strong (str "🌿 " branch)] ". A fork copies it into a new "
           "parallel branch you can edit without touching this one."]
          [:label {:style "display:block;font-size:12px;color:var(--muted);margin-bottom:.2rem;"}
           "New branch name"]
          [:div {:style "display:flex;gap:.4rem;"}
           [:input (merge no-autofill
                    {:data-bind:bname "" :placeholder "feature-x"
                     :data-on:keydown "evt.key==='Enter' && ($branchact='fork', @post('/branch'))"
                     :style (str field "flex:1;")})]
           [:button {:class "btn primary" :data-on:click "$branchact='fork', @post('/branch')"}
            (str "Fork from " branch)]]
          ;; ── merge another branch INTO this one (3-way) ──────────────────
          (when-let [others (seq (remove #(= % branch) names))]
            [:div {:style "border-top:1px solid var(--grid);margin-top:.8rem;padding-top:.7rem;"}
             [:label {:style "display:block;font-size:12px;color:var(--muted);margin-bottom:.2rem;"}
              (str "Merge another branch into 🌿 " branch)]
             [:div {:style "display:flex;gap:.4rem;"}
              [:select {:class "tool" :data-bind:mergefrom "" :style "flex:1;"}
               [:option {:value ""} "choose a branch…"]
               (for [n others] [:option {:value n} (str "🌿 " n)])]
              [:button {:class "btn"
                        :data-on:click "$mergetake='', $branchact='preview', @post('/merge')"}
               "Preview"]]
             ;; preview result + conflict picker + Apply land here (patched by id)
             [:div {:id "mergeresult"}]])
          (when (not= branch db/MAIN)
            [:div {:style "border-top:1px solid var(--grid);margin-top:.8rem;padding-top:.7rem;"}
             [:button {:class "btn"
                       :data-on:click (str "confirm('Delete branch \\u201c" branch "\\u201d? "
                                           "This removes its cells and cannot be undone.') && "
                                           "($branchact='delete', @post('/branch'))")
                       :style "color:var(--danger);border-color:var(--danger);"}
              (str "Delete “" branch "”")]])]])))))

;; --- as-of / history viewing (PR C) ---------------------------------------

(defn- fmt-ts
  "An #inst (java.util.Date) -> \"yyyy-MM-dd HH:mm:ss\" local, or nil."
  [inst]
  (when inst
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             (java.time.LocalDateTime/ofInstant (.toInstant ^java.util.Date inst)
                                                (java.time.ZoneId/systemDefault)))))

(defn- sheet-href
  "The sheet's own branch-aware URL (owners reach theirs by ?s=; a link visitor
   keeps ?t=; a shared viewer keeps ?u=&s=; non-main adds &b=). History links
   append &at=<tx>."
  [storage-id sname branch link-token owner?]
  (str (cond link-token (str "/?t=" link-token)
             owner?      (str "/?s=" sname)
             :else       (str "/?u=" (first (store/split-id storage-id)) "&s=" sname))
       (when (not= branch db/MAIN) (str "&b=" branch))))

(defn- revision-select
  "A <select> of revisions (newest first) that navigates on change; `cur` = the
   tx currently viewed (nil = live, selects the 'current' option)."
  [href revisions cur]
  [:select {:class "tool" :title "view an earlier revision (read-only)"
            :data-on:change "el.value && (location.href = el.value)"
            :style "max-width:14rem;"}
   [:option {:value href :selected (nil? cur)} "● current (live)"]
   (for [{:keys [tx inst]} revisions]
     [:option {:value (str href "&at=" tx) :selected (= tx cur)}
      (str "🕘 " (fmt-ts inst))])])

(defn- asof-banner
  "Read-only banner shown while viewing a past revision: what/when + a revision
   picker + Back-to-live."
  [storage-id sname branch at revisions link-token owner?]
  (let [href (sheet-href storage-id sname branch link-token owner?)
        cur  (parse-long (str at))
        when-s (some->> revisions (filter #(= (:tx %) cur)) first :inst fmt-ts)]
    [:div {:style (str "display:flex;align-items:center;gap:.5rem;flex:1;"
                       "background:#fff8e1;border:1px solid #e6c200;border-radius:var(--radius);"
                       "padding:4px 8px;font:12px sans-serif;color:#7a5b00;")}
     [:span "🕘 " [:strong (str "🌿 " branch)] " as of "
      [:strong (or when-s (str "tx " cur))] " — read-only."]
     (revision-select href revisions cur)
     [:a {:class "btn" :href href :style "text-decoration:none;"} "Back to live"]]))

(defn- history-modal
  "The 🕘 history modal (live page): a list of revisions, each opening a read-only
   as-of view. Toggled by $histpanel."
  [storage-id sname branch revisions link-token owner?]
  (let [href (sheet-href storage-id sname branch link-token owner?)]
    [:div {:data-show "$histpanel" :data-on:click "$histpanel=false"
           :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                       "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")}
     [:div {:data-on:click "evt.stopPropagation()"
            :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                        "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:26rem;width:100%;"
                        "padding:1rem 1.1rem;font:13px sans-serif;color:var(--fg);")}
      [:div {:style "display:flex;align-items:center;margin-bottom:.5rem;"}
       [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} (str "History — 🌿 " branch)]
       [:button {:class "btn" :data-on:click "$histpanel=false" :title "close"} "✕"]]
      [:p {:style "color:var(--muted);margin:.2rem 0 .6rem;"}
       "Open this branch as it was at an earlier point (read-only)."]
      (if (empty? revisions)
        [:p {:style "color:var(--muted);"} "No history yet — make some edits first."]
        [:div {:style "max-height:40vh;overflow:auto;"}
         (for [{:keys [tx inst]} revisions]
           [:a {:href (str href "&at=" tx)
                :style (str "display:block;padding:.4rem .2rem;border-top:1px solid var(--grid);"
                            "text-decoration:none;color:var(--fg);font:12px monospace;")}
            (str "🕘 " (fmt-ts inst))])])]]))

(defn- branch-gone-html
  "Shown when the owner deletes the branch you are on ($branchgone = its name).

   Deliberately a BLOCKING modal with one button, not a redirect. Dropping
   someone onto main automatically is how they keep typing and edit main by
   accident — the branch they believed they were on is gone and nothing said so.
   Here the move is their own click. No click-outside dismiss, no ✕: while it is
   up the page still shows the stale branch, and every write already 403s (their
   $branch names a branch that no longer exists), so nothing can leak to main."
  [main-href]
  [:div {:data-show "$branchgone != ''"
         :style (str "position:fixed;inset:0;z-index:70;background:rgba(0,0,0,.45);"
                     "display:flex;align-items:center;justify-content:center;padding:1rem;")}
   [:div {:style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                      "box-shadow:0 8px 32px rgba(0,0,0,.3);max-width:26rem;width:100%;"
                      "padding:1.1rem 1.3rem;font:13px sans-serif;color:var(--fg);")}
    [:h2 {:style "margin:0 0 .5rem;font:600 15px sans-serif;"} "This branch was deleted"]
    [:p {:style "color:var(--muted);margin:.2rem 0 .5rem;"}
     "The owner deleted "
     [:strong {:data-text "'🌿 ' + $branchgone"}]
     ". What you see here is no longer saved anywhere, and edits are refused."]
    [:p {:style "color:var(--muted);margin:.2rem 0 .9rem;"}
     "Continue on " [:strong "🌿 main"] " — a different branch, so check where you "
     "are before typing."]
    [:div {:style "display:flex;justify-content:flex-end;"}
     [:a {:class "btn primary" :href main-href :style "text-decoration:none;"}
      "Go to 🌿 main"]]]])

(defn- graph-modal-html
  "The 🕸 dependency-graph modal shell, toggled by $graphpanel. Its #graphview
   inner is server-rendered by /graph when the modal opens (so it's always
   fresh)."
  []
  [:div {:data-show "$graphpanel" :data-on:click "$graphpanel=false"
         :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:8vh 1rem;")}
   [:div {:data-on:click "evt.stopPropagation()"
          :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                      "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:52rem;width:100%;"
                      "padding:1rem 1.1rem;font:13px sans-serif;color:var(--fg);")}
    [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
     [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Dependency graph"]
     [:button {:class "btn" :data-on:click "$graphpanel=false" :title "close"} "✕"]]
    [:p {:style "color:var(--muted);margin:.2rem 0 .5rem;"}
     "An arrow points from a cell to the cells whose formulas read it. Click a node to select it. "
     "Name a cell (style row → " [:span {:style "font-family:monospace;"} "label"] ") for a friendlier node."]
    [:div {:id "graphview" :style "overflow:auto;max-height:64vh;"}]]])

(defn violations-html
  "The ⚠ panel's inner list: every failing assertion, sheet-wide.

   This exists because the per-cell wedge cannot answer \"is anything wrong\" —
   only the rendered window is in the DOM (a few hundred cells out of a possible
   million), so a violation is usually somewhere you are not looking. It is also
   what survives a reload, where the toast that first announced it is long gone.

   Each row carries `data-addr`; app.cljs delegates a click on it to the same
   jump that the address box performs, so a row takes you to the cell."
  [sh]
  (let [viols (sheet/assert-violations sh)]
    (str (h/html
          (if (empty? viols)
            [:p {:style "color:var(--muted);margin:.3rem 0;"} "Every assertion holds."]
            [:ul {:style "list-style:none;margin:0;padding:0;"}
             (for [[a msg] viols]
               [:li {:class "violrow" :data-addr a :title "go to this cell"
                     :style (str "padding:.4rem .5rem;border-bottom:1px solid var(--line);"
                                 "cursor:pointer;display:flex;gap:.5rem;align-items:baseline;")}
                [:span {:style "font:600 12px monospace;color:var(--accent);min-width:3.5rem;"} a]
                [:span msg]])])))))

(defn- violations-modal-html
  "The ⚠ modal shell, toggled by $violpanel; its #violview inner is re-rendered
   by /violations on open (and pushed on change), so it is never stale."
  [sh]
  [:div {:data-show "$violpanel" :data-on:click "$violpanel=false"
         :style (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                     "display:flex;align-items:flex-start;justify-content:center;padding:8vh 1rem;")}
   [:div {:data-on:click "evt.stopPropagation()"
          :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                      "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:40rem;width:100%;"
                      "padding:1rem 1.1rem;font:13px sans-serif;color:var(--fg);")}
    [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
     [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"} "Assertions"]
     [:button {:class "btn" :data-on:click "$violpanel=false" :title "close"} "✕"]]
    [:p {:style "color:var(--muted);margin:.2rem 0 .5rem;"}
     "Cells whose claim about themselves does not currently hold. Click one to go to it. "
     "Set a claim with the " [:span {:style "font-family:monospace;"} "⊨"] " button on the formula row."]
    [:div {:id "violview" :style "overflow:auto;max-height:64vh;"} (h/raw (violations-html sh))]]])

(defn page [sh storage-id sname branch at uid link-token]
  ;; one session id seeds BOTH $sid (sent on /stream, registers the session) and
  ;; #ctl's data-sid (read by the unload beacon) — they must be the same value.
  (let [sid    (str (random-uuid))
        owner? (= uid (first (store/split-id storage-id)))
        asof?  (boolean at)                       ; read-only historical view?
        revisions (db/branch-revisions storage-id branch)]
   (str
    "<!doctype html>"
   (h/html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title (str sname " — SaltRim")]
      [:link {:rel "icon" :type "image/x-icon" :href "/favicon.ico"}]
      [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
      [:link {:rel "apple-touch-icon" :href "/apple-touch-icon.png"}]
      [:meta {:property "og:type" :content "website"}]
      [:meta {:property "og:site_name" :content "SaltRim"}]
      [:meta {:property "og:title" :content (str sname " — SaltRim")}]
      [:meta {:property "og:description"
              :content "A simple-but-powerful Clojure reactive spreadsheet. Cells are Clojure expressions, collaborative edits and git-like branching are build in."}]
      [:meta {:property "og:url" :content (str (auth/base-url) "/?s=" sname
                                                (when (not= branch db/MAIN) (str "&b=" branch)))}]
      [:meta {:property "og:image" :content (str (auth/base-url) "/SaltRim-opengraph.png")}]
      [:meta {:name "twitter:card" :content "summary_large_image"}]
      ;; Cells are display <div class="cell"> (not inputs); the floating editor
      ;; is the single #editor input. Both are absolutely positioned (cells by
      ;; their inline left/top, #editor by app.js) — without this the left/top
      ;; are ignored and everything stacks in flow at the top-left.
      [:style (h/raw
               (str
                ;; design tokens — centralize colors/geometry so a merge can't
                ;; silently drift them and so inline styles can share them.
                ;; palette tuned to the SaltRim logo: blue grid/parens, lime
                ;; slice, slate wordmark — softer neutrals than the old grey/blue.
                ":root{--bg:#fefefe;--panel:#f4f6f8;--line:#c7ccd1;--grid:#e2e6ea;"
                "--fg:#3a4149;--muted:#7a828b;--accent:#2f8fd8;--accent2:#9ec9ee;"
                ;; …and a pale wash of each alert colour, mixed the same way
                ;; --accent-bg is for --accent (a toast tints its whole card).
                "--accent-bg:#eaf4fc;--lime:#7cc62e;--lime-bg:#f2f9e8;"
                "--danger:#c0392b;--danger-bg:#fdeeec;"
                ;; gold = an assertion violation: a statement about the DATA,
                ;; not about an operation that failed, so it is deliberately
                ;; neither the red of an error nor the green of a confirmation.
                "--gold:#d19b12;--gold-bg:#fdf6e3;--radius:4px;}"
                ;; toolbar: two rows. row 1 = picker/new/share/identity,
                ;; row 2 = cell-ref + formula bar. .tool/.btn unify the inputs
                ;; and buttons that used to repeat the same inline style string.
                ".toolrow{display:flex;align-items:center;gap:.5rem;margin-bottom:.4rem;}"
                ".tool{font:13px sans-serif;padding:5px 6px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);}"
                ".tool.mono{font-family:monospace;}"
                ".btn{font:12px sans-serif;padding:5px 8px;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);cursor:pointer;}"
                ".btn:hover{border-color:var(--accent);}"
                ;; toggled-on section chip (e.g. 🎨 format) — tinted, not loud
                ".btn.active{background:var(--accent-bg);border-color:var(--accent);color:var(--accent);}"
                ;; primary action button (Save / Apply) — the brand accent
                ".btn.primary{background:var(--accent);color:#fff;border-color:var(--accent);}"
                ".btn.primary:hover{filter:brightness(1.06);}"
                ;; a button and the option that modifies it, welded into one
                ;; control (⧉ + strict): one border around both, a hairline
                ;; between, so the option reads as part of the action.
                ".grp{display:inline-flex;align-items:stretch;border:1px solid var(--line);"
                "border-radius:var(--radius);background:var(--panel);overflow:hidden;}"
                ".grp .btn{border:0;border-radius:0;background:transparent;}"
                ".grp .btn:hover{background:var(--accent-bg);color:var(--accent);}"
                ".grp .opt{display:flex;align-items:center;gap:.25rem;padding:0 .5rem;"
                "border-left:1px solid var(--line);font:11px sans-serif;color:var(--muted);"
                "cursor:pointer;user-select:none;}"
                ".grp .opt:hover{background:var(--accent-bg);}"
                ".grp .opt input{margin:0;}"
                ;; definition name badges (collapsed library cards)
                ".badge{display:inline-block;font:600 11px/1.5 monospace;color:var(--accent);"
                "background:var(--accent-bg);border:1px solid var(--accent2);"
                "border-radius:10px;padding:0 .5rem;}"
                ".spacer{flex:1;}"
                ;; Toast list. The server APPENDS a card per message (see
                ;; web.sse/toast!), so they stack instead of overwriting each
                ;; other. `column-reverse` puts the newest at the top: the list
                ;; is pinned by its top edge, so it grows downward and older
                ;; cards slide away from the corner rather than the new one
                ;; appearing somewhere further down the screen. Clipped at the
                ;; viewport, because an error card stays until it is clicked and
                ;; a bad afternoon should not paint over the whole grid.
                ;; above the modals (50, and 70 for the branch-gone block): most
                ;; messages are RAISED by a modal action — merge, properties,
                ;; share — and at the old z-index of 20 the confirmation you had
                ;; just asked for came up dimmed behind that modal's backdrop.
                "#toasts{position:fixed;top:1rem;right:1rem;z-index:80;margin:0;padding:0;"
                "list-style:none;display:flex;flex-direction:column-reverse;gap:.4rem;"
                "align-items:flex-end;max-height:calc(100vh - 2rem);overflow:hidden;"
                "pointer-events:none;}"
                ;; Cards are LIGHT with a coloured left rule, not a block of
                ;; solid colour: messages carry emoji (🌿 for a branch, 🕘 for a
                ;; revision) and a green-on-green 🌿 was simply invisible. Dark
                ;; text on the page colour keeps every glyph readable whatever
                ;; the message says. What DOES carry the colour: a pale wash of
                ;; it behind the text, the whole 1px border, and a 6px rule down
                ;; the left — enough that err and info are told apart at a
                ;; glance, all of it still far too light to swallow a glyph.
                ;;
                ;; The width is what stops a message like "…e.g. (+ (or $B5 0)
                ;; 1)" wrapping its last "1)" onto a line of its own: at 26rem
                ;; that one needed two lines, at 30rem it fits on one.
                ;; `text-wrap:pretty` is insurance for the messages still long
                ;; enough to wrap — it asks the engine not to leave a runt last
                ;; line. (Unsupported engines ignore it; it never breaks.)
                ".toast{pointer-events:auto;max-width:30rem;background:var(--bg);"
                "color:var(--fg);border:1px solid var(--line);border-left:6px solid var(--muted);"
                "border-radius:6px;padding:.55rem .85rem;font:13px/1.4 sans-serif;cursor:pointer;"
                "text-wrap:pretty;"
                "box-shadow:0 2px 10px rgba(0,0,0,.18);animation:toastin .18s ease-out;}"
                ".toast.err{background:var(--danger-bg);border-color:var(--danger);}"
                ;; an assertion violation: gold, and like an error it does NOT
                ;; auto-dismiss — the number is still wrong when the animation
                ;; would have ended. It carries a cell, so it also gets a
                ;; pointer affordance for "click to go there".
                ".toast.warn{background:var(--gold-bg);border-color:var(--gold);}"
                ".btn.viol{background:var(--gold-bg);border-color:var(--gold);color:var(--fg);}"
                ".violrow:hover{background:var(--accent-bg);}"
                ;; ƒ-panel function reference: a chip per function, its docs in a
                ;; CSS-only tooltip, and a copy button that appears on hover.
                ".fnref{position:relative;display:inline-flex;align-items:center;gap:2px;"
                "font:12px monospace;background:var(--panel);border:1px solid var(--grid);"
                "border-radius:3px;padding:0 3px;margin:0 4px 5px 0;cursor:default;}"
                ".fnref:hover{border-color:var(--accent);color:var(--accent);}"
                ;; ◆ ours / ◇ borrowed. Same shape filled and hollow rather than
                ;; two different glyphs: at 284 chips the eye sorts them without
                ;; reading either, and it survives a monochrome theme.
                ".fnmark{font-size:8px;line-height:1;color:var(--muted);"
                "margin-right:1px;cursor:help;}"
                ".fnref.own .fnmark{color:var(--accent);}"
                ".fnref::after{content:attr(data-tip);display:none;position:absolute;"
                "left:0;top:calc(100% + 5px);z-index:60;width:21rem;max-width:60vw;"
                "white-space:pre-wrap;font:12px/1.45 sans-serif;color:var(--fg);"
                "background:var(--bg);border:1px solid var(--line);border-radius:6px;"
                "box-shadow:0 6px 20px rgba(0,0,0,.28);padding:.45rem .55rem;"
                "text-align:left;pointer-events:none;}"
                ".fnref:hover::after{display:block;}"
                ".fncopy{border:0;background:none;color:var(--muted);cursor:pointer;"
                "font:12px sans-serif;padding:0 1px;opacity:0;transition:opacity .1s;}"
                ".fnref:hover .fncopy{opacity:1;}"
                ".fncopy:hover{color:var(--accent);}"
                ".toast.warn[data-addr]{text-decoration-color:var(--gold);}"
                ;; ONE animation on an info card, not an entrance plus a
                ;; lifetime: `animationend` is what removes the node, and a
                ;; second animation would fire it early. So the fade-in is the
                ;; first 4% of the same keyframes. (The rule overrides the
                ;; shorthand above, so an err card keeps the bare entrance and
                ;; never auto-dismisses.)
                ".toast.info{background:var(--lime-bg);border-color:var(--lime);"
                "animation:toastlife 5s ease-out forwards;}"
                "@keyframes toastin{from{opacity:0;transform:translateX(12px);}}"
                "@keyframes toastlife{0%{opacity:0;transform:translateX(12px);}"
                "4%{opacity:1;transform:none;}88%{opacity:1;transform:none;}"
                "100%{opacity:0;transform:translateX(12px);}}"
                ;; reduced motion still needs the animation to END — that event is
                ;; the dismissal — so it holds still for the same 5s and then
                ;; cuts out, with no movement and no fade.
                "@media(prefers-reduced-motion:reduce){"
                ".toast{animation:none;}"
                ".toast.info{animation:toastcut 5s steps(1,end) forwards;}}"
                "@keyframes toastcut{from{opacity:1;}to{opacity:0;}}"
                ;; resize grips: a thin hit-zone on a header's trailing edge that
                ;; /app.js drags. The #rzguide is the single moving guide line.
                ".colgrip{position:absolute;top:0;right:-3px;width:6px;height:100%;"
                "cursor:col-resize;z-index:5;}"
                ".rowgrip{position:absolute;left:0;bottom:-3px;height:6px;width:100%;"
                "cursor:row-resize;z-index:5;}"
                "#rzguide{position:absolute;display:none;background:var(--accent);"
                "z-index:7;pointer-events:none;}"
                ;; default cell/editor box = this SHEET's default axis sizes (a
                ;; sized column/row overrides inline per cell). Server-rendered so
                ;; changing the sheet default reflows the grid on reload.
                ;; the grid, as its own layer under the cells (see gridlines-html).
                ;; A 3px band whose outer pixels are the grid colour and whose
                ;; middle is transparent — pixel-for-pixel what two cell borders
                ;; either side of a 1px gap used to draw.
                ;; The grid sits BETWEEN two cell layers. A cell defaults below
                ;; it, so a fill tints the cell while the table's ruling still
                ;; reads across it; `.over` lifts it above, so neighbours sharing
                ;; a fill become one solid region. Only siblings can be ordered
                ;; this way — which is why the lines live in the cell layer.
                ".gl{position:absolute;box-sizing:border-box;z-index:1;}"
                ".gv{top:0;width:3px;border-left:1px solid var(--grid);"
                "border-right:1px solid var(--grid);}"
                ".gh{left:0;height:3px;border-top:1px solid var(--grid);"
                "border-bottom:1px solid var(--grid);}"
                (let [dw (sheet/default-col-w sh) dh (sheet/default-row-h sh)
                      ew (dec dw) eh (dec dh)]
                  (format (str
                               ;; A cell fills its WHOLE slot and is TRANSPARENT, so
                               ;; the lines beneath show through an unstyled one and
                               ;; a `bg` paints edge to edge — two coloured
                               ;; neighbours meet with no seam. The padding is
                               ;; 1px more than it looks: it absorbs the border the
                               ;; cell no longer draws, so text lands on exactly the
                               ;; pixel it always did.
                               ".cell{position:absolute;width:%dpx;height:%dpx;z-index:0;"
                               "box-sizing:border-box;"
                               "padding:3px 5px 4px 5px;font:13px monospace;overflow:hidden;"
                               "white-space:nowrap;background:transparent;}"
                               ;; a merge covers boundaries that are still drawn
                               ;; beneath it, so the anchor is opaque and keeps a
                               ;; box of its own around the whole block
                               ".cell.over{z-index:2;}"
                               ;; a merge always covers the boundaries inside its
                               ;; own block, so it paints above them regardless
                               ".cell.merged{background:var(--bg);z-index:2;"
                               "border:1px solid var(--grid);padding:2px 4px;}"
                               ;; commented cell: a small corner flag (the comment
                               ;; text itself is the cell's hover title).
                               ".cell.noted::after{content:'';position:absolute;top:0;right:0;"
                               "border-top:5px solid var(--accent);border-left:5px solid transparent;}"
                               ;; cell whose assertion currently fails: the same
                               ;; flag idea in the OPPOSITE corner, so a cell can
                               ;; carry a comment and a violation at once.
                               ".cell.badassert::before{content:'';position:absolute;bottom:0;left:0;"
                               "border-bottom:5px solid var(--danger);border-right:5px solid transparent;}"
                               "#editor{position:absolute;width:%dpx;height:%dpx;"
                               "box-sizing:border-box;border:1px solid var(--accent);"
                               "padding:2px 4px;font:13px monospace;outline:none;z-index:6;"
                               "user-select:text;-webkit-user-select:text;}")
                          ;; the cell is a whole slot; the editor keeps the old
                          ;; inset box, so its accent border reads as a frame
                          ;; INSIDE the cell rather than over the grid line
                          dw dh ew eh))
                ;; selection / editing OVERLAY (#self), server-rendered. Literal %
                ;; in the gradients -> kept OUT of the format call above.
                ;; calm "you are here" selection box:
                ".selfcell{position:absolute;box-sizing:border-box;pointer-events:none;"
                "border:2px solid var(--accent2);}"
                ;; actively editing: animated 'marching ants' border (four gradient
                ;; edges whose position scrolls). pointer-events stays none so the
                ;; cell beneath is still typable.
                ".selfcell.editing{border-color:transparent;"
                "background-image:"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(90deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%),"
                "linear-gradient(0deg,#1a73e8 50%,transparent 50%);"
                "background-repeat:repeat-x,repeat-x,repeat-y,repeat-y;"
                "background-size:8px 2px,8px 2px,2px 8px,2px 8px;"
                "background-position:0 0,0 100%,0 0,100% 0;"
                "animation:cc-ants .6s infinite linear;}"
                "@keyframes cc-ants{to{background-position:8px 0,-8px 100%,0 -8px,100% 8px;}}"
                "@media(prefers-reduced-motion:reduce){.selfcell.editing{animation:none;}}"
                ;; collaborator cursor overlays (#peers):
                ".peer{position:absolute;box-sizing:border-box;border:2px solid #888;border-radius:2px;}"
                ".peer.editing{cursor:not-allowed;}"
                ".peer .peertag{position:absolute;top:-15px;left:-2px;"
                "font:10px/14px sans-serif;color:#fff;padding:0 4px;"
                "border-radius:3px 3px 3px 0;white-space:nowrap;}"))]
      ;; Datastar 1.0.2, served by US from `resources/public/datastar.js` (the
      ;; /datastar.js route in `web`). It used to load from
      ;; `cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js`
      ;; with this local path sitting beside it as a comment — but the CDN owns
      ;; the whole app when it is the source: a jsdelivr outage is a blank sheet,
      ;; and whatever it serves at that URL runs with full access to every cell.
      ;; Self-hosting also lets `script-src` in `web/security-headers` drop the
      ;; third-party origin, and it is what makes the app work air-gapped. Bump
      ;; the vendored file to change version.
      [:script {:type "module" :src "/datastar.js"}]
      [:script {:src "/app.js"}]]
     [:body {:data-signals:cell "''"
             :data-signals:v "''"
             ;; ($err / $info are gone: a message is an appended card in #toasts,
             ;; not a signal in a slot — see web.sse. Handlers still write them
             ;; through signals!, which turns them into elements.)
             :data-signals:sel "''"
             :data-signals:edit "false"
             ;; floating in-cell editor visibility (distinct from $edit: only the
             ;; cell dblclick/Enter path shows it, after start-edit! positions it)
             :data-signals:celledit "false"
             :data-signals:r0 "0"
             :data-signals:c0 "0"
             ;; how many columns/rows THIS client's viewport needs covered
             ;; (app.cljs measures it — only the browser knows). 0 = not measured
             ;; yet: the server then falls back to its own px-budget guess.
             :data-signals:wc "0"
             :data-signals:wr "0"
             :data-signals:sheet (format "'%s'" storage-id)
             ;; the working branch (rides in every POST so the server routes to
             ;; the right (sheet,branch) room); seeded from the resolved &b=.
             :data-signals:branch (format "'%s'" branch)
             ;; as-of (PR C): the tx being viewed, or "" for live. When set, every
             ;; POST carries it and the server forces read-only access.
             :data-signals:at (format "'%s'" (or at ""))
             :data-signals:histpanel "false"     ; 🕘 history modal open? (live page)
             :data-signals:bname "''"            ; new-branch name (fork modal)
             :data-signals:branchact "''"        ; fork | delete | merge-preview/apply
             :data-signals:branchpanel "false"   ; 🌿 modal open?
             ;; set (to its name) when the owner deletes the branch you are on —
             ;; raises a blocking modal instead of redirecting you onto main
             :data-signals:branchgone "''"
             ;; merge (PR B): source branch + the space-separated set of conflict
             ;; keys ("addr|prop") the owner chose to take from source.
             :data-signals:mergefrom "''"
             :data-signals:mergetake "''"
             ;; server sets $goto on a fork/delete to navigate (full reload) to
             ;; the resulting branch — the #goto effect element below watches it.
             :data-signals:goto "''"
             :data-signals:sid (format "'%s'" sid)
             :data-signals:link (format "'%s'" (or link-token ""))
             :data-signals:sharepanel "false"
             :data-signals:shareact "''"
             :data-signals:plevel "''"
             :data-signals:rotateconfirm "false"  ; new-link agreement modal open?
             :data-signals:gtarget "''"
             :data-signals:glevel "'read-write'"
             :data-signals:grantee "''"
             :data-signals:styleprop "'bg'"
             :data-signals:stylesrc "''"
             ;; which side(s) a `border` write lands on: the comma-joined prop list
             ;; of the picked group (see `border-sides`); defaults to all four.
             :data-signals:borderside (format "'%s'"
                                              (str/join "," (map name (:all border-sides))))
             ;; collapsible toolbar sections (formula bar stays; others toggle)
             :data-signals:fmtbar "false"
             ;; ⊨ assertion lever: the row's visibility + the selected cell's claim
             :data-signals:assertbar "false"
             :data-signals:assertsrc "''"
             ;; how many cells currently FAIL their assertion, sheet-wide. Seeded
             ;; server-side so a reload still tells you, and re-pushed to the whole
             ;; room on every change — a peer's edit can break your assertion.
             :data-signals:nviol (str (count (sheet/assert-violations sh)))
             :data-signals:violpanel "false"
             ;; current multi-selection as space-separated "TL:BR" ranges, kept
             ;; LIVE by app.cljs so selection-wide actions (clear / style / …) use it
             :data-signals:selcells "''"
             :data-signals:insertdir "''"   ; top|bottom|left|right (insert blank row/col)
             :data-signals:deletedir "''"   ; row|col (delete the line the cursor is on)
             :data-signals:layerdir "''"    ; over|under (which side of the grid a fill paints on)
             :data-signals:rzcmd "''"
             ;; definitions library (ƒ modal)
             :data-signals:defspanel "false"
             :data-signals:defid "''"
             :data-signals:defsrc "''"
             ;; shared big-editor modal (formula bar / style bar / definitions)
             :data-signals:bigedit "false"
             :data-signals:bigwhat "''"
             :data-signals:big "''"
             ;; flatten (⧉): strict = only error-behavior-preserving simplify rules
             :data-signals:flatstrict "false"
             ;; import-.xlsx modal: $importing = upload in flight (disables the
             ;; button), $imported = report replaces the form until re-opened
             :data-signals:importpanel "false"
             :data-signals:importing "false"
             :data-signals:imported "false"
             :data-signals:help "false"
             ;; account agent key (🔑 panel): the MCP credential for ALL your
             ;; sheets. $agentkey holds the freshly minted secret — shown once,
             ;; never persisted client-side; $agentkeyhas only says one exists.
             :data-signals:agentpanel "false"
             :data-signals:agentact "''"
             :data-signals:agentkey "''"
             :data-signals:agentkeyhas (str (boolean (auth/agent-key-info uid)))
             :data-signals:agentrevoke "false"   ; revoke confirmation armed?
             ;; account erasure (same panel). $acctplan is armed by the server's
             ;; answer to "plan", so the warning can name the sheets other
             ;; people are on ($acctshared) before anything is typed.
             :data-signals:acctact "''"
             :data-signals:acctplan "false"
             :data-signals:acctword "''"
             :data-signals:acctsheets "0"
             :data-signals:acctshared "''"
             ;; dependency-graph view (🕸 modal) — server renders #graphview on open
             :data-signals:graphpanel "false"
             ;; sheet properties (⚙ modal, owner-only) — seeded with current defaults
             :data-signals:propspanel "false"
             :data-signals:delconfirm "false"    ; delete-sheet danger zone armed?
             :data-signals:pcw (str (sheet/default-col-w sh))
             :data-signals:prh (str (sheet/default-row-h sh))
             ;; the page IS the window: a flex column exactly 100vh tall, so the
             ;; grid (flex:1) takes every pixel the toolbars don't. Modals/toast
             ;; are position:fixed, so they stay out of this column.
             :style (str "font-family:sans-serif;margin:0;padding:.6rem;height:100vh;"
                         "box-sizing:border-box;display:flex;flex-direction:column;"
                         "background:var(--bg);color:var(--fg);")}
      ;; Toasts: the server appends one card per message (web.sse/toast!), each
      ;; carrying its own dismissal — click on all of them, plus an animation
      ;; that ends by removing an info card. Empty here, and empty again once
      ;; the last card goes; `aria-live` announces arrivals to a screen reader.
      [:ul {:id "toasts" :aria-live "polite"}]
      (h/raw (help-html))
      (when-not asof? (h/raw (defs-html storage-id)))
      (when-not asof? (h/raw (bigedit-html)))
      (when (and owner? (not asof?)) (h/raw (props-html sname)))
      (when-not asof? (h/raw (import-html)))
      (when-not asof? (h/raw (agentkey-html (auth/agent-key-info uid))))
      (when-not asof? (history-modal storage-id sname branch revisions link-token owner?))
      (when-not asof? (graph-modal-html))
      (when-not asof? (violations-modal-html sh))
      ;; only reachable on a non-main branch — main is never deleted
      (when (and (not asof?) (not= branch db/MAIN))
        (branch-gone-html (sheet-href storage-id sname db/MAIN link-token owner?)))
      ;; ── toolbar row 1: sheet management + sharing + identity ───────────
      [:div {:class "toolrow"}
       (sheet-picker uid storage-id sname)
       ;; the branch picker stays in as-of (you can switch branch); its owner
       ;; fork/merge/delete controls are hidden while viewing read-only history.
       (branch-bar uid storage-id sname branch link-token (and owner? (not asof?)))
       (if asof?
         ;; read-only history view: a banner + revision picker + Back-to-live
         (asof-banner storage-id sname branch at revisions link-token owner?)
         ;; live: new-sheet, sharing, format/defs/props, help, history
         (list
          [:input {:id "sheetbox" :class "tool" :placeholder "new sheet…"
                   :data-on:keydown "evt.key==='Enter' && el.value && (location.href='/?s='+el.value)"
                   :title "type a name + Enter to create/open one of your sheets"
                   :style "width:6rem;"}]
          ;; navigate (full reload) when the server sets $goto (fork/delete result)
          [:div {:id "goto" :data-effect "$goto && window.location.assign($goto)" :style "display:none;"}]
          (h/raw (share-html uid storage-id link-token))
          [:span {:class "spacer"}]
          [:button {:class "btn" :data-class:active "$fmtbar"
                    :data-on:click "$fmtbar = !$fmtbar" :title "format / style controls"} "🎨"]
          [:button {:class "btn" :data-on:click "$defspanel=true" :title "sheet definitions (reusable functions)"} "ƒ"]
          [:button {:class "btn" :data-on:click "$graphpanel=true, @post('/graph')" :title "dependency graph"} "🕸"]
          ;; Only shown when something IS failing: a permanent "0 problems" badge
          ;; is furniture people stop seeing, and the whole job of this button is
          ;; to be noticed. It is also the only surface that survives a reload —
          ;; the toast that first reported the violation is long gone by then.
          [:button {:class "btn viol" :data-show "$nviol > 0"
                    :data-on:click "$violpanel=true, @post('/violations')"
                    :title "cells whose assertion does not hold"}
           "⚠ " [:span {:data-text "$nviol"}]]
          (when owner?
            [:button {:class "btn" :data-on:click "$propspanel=true" :title "sheet properties"} "⚙"])
          [:button {:class "btn" :data-on:click "$histpanel=true" :title "history — view an earlier revision"} "🕘"]
          ;; export: a plain download link (GET /export.xlsx), carrying the same
          ;; access params as this page. Formulas export LIVE where `xlformula`
          ;; can spell them, computed values elsewhere (see export.clj).
          (let [q (if link-token
                    (str "?t=" (url-encode link-token) "&b=" (url-encode branch))
                    (str "?s=" (url-encode sname)
                         "&u=" (url-encode (first (store/split-id storage-id)))
                         "&b=" (url-encode branch)))]
            [:a {:class "btn" :href (str "/export.xlsx" q) :download (str sname ".xlsx")
                 :style "text-decoration:none;"
                 :title (str "Export to Excel (.xlsx) — live formulas where Excel has an "
                             "equivalent, computed values elsewhere. Styling and number format "
                             "come along; each formula's source is kept as a cell comment.")}
             "⬇ xlsx"])
          ;; import: opens the multipart-form modal — each tab becomes a NEW sheet.
          ;; Re-opening clears any previous run's report (back to the form).
          [:button {:class "btn" :data-on:click "$imported=false, $importpanel=true"
                    :title (str "Import an Excel workbook (.xlsx) — each tab becomes a new "
                                "sheet of yours, formulas translated to Clojure where possible "
                                "(the rest keep their computed values, commented).")}
           "⬆ xlsx"]
          [:button {:class "btn" :data-on:click "$help=true" :title "help / quick guide"} "?"]))
       ;; who am I + sign out
       [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
        (or (:name (auth/user-info uid)) uid)]
       ;; account-level (not per-sheet), so it lives by the identity, not in the
       ;; share panel: the MCP key that reaches every sheet this user can.
       (when-not asof?
         [:button {:class "btn" :data-on:click "$agentpanel=true"
                   :title "agent key — let an AI agent (MCP) work in your sheets"} "🔑"])
       [:form {:method "post" :action "/logout" :style "margin:0;"}
        [:button {:class "btn"} "sign out"]]]
      ;; ── toolbar row 2: cell reference + formula bar (live only) ─────────
      (when-not asof?
       [:div {:class "toolrow"}
       ;; address box: $sel via data-bind; Enter jumps (app.cljs scrolls there +
       ;; selects). The keydown listener is attached in app.cljs (a scroll action).
       [:input (merge no-autofill
                {:id "addrbox" :class "tool mono" :data-bind:sel "" :placeholder "A1"
                 :style "width:5rem;text-align:center;"})]
       ;; editing via the formula bar still drives presence on the SELECTED cell
       ;; (so it shows the marching-ants self marker and locks it for peers).
       ;; formula bar shares $v with the floating #editor, so the two stay live-
       ;; synced: typing in either updates $v and the other reflects it.
       [:input (merge no-autofill
                {:id "fbar" :class "tool mono" :data-bind:v "" :placeholder "value or =formula like =(+ $A1 $B2 42) or =(sum $A1:A10) - Enter to apply"
                 :data-on:focus "$edit=true, @post('/presence')"
                 :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/cell'))"
                 :data-on:blur "$cell=$sel, @post('/cell'), $edit=false, @post('/presence')"
                 :style "flex:1;"})]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$v, $bigwhat='v', $bigedit=true"} "⤢"]
       ;; ASSERTION lever: a claim about the selected cell's own value, checked
       ;; on every recompute. It lives here rather than in the 🎨 style row
       ;; because it is about the DATA, not its presentation — and it is a lever
       ;; rather than a permanent field because most cells never carry one, and a
       ;; third always-on row would cost every sheet the vertical space.
       [:span {:class "grp"}
        [:button {:class "btn" :title "assertion — a claim about this cell, e.g. =(> $val 0). Flags the cell when it stops holding; never blocks an edit."
                  :data-on:click (str "$assertbar = !$assertbar;"
                                      "const c=document.getElementById('c_'+$sel);"
                                      "c && (" assert-read-js ")")} "⊨"]]
       ;; flatten: server computes the inlined+simplified source of the selected
       ;; formula cell and opens it in the big editor — Apply there posts /cell.
       ;; `strict` modifies THIS action only, so the two share one .grp frame.
       [:span {:class "grp"}
        [:button {:class "btn" :title "flatten formula — inline every referenced formula, then simplify; review in the big editor before applying"
                  :data-on:click "@post('/flatten')"} "⧉"]
        [:label {:class "opt" :title "strict: only simplify rules that preserve error behavior exactly (skip e.g. (+ x 0) → x, which turns a blank-ref error into a blank)"}
         [:input {:type "checkbox" :data-bind:flatstrict ""}] "strict"]]])
      ;; the lever's input: hidden until ⊨ opens it, and pre-filled from the
      ;; selected cell (`data-sty` already carries every prop the cell has).
      (when-not asof?
       [:div {:class "toolrow" :data-show "$assertbar"}
        [:span {:style "font:11px sans-serif;color:var(--muted);"} "⊨ assert"]
        [:input (merge no-autofill
                 {:id "assertbox" :class "tool mono" :data-bind:assertsrc ""
                  :placeholder "=(> $val 0) — a claim about this cell; blank removes it"
                  :data-on:keydown "evt.key==='Enter' && @post('/assert')"
                  :style "flex:1;"})]
        [:button {:class "btn" :title "apply to the selected cell" :data-on:click "@post('/assert')"} "set"]
        [:button {:class "btn" :title "remove the assertion from the selected cell"
                  :data-on:click "$assertsrc='', @post('/assert')"} "clear"]])
      ;; ── toolbar row 3: style of the selected cell (collapsible) ────────
      ;; prop dropdown + a literal-or-=formula source, applied to $sel on Enter
      ;; (like the formula bar — no separate button). $val is the cell's own
      ;; value, e.g. =(if (> $val 100) "tomato" "white"). Hidden until the 🎨
      ;; toggle ($fmtbar) reveals it — keeps the default bar lean.
      (when-not asof?
       [:div {:class "toolrow" :data-show "$fmtbar"}
       [:select {:id "stylepropbox" :class "tool" :data-bind:styleprop ""
                 :data-on:change (str "const c=document.getElementById('c_'+$sel);"
                                      "c && ($v=c.dataset.raw||''," style-read-js ")")
                 :title "style / format property of the selected cell"}
        (for [p style-bar-props] [:option {:value (name p)} (name p)])]
       ;; border is one entry in the prop dropdown; THIS picks which side(s) it
       ;; writes (each side is its own prop). Only shown for border.
       [:select {:id "bordersidebox" :class "tool" :data-bind:borderside ""
                 :data-show "$styleprop=='border'"
                 :data-on:change (str "const c=document.getElementById('c_'+$sel); c && (" style-read-js ")")
                 :title "which side(s) of the cell the border applies to"}
        (for [[side props] border-sides]
          [:option {:value (str/join "," (map name props))} (name side)])]
       [:input (merge no-autofill
                {:id "stylesrcbox" :class "tool mono" :data-bind:stylesrc ""
                 :placeholder "prop value or =formula (use $val for current cell value) like =(if (> $val 100) \"tomato\" \"white\")) — Enter to apply"
                 :data-on:keydown "evt.key==='Enter' && ($cell=$sel, @post('/style'))"
                 :style "flex:1;"})]
       [:button {:class "btn" :title "big editor" :data-on:click "$big=$stylesrc, $bigwhat='style', $bigedit=true"} "⤢"]
       ;; insert a blank row/column around the selected cell (refs follow; one undo)
       [:span {:style "border-left:1px solid var(--grid);margin:0 .2rem;align-self:stretch;"}]
       [:span {:style "font:11px sans-serif;color:var(--muted);"} "insert"]
       [:button {:class "btn" :title "insert row above" :data-on:click "$insertdir='top', @post('/insert')"} "⤒ row"]
       [:button {:class "btn" :title "insert row below" :data-on:click "$insertdir='bottom', @post('/insert')"} "⤓ row"]
       [:button {:class "btn" :title "insert column left" :data-on:click "$insertdir='left', @post('/insert')"} "⇤ col"]
       [:button {:class "btn" :title "insert column right" :data-on:click "$insertdir='right', @post('/insert')"} "⇥ col"]
       ;; delete the row/column the active cell sits on. Separated from the
       ;; inserts by a rule: these DESTROY cells (undoably), the others don't.
       [:span {:style "border-left:1px solid var(--grid);margin:0 .2rem;align-self:stretch;"}]
       [:span {:style "font:11px sans-serif;color:var(--muted);"} "delete"]
       [:button {:class "btn" :style "color:var(--danger);"
                 :title "delete this row — cells after it shift up, references to it become #REF! (Ctrl/⌘+Z restores)"
                 :data-on:click "$deletedir='row', @post('/deleteline')"} "⊖ row"]
       [:button {:class "btn" :style "color:var(--danger);"
                 :title "delete this column — cells after it shift left, references to it become #REF! (Ctrl/⌘+Z restores)"
                 :data-on:click "$deletedir='col', @post('/deleteline')"} "⊖ col"]
       ;; merge / unmerge the selection into one big cell (top-left keeps its
       ;; address; the swallowed cells are hidden but keep their data)
       [:span {:style "border-left:1px solid var(--grid);margin:0 .2rem;align-self:stretch;"}]
       [:button {:class "btn" :title "merge the selected range into one cell (top-left keeps its address; the rest are hidden but kept — reversible)"
                 :data-on:click "@post('/mergecells')"} "⛶ merge"]
       [:button {:class "btn" :title "unmerge the selected cell back into individual cells"
                 :data-on:click "@post('/unmergecells')"} "unmerge"]
       ;; which side of the grid lines the selected cells paint on
       [:span {:style "border-left:1px solid var(--grid);margin:0 .2rem;align-self:stretch;"}]
       [:span {:style "font:11px sans-serif;color:var(--muted);"} "fill"]
       [:button {:class "btn"
                 :title (str "paint the selected cells OVER the grid lines — neighbours "
                             "sharing a fill become one solid block of colour")
                 :data-on:click "$layerdir='over', @post('/celllayer')"} "over grid"]
       [:button {:class "btn"
                 :title (str "paint the selected cells UNDER the grid lines (the default) — "
                             "the fill tints them while the table's ruling still reads across")
                 :data-on:click "$layerdir='under', @post('/celllayer')"} "under grid"]])
      ;; logical-scroll viewport (custom wheel + scrollbars in /app.js)
      (grid-layers sh {:r0 0 :c0 0})

      ;; ── client ⇆ server bridge (no hidden trigger buttons) ─────────────
      ;; app.cljs does the imperative work (scroll / edit / resize / keyboard)
      ;; and, when the server must hear about it, dispatches a `sr-*` CustomEvent
      ;; on window; these declarative handlers turn each into the Datastar action,
      ;; pulling the carried data off evt.detail. The persistent collaboration
      ;; stream lives on its OWN element (#streamer) so app.cljs can pick its
      ;; datastar-fetch lifecycle apart from the @posts for reconnect.
      ;; live only: the persistent collaboration stream + the full control bridge.
      ;; In a read-only as-of view there is no live room, so we open no stream and
      ;; expose ONLY scroll (→ /viewat, which renders the historical window). Every
      ;; mutating sr-* event is simply absent, so nothing can edit the past — and
      ;; the server also forces read-only when $at is set (belt and suspenders).
      (when asof?
        [:div {:id "ctl" :data-sid sid :style "display:none;"
               :data-on:sr-view__window
               (str "$r0=evt.detail.r0, $c0=evt.detail.c0, "
                    "$wc=evt.detail.wc, $wr=evt.detail.wr, @post('/viewat')")}])
      (when-not asof?
       (list
      [:div {:id "streamer" :data-on:sr-open__window "@get('/stream')"
             :style "display:none;"} ""]
      [:div {:id "ctl" :data-sid sid :style "display:none;"
             :data-on:sr-view__window
             (str "$r0=evt.detail.r0, $c0=evt.detail.c0, "
                  "$wc=evt.detail.wc, $wr=evt.detail.wr, @post('/view')")
             :data-on:sr-size__window "$rzcmd=evt.detail.cmd, @post('/size')"
             ;; per-user undo / redo (Ctrl+Z / Ctrl+Shift+Z|Ctrl+Y from app.cljs)
             :data-on:sr-undo__window "@post('/undo')"
             :data-on:sr-redo__window "@post('/redo')"
             ;; keep $selcells in sync with the live selection (no post) so a
             ;; selection-wide action (style a rectangle, /clear, …) uses the
             ;; current ranges
             :data-on:sr-sel__window "$selcells=evt.detail.ranges"
             ;; clear the current selection (Delete/Backspace from app.cljs)
             :data-on:sr-clear__window "$selcells=evt.detail.ranges, @post('/clear')"
             ;; clipboard (Ctrl/⌘ C / X / V from app.cljs) — selection rides in $selcells
             :data-on:sr-copy__window  "$selcells=evt.detail.ranges, @post('/copy')"
             :data-on:sr-cut__window   "$selcells=evt.detail.ranges, @post('/cut')"
             :data-on:sr-paste__window "$selcells=evt.detail.ranges, @post('/paste')"
             ;; commit an in-progress edit (app.cljs fires this before a resize
             ;; drag, whose preventDefault would otherwise swallow the blur)
             :data-on:sr-commit__window "$edit && ($cell=$sel, @post('/cell'), $edit=false, $celledit=false, @post('/presence'))"
             ;; select: move cursor + mirror the cell's value/style into the bars
             :data-on:sr-select__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "c ? (" style-read-js ", " assert-read-js ") : ($stylesrc='', $assertsrc='');"
                  "$edit=false; $celledit=false; @post('/presence')")
             ;; start editing in-cell: load the cell's source into $v, take the edit
             ;; lock, and reveal the floating editor (app.cljs already positioned it)
             :data-on:sr-edit__window
             (str "const c=document.getElementById('c_'+evt.detail.addr);"
                  "$sel=evt.detail.addr; $v=c?(c.dataset.raw||''):'';"
                  "$edit=true; $celledit=true; @post('/presence')")} ""]))]]))))

;; --- SSE (official Datastar SDK) ----------------------------------------

;; Optional SSE tracing: set SALTRIM_SSE_DEBUG=1 to log every server-sent event
;; (type + a snippet of its data lines) to the console. Implemented as a Datastar
;; write profile — the SDK's designed seam for this — so it sees every event the
;; server emits through the SDK, on both the one-shot @post responses and the
;; persistent /stream. (The raw WebKit flush comment bypasses the SDK, so
;; `flush-tick!` logs itself.) Off by default = zero overhead.
(defn render-cells
  "Cell-input HTML for addrs, positioned window-relative to view (cbase,rbase).
   Skips cells hidden under a merge and draws a merge anchor spanning its block —
   so a pushed edit patches the right shape (a full window re-render, not this, is
   what runs when a merge itself changes)."
  [sh addrs view]
  (let [[cb rb] (view-base view)
        anchors (sheet/merge-spans sh)
        hidden  (covered anchors)]
    (apply str (keep #(when-not (contains? hidden %)
                        (let [{:keys [ci ri]} (addr/parse %)]
                          (str (h/html (cell-input sh % ci ri cb rb (get anchors %))))))
                     addrs))))

(defn- peer-marker
  "Overlay div for one peer's cursor, positioned window-relative to `view`. An
   editing peer's marker captures pointer events (locks the cell beneath)."
  [sh view {:keys [cursor editing color uname]}]
  (let [{:keys [ci ri]} (addr/parse cursor)
        [cb rb] (view-base view)
        editing? (= editing cursor)
        tag (or uname "•")
        span (get (sheet/merge-spans sh) cursor)      ; anchor -> cover the block
        [w h] (if span (span-px sh ci ri (first span) (second span))
                  [(col-w sh ci) (row-h sh ri)])
        ;; the tag normally floats above the cell (CSS top:-15px); on the top
        ;; rendered row that's clipped by #cellclip's overflow, so flip it below.
        top-row? (zero? (- ri rb))
        tag-style (str "background:" color
                       (when top-row? (str ";top:" (dec (long h)) "px;border-radius:0 3px 3px 3px")))
        base (format (str "left:%dpx;top:%dpx;width:%dpx;height:%dpx;border-color:%s;")
                     (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                     (dec (long w)) (dec (long h)) color)]
    (str (h/html
          [:div {:class (str "peer" (when editing? " editing"))
                 :style (if editing?
                          (str base "background:" (rgba color "0.16")
                               ";pointer-events:auto;cursor:not-allowed;")
                          base)}
           [:span {:class "peertag" :style tag-style}
            (if editing? (str tag " editing…") tag)]]))))

(defn peers-html
  "Overlay markers for every OTHER session in the viewer's ROOM whose cursor
   falls in viewer-sid's window. Rendered relative to the viewer's own view so
   coords line up. `room` = [sheet-id branch]."
  [viewer-sid room]
  (let [view (session-view viewer-sid)
        sh   (:sh (@sheets* room))]
    (apply str
           (for [[sid s] @sessions*
                 :when (and (not= sid viewer-sid) (= room (:room s))
                            (:cursor s) (in-window? sh view (:cursor s)))]
             (peer-marker sh view s)))))

(defn self-html
  "THIS session's own selection / editing marker for its #self overlay, rendered
   window-relative to its own view. Empty when there is no cursor or it scrolled
   out of the window. `room` = [sheet-id branch]."
  [sid room]
  (let [s    (@sessions* sid)
        view (session-view sid)
        a    (:cursor s)
        sh   (:sh (@sheets* room))]
    (if (and s sh (= room (:room s)) a (in-window? sh view a))
      (let [{:keys [ci ri]} (addr/parse a)
            [cb rb] (view-base view)
            span (get (sheet/merge-spans sh) a)       ; anchor -> cover the block
            [w h] (if span (span-px sh ci ri (first span) (second span))
                      [(col-w sh ci) (row-h sh ri)])]
        (str (h/html
              [:div {:class (str "selfcell" (when (= (:editing s) a) " editing"))
                     :style (format "left:%dpx;top:%dpx;width:%dpx;height:%dpx;"
                                    (- (axis-x sh ci) (axis-x sh cb)) (- (axis-y sh ri) (axis-y sh rb))
                                    (dec (long w)) (dec (long h)))}])))
      "")))

(defn- def-names
  "The symbols a chunk declares — the names of every top-level (def…)/(defn…)
   form — shown as badges on the collapsed card. Empty source -> [\"untitled\"]."
  [src]
  (let [ns (map second (re-seq #"\(def[a-z]*\s+([A-Za-z0-9*+!?<>=_.%/-]+)" (str src)))]
    (if (seq ns) (vec ns) ["untitled"])))

(defn- fmt-edited
  "Epoch-ms -> \"yyyy-MM-dd HH:mm\" (local), or nil."
  [ms]
  (when ms
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli (long ms))
                                                (java.time.ZoneId/systemDefault)))))

(defn deflib-html
  "Inner HTML of #deflib for session `sid`. An ACCORDION: each chunk shows
   collapsed (tier 1) as its declared-name badges + last-edit time, with Edit /
   delete. Edit (`/deflock`) expands it (tier 2) into a textarea bound to $defsrc
   with Save / Cancel and a ⤢ that opens the shared big editor (tier 3). A chunk
   held by another session shows a lock badge and stays collapsed. `sid` may be
   nil (initial page render: all read-only, no own-edit). `room` = [sheet-id branch]."
  [sid room]
  (let [sh     (:sh (@sheets* room))
        chunks (when sh (sheet/defs sh))
        card   "border:1px solid var(--grid);border-radius:6px;padding:.45rem .6rem;margin:.4rem 0;"
        row    "display:flex;align-items:center;gap:.4rem;flex-wrap:wrap;"
        when-s "font:11px sans-serif;color:var(--muted);white-space:nowrap;"
        ta     (str "width:100%;box-sizing:border-box;min-height:7rem;margin:.3rem 0;"
                    "white-space:pre;font:13px/1.4 monospace;resize:vertical;")
        badges (fn [src] (for [n (def-names src)] [:span {:class "badge"} n]))]
    (str (h/html
          [:div
           (if (empty? chunks)
             [:p {:style "font:13px sans-serif;color:var(--muted);margin:.3rem 0;"}
              "No definitions yet — add one below."]
             (for [{:keys [id src edited]} chunks
                   :let [editor (def-editor-of room id)
                         mine?  (and sid (= editor sid))]]
               [:div {:style card}
                (cond
                  ;; tier 2/3 — this session is editing: textarea + ⤢ big editor
                  mine?
                  [:div
                   [:div {:style row}
                    (badges src) [:span {:style "flex:1;"}]
                    [:span {:style "font:11px sans-serif;color:var(--accent);"} "editing"]
                    [:button {:class "btn" :title "open the big editor"
                              :data-on:click "$big=$defsrc, $bigwhat='def', $bigedit=true"} "⤢"]]
                   [:textarea {:class "tool mono" :data-bind:defsrc "" :spellcheck "false"
                               :placeholder "(defn double [x] (* 2 x))" :style ta}]
                   [:div {:style "display:flex;gap:.4rem;"}
                    [:button {:class "btn primary" :data-on:click "@post('/defsave')"} "Save"]
                    [:button {:class "btn" :data-on:click "@post('/defunlock')"} "Cancel"]]]

                  ;; locked by another collaborator: collapsed, no Edit
                  editor
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   [:span {:style when-s}
                    (str "🔒 " (or (get-in @sessions* [editor :uname]) "someone") " editing")]]

                  ;; tier 1 collapsed: name badges + last-edit time + Edit / delete
                  :else
                  [:div {:style row}
                   (badges src) [:span {:style "flex:1;"}]
                   (when-let [w (fmt-edited edited)] [:span {:style when-s} (str "edited " w)])
                   [:button {:class "btn" :data-on:click (str "$defid=" (js-str id) ", @post('/deflock')")} "Edit"]
                   [:button {:class "btn" :data-on:click (str "$defid=" (js-str id) ", @post('/defdel')")
                             :title "delete this definition"} "🗑"]])]))
           [:button {:class "btn" :data-on:click "@post('/defadd')" :style "margin-top:.3rem;"}
            "+ Add definition"]]))))

(defn- bigedit-html
  "A large shared editor modal (#bigedit). Opened from the formula bar, the style
   bar, or a definition card by setting $big (the text to edit), $bigwhat (which
   target: 'v' | 'style' | 'def') and $bigedit=true. Apply writes $big back to the
   target signal and posts to the matching endpoint — entirely declarative."
  []
  (let [ta (str "width:100%;box-sizing:border-box;min-height:52vh;"
                "font:13px/1.5 monospace;white-space:pre;resize:vertical;"
                "border:1px solid var(--line);border-radius:var(--radius);padding:.5rem .6rem;")]
    (str (h/html
          [:div {:id "bigeditwrap" :data-show "$bigedit" :data-on:click "$bigedit=false"
                 :style (str "position:fixed;inset:0;z-index:60;background:rgba(0,0,0,.35);"
                             "display:flex;align-items:flex-start;justify-content:center;padding:4vh 1rem;")}
           [:div {:data-on:click "evt.stopPropagation()"
                  :style (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                              "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:48rem;width:100%;padding:1rem 1.1rem;")}
            [:div {:style "display:flex;align-items:center;margin-bottom:.4rem;"}
             [:h2 {:style "margin:0;font:600 15px sans-serif;flex:1;"
                   :data-text (str "$bigwhat==='v' ? 'Edit value / formula' : "
                                   "($bigwhat==='style' ? 'Edit style source' : 'Edit definition')")}]
             [:button {:class "btn" :data-on:click "$bigedit=false" :title "close"} "✕"]]
            [:textarea {:id "bigbox" :class "mono" :data-bind:big "" :spellcheck "false" :style ta}]
            [:div {:style "display:flex;gap:.4rem;margin-top:.5rem;justify-content:flex-end;"}
             [:button {:class "btn" :data-on:click "$bigedit=false"} "Cancel"]
             [:button {:class "btn primary"
                       :data-on:click
                       (str "($bigwhat==='v' ? ($v=$big, $cell=$sel, @post('/cell')) : "
                            "$bigwhat==='style' ? ($stylesrc=$big, $cell=$sel, @post('/style')) : "
                            "($defsrc=$big, @post('/defsave'))), $bigedit=false")} "Apply"]]]]))))

(defn- level-label [lvl]
  (case lvl :read-write "can edit" :read "can view" "no access"))

(defn share-html
  "The #sharebar fragment. Visitors see a read-only badge (their effective
   level, given the link `token` they arrived with). The owner gets a popover
   (toggled by $sharepanel) to set the capability-link level, copy/rotate the
   secret link, and grant/revoke direct per-user shares (name in dev, email in
   prod)."
  [uid sheet-id token]
  (let [owner     (owner-of sheet-id)
        owner?    (= uid owner)
        link      (db/link-grant sheet-id)             ; {:token :level} | nil
        lvl       (:level link)
        grants    (->> (db/sheet-grants sheet-id)
                       (filter #(= :user (:kind %)))
                       (sort-by :grantee))
        url       (str (auth/base-url) "/?t=" (:token link))   ; self-contained capability
        badge     (cond (= lvl :read-write) "🔗 link" (= lvl :read) "🔗 link" :else "🔒 private")
        add-ph    (if (auth/dev-auth?) "name to share with…" "email to share with…")
        row-style "display:flex;align-items:center;gap:.4rem;margin-bottom:.45rem;"
        overlay   (str "position:fixed;inset:0;z-index:50;background:rgba(0,0,0,.35);"
                       "display:flex;align-items:flex-start;justify-content:center;padding:12vh 1rem;")
        modal     (str "background:var(--bg);border:1px solid var(--line);border-radius:8px;"
                       "box-shadow:0 8px 32px rgba(0,0,0,.25);max-width:22rem;width:100%;padding:1rem 1.1rem;"
                       "font:13px sans-serif;color:var(--fg);")]
    (str (h/html
          [:div {:id "sharebar" :style "display:flex;align-items:center;gap:.4rem;position:relative;"}
           (if-not owner?
             [:span {:style "font:12px sans-serif;color:var(--muted);white-space:nowrap;"}
              (str "shared by " (or (:name (auth/user-info owner)) owner)
                   " · " (level-label (db/access-level uid sheet-id token)))]
             (list
              [:button {:class "btn" :data-on:click "$sharepanel = !$sharepanel" :title "sharing"}
               badge]
              [:div {:data-show "$sharepanel"
                     :style (str "position:absolute;top:118%;left:0;z-index:30;width:24rem;"
                                 "background:var(--bg);border:1px solid var(--line);border-radius:6px;"
                                 "box-shadow:0 4px 16px rgba(0,0,0,.18);padding:.7rem;"
                                 "font:12px sans-serif;color:var(--fg);")}
               ;; capability link
               [:div {:style row-style}
                [:span {:style "flex:1;"} "Anyone with the link"]
                [:select {:class "tool"
                          :data-on:change "$shareact='link', $plevel=el.value, @post('/share')"}
                 [:option {:value "none"       :selected (nil? lvl)}          "no access"]
                 [:option {:value "read"       :selected (= lvl :read)}       "can view"]
                 [:option {:value "read-write" :selected (= lvl :read-write)} "can edit"]]]
               (when link
                 (list
                  [:div {:style "display:flex;gap:.3rem;margin-bottom:.5rem;"}
                   [:input {:readonly true :value url :title "secret share link"
                            :style (str "flex:1;box-sizing:border-box;font:11px monospace;"
                                        "padding:4px 6px;border:1px solid var(--grid);"
                                        "border-radius:var(--radius);color:var(--muted);")}]
                   [:button {:class "btn" :title "copy link"
                             :data-on:click
                             (str "navigator.clipboard.writeText(" (js-str url) "), "
                                  "el.textContent='✓', setTimeout(()=>el.textContent='📋',1200)")}
                    "📋"]
                   [:button {:class "btn" :title "make a new link (invalidates the old one)"
                             :data-on:click "$rotateconfirm=true"} "↻"]]
                  ;; agreement modal — rotating breaks the old link for everyone
                  ;; who still has it, so this needs an explicit confirm step.
                  [:div {:data-show "$rotateconfirm" :data-on:click "$rotateconfirm=false"
                         :style overlay}
                   [:div {:data-on:click "evt.stopPropagation()" :style modal}
                    [:h2 {:style "margin:0 0 .5rem;font:600 15px sans-serif;"} "Make a new link?"]
                    [:p {:style "color:var(--muted);margin:.2rem 0 .8rem;"}
                     "This invalidates the current link. Anyone who still has it — including "
                     "people you already shared it with — loses access until you send them "
                     "the new one."]
                    [:div {:style "display:flex;gap:.4rem;justify-content:flex-end;"}
                     [:button {:class "btn" :data-on:click "$rotateconfirm=false"} "Cancel"]
                     [:button {:class "btn primary"
                               :style "background:var(--danger);border-color:var(--danger);"
                               :data-on:click
                               "$rotateconfirm=false, $shareact='rotate', @post('/share')"}
                      "Yes, create new link"]]]]))
               ;; per-user grants
               [:div {:style "border-top:1px solid var(--grid);padding-top:.5rem;"}
                (if (seq grants)
                  (for [{:keys [grantee level]} grants]
                    [:div {:style row-style}
                     [:span {:style "flex:1;"} (or (:name (auth/user-info grantee)) grantee)]
                     [:span {:style "color:var(--muted);"} (level-label level)]
                     [:button {:class "btn" :title "remove"
                               :data-on:click (str "$shareact='revoke', $grantee=" (js-str grantee) ", @post('/share')")}
                      "✕"]])
                  [:div {:style "color:var(--muted);margin-bottom:.45rem;"} "not shared with anyone yet"])
                ;; add person
                [:div {:style "display:flex;gap:.3rem;margin-top:.4rem;"}
                 [:input {:class "tool" :data-bind:gtarget "" :placeholder add-ph :style "flex:1;"}]
                 [:select {:class "tool" :data-bind:glevel ""}
                  [:option {:value "read-write"} "edit"]
                  [:option {:value "read"} "view"]]
                 [:button {:class "btn" :data-on:click "$shareact='grant', @post('/share')"} "share"]]]]))]))))

(defn- merge-val
  "Compact display of a property's source for the conflict list ((empty) for a
   deletion / absence; truncated if long)."
  [s]
  (cond (nil? s)          [:em {:style "color:var(--muted);"} "(empty)"]
        (> (count s) 48)  (str (subs s 0 48) "…")
        :else             s))

(defn merge-result-html
  "Inner #mergeresult fragment for a merge PREVIEW of `source` → `target`: the
   clean-merge count, and for each conflict a checkbox that toggles its key in
   $mergetake (take source) plus the two competing values. Empty when already up
   to date. Ends with the Apply button."
  [source target {:keys [take conflicts]}]
  (let [nt (count take) nc (count conflicts)
        mono "font:12px monospace;white-space:pre-wrap;word-break:break-all;"]
    (str (h/html
          [:div {:id "mergeresult" :style "margin-top:.6rem;"}
           (if (and (zero? nt) (zero? nc))
             [:p {:style "color:var(--muted);margin:.2rem 0;"}
              (str "✓ 🌿 " target " is already up to date with 🌿 " source ".")]
             (list
              [:p {:style "margin:.2rem 0;"}
               [:strong (str nt)] (str " cell-" (if (= nt 1) "property" "properties") " merge cleanly")
               (when (pos? nc) [:span (str " · " nc " conflict" (when (> nc 1) "s"))])]
              (when (pos? nc)
                [:div {:style (str "max-height:30vh;overflow:auto;border:1px solid var(--grid);"
                                   "border-radius:6px;padding:.2rem .5rem;margin:.3rem 0;")}
                 [:p {:style "color:var(--muted);font-size:11px;margin:.2rem 0 .3rem;"}
                  "Both branches changed these. Tick to take " [:strong (str "🌿 " source)]
                  "'s version; unticked keeps " [:strong (str "🌿 " target)] "'s."]
                 (for [{k :key csrc :source ctgt :target} conflicts
                       :let [ks (mrg/key->str k) [a p] k]]
                   [:label {:style (str "display:flex;gap:.4rem;align-items:baseline;"
                                        "padding:.25rem 0;border-top:1px solid var(--grid);")}
                    [:input {:type "checkbox"
                             :data-on:change (str "$mergetake = evt.target.checked"
                                                  " ? ($mergetake+' '+" (js-str ks) ").trim()"
                                                  " : $mergetake.split(' ').filter(x=>x&&x!==" (js-str ks) ").join(' ')")}]
                    [:span {:style "flex:1;"}
                     [:strong (str a)] " " [:span {:style "color:var(--muted);font-size:11px;"} (name p)]
                     [:div {:style mono} "↱ " (merge-val csrc)]
                     [:div {:style (str mono "color:var(--muted);")} "= " (merge-val ctgt)]]])])
              [:button {:class "btn primary" :style "margin-top:.4rem;"
                        :data-on:click "$branchact='apply', @post('/merge')"}
               "Apply merge"]))]))))

(defn- node-label
  "A cell's display name in the graph: its `:label` meta-prop if set, else the
   address."
  [sh a]
  (let [l (sheet/style-value sh a :label)]
    (if (and (string? l) (not (str/blank? l))) l (str a))))

(defn graph-svg
  "Render the layered DAG (`graph/build` output) as an inline SVG: nodes placed
   left→right by dependency depth, edges arrow from a cell to the cells that read
   it. Edges in `dyn-edges` (currently-resolved dynamic `$(…)` targets) render
   dashed — they can move on the next recompute. A node click selects the cell
   (and closes the modal)."
  ([sh g] (graph-svg sh g #{}))
  ([sh {:keys [nodes edges layer]} dyn-edges]
  (let [COLW 168 ROWH 40 NW 132 NH 26 PAD 16
        by-layer (->> nodes (group-by layer) (into (sorted-map)))
        pos (into {}
                  (for [[lyr ns] by-layer
                        [i a] (map-indexed
                               vector
                               (sort-by (fn [a] (let [{:keys [ci ri]} (addr/parse a)] [ri ci])) ns))]
                    [a [(+ PAD (* (long lyr) COLW)) (+ PAD (* i ROWH))]]))
        nlayers (inc (long (apply max 0 (vals layer))))
        maxrows (apply max 1 (map count (vals by-layer)))
        W (+ PAD (* nlayers COLW))
        H (+ PAD (* maxrows ROWH))]
    (str (h/html
          [:svg {:viewBox (format "0 0 %d %d" W H) :width W :height H
                 :style "font:11px sans-serif;min-width:100%;"}
           [:defs
            [:marker {:id "arr" :viewBox "0 0 10 10" :refX "9" :refY "5"
                      :markerWidth "7" :markerHeight "7" :orient "auto-start-reverse"}
             [:path {:d "M0,0 L10,5 L0,10 z" :fill "#9ec9ee"}]]]
           (for [[f t :as e] edges :let [[fx fy] (pos f) [tx ty] (pos t)]]
             [:line (cond-> {:x1 (+ fx NW) :y1 (+ fy (quot NH 2)) :x2 tx :y2 (+ ty (quot NH 2))
                             :stroke "#9ec9ee" :stroke-width "1.5" :marker-end "url(#arr)"}
                      (contains? dyn-edges e) (assoc :stroke-dasharray "4 3"))])
           (for [a nodes :let [[x y] (pos a) lbl (node-label sh a)]]
             [:g {:data-on:click (str "$sel=" (js-str a) ", $graphpanel=false") :style "cursor:pointer;"}
              [:title (str a)]
              [:rect {:x x :y y :width NW :height NH :rx 4
                      :fill "#f4f6f8" :stroke "#2f8fd8" :stroke-width "1"}]
              [:text {:x (+ x 8) :y (+ y 17) :fill "#3a4149"}
               (let [s (str lbl)] (if (> (count s) 19) (str (subs s 0 18) "…") s))]])])))))

(defn login-page [err]
  (let [field (str "font:13px sans-serif;padding:6px 8px;border:1px solid #c7ccd1;"
                   "border-radius:4px;")]
    (str
     "<!doctype html>"
     (h/html
      [:html
       [:head [:meta {:charset "utf-8"}] [:title "SaltRim — sign in"]
        [:link {:rel "icon" :type "image/x-icon" :href "/favicon.ico"}]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
        [:link {:rel "apple-touch-icon" :href "/apple-touch-icon.png"}]
        [:meta {:property "og:type" :content "website"}]
        [:meta {:property "og:site_name" :content "SaltRim"}]
        [:meta {:property "og:title" :content "SaltRim — sign in"}]
        [:meta {:property "og:description"
                :content "A simple-but-powerful Clojure reactive spreadsheet. Cells are Clojure expressions, collaborative edits and git-like branching are build in."}]
        [:meta {:property "og:url" :content (auth/base-url)}]
        [:meta {:property "og:image" :content (str (auth/base-url) "/SaltRim-opengraph.png")}]
        [:meta {:name "twitter:card" :content "summary_large_image"}]]
       ;; explicit light bg so an OS dark theme can't black out the page; the
       ;; centered column lives in an inner wrapper.
       [:body {:style "font-family:sans-serif;margin:0;min-height:100vh;background:#fefefe;color:#3a4149;"}
        [:div {:style "max-width:24rem;margin:0 auto;padding:14vh 1rem 0;"}
        [:img {:src "/SaltRim.png" :alt "SaltRim"
               :style "display:block;margin:0 auto .6rem;width:180px;height:auto;"}]
        [:p {:style "color:#7a828b;text-align:center;margin-top:0;"} "Sign in to open your sheets."]
        (when err
          [:p {:style "color:#c0392b;font:13px sans-serif;"} (url-decode err)])
        [:div {:style "display:flex;flex-direction:column;gap:.6rem;"}
         (for [[k p] (auth/providers)]
           [:a {:href (str "/auth/" (name k))
                :style (str field "text-align:center;text-decoration:none;"
                            "background:#f4f6f8;color:#3a4149;display:block;")}
            (str "Continue with " (:label p))])
         (when (auth/dev-auth?)
           [:form {:method "get" :action "/auth/dev"
                   :style "display:flex;gap:.4rem;"}
            [:input {:name "name" :placeholder "your name (dev login)"
                     :autofocus true :style (str field "flex:1;")}]
            [:button {:style field} "Sign in"]])]
        ;; Before you sign in is exactly when the privacy notice is worth
        ;; reading — signing in is the moment your provider hands us your name
        ;; and address.
        [:p {:style "text-align:center;margin-top:1.4rem;font:11px sans-serif;color:#aab0b8;"}
         "SaltRim " (version/current) " · "
         [:a {:href "/privacy" :style "color:inherit;"} "Privacy"] " · "
         [:a {:href "/terms" :style "color:inherit;"} "Terms"]]]]]))))

;; --- privacy notice + terms of use ------------------------------------------
;; Standalone pages, PUBLIC by design: Google's OAuth consent screen wants a
;; privacy policy URL that works without signing in, and a notice you can only
;; read once you have already handed over your data is not a notice. They carry
;; no Datastar and no /app.js — plain HTML, so they render if everything else is
;; broken.
;;
;; The facts here are asserted by `legal_test`: what the pages claim SaltRim
;; stores has to match the schema, and what they claim deletion does has to
;; match `db/delete-user!`. A privacy notice that drifts from the code is worse
;; than none, because people rely on it.

(def ^:private LAST-UPDATED "12 August 2026")

(defn- legal-page
  "Shell for the public legal pages: same look as the login page, no scripts."
  [title & body]
  (let [muted "color:#6b737b;"]
    (str
     "<!doctype html>"
     (h/html
      [:html
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
        [:title (str "SaltRim — " title)]
        [:link {:rel "icon" :type "image/x-icon" :href "/favicon.ico"}]
        [:style (h/raw
                 (str "body{font:15px/1.6 sans-serif;margin:0;background:#fefefe;color:#3a4149;}"
                      ".wrap{max-width:44rem;margin:0 auto;padding:3rem 1.2rem 4rem;}"
                      "h1{font-size:26px;font-weight:600;margin:0 0 .2rem;}"
                      "h2{font-size:17px;font-weight:600;margin:2rem 0 .5rem;}"
                      "ul{padding-left:1.2rem;}li{margin:.25rem 0;}"
                      "a{color:#2f8fd8;}"
                      "code{font:13px monospace;background:#f1f3f5;padding:0 4px;border-radius:3px;}"
                      ".foot{margin-top:3rem;padding-top:1rem;border-top:1px solid #e3e6e9;"
                      "font-size:12px;" muted "}"))]]
       [:body
        [:div {:class "wrap"}
         [:h1 title]
         [:p {:style (str "margin-top:0;font-size:13px;" muted)}
          "Last updated " LAST-UPDATED "."]
         body
         [:p {:class "foot"}
          [:a {:href "/"} "SaltRim"] " · "
          [:a {:href "/privacy"} "Privacy"] " · "
          [:a {:href "/terms"} "Terms"] " · "
          [:a {:href "https://github.com/aldebogdanov/saltrim"
               :target "_blank" :rel "noopener"} "Source"]]]]]))))

(defn privacy-page []
  (legal-page
   "Privacy"
   [:p "SaltRim is a free, non-commercial project. This page says what it keeps "
    "about you, why, and how to get rid of it."]

   [:h2 "Who is responsible"]
   [:p "Aleksandr Bogdanov, an individual rather than a company. Write to "
    [:a {:href "mailto:privacy@michelada.uno"} "privacy@michelada.uno"]
    " about anything on this page."]
   [:p "SaltRim is not established in the EU or EEA. It is offered to people "
    "there, so the GDPR applies to it under Article 3(2). No representative "
    "under Article 27 has been appointed: the processing is limited to account "
    "details and documents you create yourself, involves no special-category "
    "data, is not large scale, and is unlikely to result in a risk to your "
    "rights and freedoms. If that stops being true, this page changes first."]

   [:h2 "What is stored"]
   [:p "From your login provider, when you sign in with GitHub or Google:"]
   [:ul
    [:li "your display name"]
    [:li "your email address"]
    [:li "your avatar image URL"]
    [:li "which provider you used, and the opaque account id it gave us "
     "(for example " [:code "github-12345"] ")"]]
   [:p "Created by you, as you use the app:"]
   [:ul
    [:li "your sheets — cell values, formulas, styles, comments, labels, your "
     "function library, branches, and the edit history that makes time-travel "
     "and merging work"]
    [:li "who you shared a sheet with, and sheets other people shared with you"]
    [:li "which account last wrote each cell, which is what per-user undo uses"]]
   [:p "Operational:"]
   [:ul
    [:li "a sign-in cookie holding a random token — only a SHA-256 hash of it "
     "is stored, never the token itself"]
    [:li "agent keys for MCP access, stored the same way, as a hash"]
    [:li "timestamps: when the account was created, when a token was last used, "
     "when each cell changed"]]
   [:p [:b "There is no analytics, no tracking, no advertising and no "
        "third-party script on any page."] " Nothing is sold, and nothing is "
    "shared with anyone for marketing."]

   [:h2 "Why"]
   [:p "To run your account and the service you signed up for — Article 6(1)(b), "
    "processing necessary to provide what you asked for. Your email address "
    "additionally makes it possible for someone to share a sheet with you by "
    "address."]

   [:h2 "Where it is kept"]
   [:ul
    [:li "Application server: " [:b "vpsFree.cz"] ", Czech Republic."]
    [:li "Database: " [:b "YugabyteDB Cloud"] " on AWS " [:code "eu-central-1"]
     " (Frankfurt, Germany)."]]
   [:p "Your data is stored in the EEA. YugabyteDB Cloud is run by a company "
    "based in the United States; any access by its staff for support purposes "
    "is governed by its data processing agreement."]

   [:h2 "How long"]
   [:ul
    [:li [:b "Account details"] " — until you delete your account. Nothing "
     "expires on its own; an unused account is not removed for you."]
    [:li [:b "Sign-in tokens"] " — 90 days after they were last used, then "
     "deleted automatically."]
    [:li [:b "Sheets"] " — until you, or the owner of a shared sheet, delete "
     "them."]
    [:li [:b "Backups"] " — deleted data can survive in database backups for up "
     "to 30 days. After that it is gone from those too."]]

   [:h2 "Deleting your account"]
   [:p "Open the " [:b "🔑"] " panel in the toolbar and choose "
    [:i "Delete my account"] ". It removes:"]
   [:ul
    [:li "your name, email address and avatar"]
    [:li "every sign-in token and agent key"]
    [:li "every sheet you own, with all of its content and its history"]
    [:li "every share granted to you on other people's sheets"]]
   [:p "This happens immediately and cannot be undone. The history goes with it, "
    "so there is no earlier version of a deleted sheet to recover — not for you "
    "and not for anyone you had shared it with."]
   [:p [:b "One thing is deliberately kept: the opaque account id"] " (for "
    "example " [:code "github-12345"] "). It forms part of the identifier of "
    "every sheet you ever made, and it is recorded as the author of cells you "
    "wrote in other people's sheets — removing it would mean rewriting other "
    "people's documents. Once your name, email address and avatar are gone, "
    "that id is not connected to you anywhere in this system. We would rather "
    "say this plainly than claim an erasure more complete than the one we do."]
   [:p "Signing in again with the same provider account afterwards gives you a "
    "new, empty account under that same id."]

   [:h2 "Your rights"]
   [:p "You can ask for access to your data, correction, deletion, restriction "
    "of processing, portability, and you can object to processing. Most of it "
    "is self-service:"]
   [:ul
    [:li [:b "Access and correction"] " — your name, email and avatar come from "
     "your login provider and are refreshed from it each time you sign in."]
    [:li [:b "Portability"] " — every sheet exports to " [:code ".xlsx"]
     " from the toolbar, formulas included."]
    [:li [:b "Deletion"] " — the 🔑 panel, as above."]]
   [:p "For anything else, write to "
    [:a {:href "mailto:privacy@michelada.uno"} "privacy@michelada.uno"] "."]
   [:p "If you are in the EU or EEA and think this is being handled badly, you "
    "can complain to the data protection authority of the country you live in. "
    "The " [:a {:href "https://edpb.europa.eu/about-edpb/about-edpb/members_en"
                :target "_blank" :rel "noopener"} "EDPB member list"]
    " has the contact details."]

   [:h2 "Cookies"]
   [:p "One cookie, holding your sign-in token. The app cannot work without it "
    "and it is used for nothing else — no analytics, no advertising, no "
    "profiling. That is why there is no cookie banner to click through."]

   [:h2 "Changes"]
   [:p "If this page changes in a way that affects you, the date at the top "
    "changes with it. The page is in "
    [:a {:href "https://github.com/aldebogdanov/saltrim" :target "_blank"
         :rel "noopener"} "the repository"]
    ", so every past version of it is in the git history."]))

(defn terms-page []
  (legal-page
   "Terms of use"
   [:p "These terms cover the hosted service at this address. The SaltRim "
    [:i "software"] " is separate: it is open source under the MIT licence, and "
    "the licence text in "
    [:a {:href "https://github.com/aldebogdanov/saltrim" :target "_blank"
         :rel "noopener"} "the repository"] " governs it."]

   [:h2 "What this is"]
   [:p "A free, non-commercial spreadsheet, run by one person as a personal "
    "project. There is no charge, no subscription and no commercial "
    "relationship between us."]

   [:h2 "No warranty, no guarantees"]
   [:p "The service is provided " [:b "as is"] " and " [:b "as available"] ". "
    "It may be slow, may be down, may lose data, and may stop existing. To the "
    "fullest extent the law allows, there is no warranty of any kind and no "
    "liability for any loss arising from using it — including lost data or lost "
    "work."]
   [:p [:b "Keep your own copies of anything you care about."] " Every sheet "
    "exports to " [:code ".xlsx"] " from the toolbar; that is the backup."]
   [:p "Nothing here takes away rights you have as a consumer under the law of "
    "the country you live in, where those rights cannot be waived."]

   [:h2 "Your account and your content"]
   [:p "Your sheets are yours. Hosting them here grants no rights over them "
    "beyond what is needed to store and show them to you and to the people you "
    "share them with."]
   [:p "You are responsible for what you put in them and for who you share them "
    "with. A share link is a capability: anyone holding it can open the sheet, "
    "so treat it as a secret and rotate it if it gets out."]

   [:h2 "Acceptable use"]
   [:p "Don't use the service to store or distribute unlawful material; don't "
    "attempt to reach sheets that were not shared with you; don't attack, "
    "overload or probe the service; don't use it to send unsolicited messages "
    "to other people."]

   [:h2 "Suspension and shutdown"]
   [:p "Accounts or content that break these terms may be removed. Because this "
    "is a personal project, the service itself may be discontinued — reasonable "
    "notice will be given where that is possible, but it cannot be promised."]

   [:h2 "Privacy"]
   [:p "What is stored about you, and how to erase it, is on the "
    [:a {:href "/privacy"} "privacy page"] "."]

   [:h2 "Changes"]
   [:p "These terms may change; the date at the top says when they last did."]))

(defn denied-page [uid]
  (str
   "<!doctype html>"
   (h/html
    [:html
     [:head [:meta {:charset "utf-8"}] [:title "SaltRim — no access"]]
     [:body {:style "font-family:sans-serif;max-width:24rem;margin:14vh auto;"}
      [:h1 {:style "font-weight:600;"} "No access"]
      [:p {:style "color:#666;"}
       "This sheet doesn't exist or isn't shared with you."]
      [:p [:a {:href "/"} "Back to your sheets"]]]])))

