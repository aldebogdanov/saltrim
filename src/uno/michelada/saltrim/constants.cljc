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
(def WIN-COLS 16)       ; window size (+overscan)
(def WIN-ROWS 34)
(def OVER 2)            ; overscan cells
(def MIN-COLS 26)       ; logical scroll extent never smaller than this
(def MIN-ROWS 100)
(def BUF-COLS 6)        ; extra scrollable buffer past the used/visible range
(def BUF-ROWS 30)
(def BAR 12)            ; custom scrollbar thickness px
