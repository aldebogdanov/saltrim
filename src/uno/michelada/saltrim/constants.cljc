(ns uno.michelada.saltrim.constants
  "Grid geometry shared by the server renderer (web.clj) and the browser
   logical-scroll engine (app.cljs) — one source of truth so client and server
   agree on cell sizes, window size, overscan and scrollbar thickness.")

;; --- geometry -----------------------------------------------------------

(def CW 112)            ; cell width  px
(def RH 26)             ; cell height px
(def GUT 48)            ; row-header gutter px
(def HDR 26)            ; col-header height px
;; Logical grid caps. With the giant spacer gone (logical scrollbars need no huge
;; DOM element) these are NO LONGER a DOM-element-size ceiling — they're just a
;; sanity clamp on jumps/scroll, sized to a familiar spreadsheet's grid so
;; column letters + row numbers stay bounded: 16384 cols = XFD, 1048576 rows.
(def MAX-COLS 16384)
(def MAX-ROWS 1048576)
;; Rendered-window span, as a cell COUNT AT THE DEFAULT CELL SIZE (CW/RH). The
;; window is really a PX budget: a sheet whose default column is half as wide
;; needs twice as many columns to cover the same viewport, so the server scales
;; these by the sheet's own dcw/drh, and the client (which alone knows the real
;; viewport) reports what it actually needs as $wc/$wr. Never render a fixed
;; count — that is what left the right of the grid empty at a small default
;; width. Both sides walk the REAL per-index sizes: a run of hand-shrunk columns
;; fits more of them on screen than dcw alone suggests.
(def WIN-COLS 16)
(def WIN-ROWS 34)
(def MINSZ 24)          ; smallest column width / row height a resize drag allows
;; MAX-WIN-* caps what a client may ask us to render in one go. Sized so the cap
;; can't bind in practice — even a 4K viewport of MINSZ cells (3840/24 = 160
;; columns, 2160/24 = 90 rows) stays under it, so it only ever stops a buggy or
;; hostile client from asking for the whole grid.
(def MAX-WIN-COLS 192)
(def MAX-WIN-ROWS 128)
(def OVER 2)            ; overscan cells (each side of the window)
(def MIN-COLS 26)       ; logical scroll extent never smaller than this
(def MIN-ROWS 100)
(def BUF-COLS 6)        ; extra scrollable buffer past the used/visible range
(def BUF-ROWS 30)
(def BAR 12)            ; custom scrollbar thickness px
;; Largest rectangle a single merge may swallow (rows*cols). A sanity clamp: a
;; merge is presentational, but the covered set is materialized, so cap it well
;; below anything a user would want to merge in one gesture.
(def MAX-MERGE-CELLS 4096)
