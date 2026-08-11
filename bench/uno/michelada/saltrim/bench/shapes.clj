(ns uno.michelada.saltrim.bench.shapes
  "Workbook shapes the benchmarks run against.

   Each shape stresses a different part of the reactive engine, and each is a
   pure description — `[[addr raw] …]` — so the harness decides when the clock
   starts and whether cells go in one at a time (what a user does) or in bulk
   (what an import or a sheet load does).

     :chain      A1 = 0, A_i = (inc A_i-1)      one long dependency chain;
                 an edit at the root has to walk the whole depth
     :wide       B_i = (* A_i 2)                N independent one-hop formulas;
                 an edit touches exactly one dependent
     :aggregate  B1 = (sum A1:AN)               one formula awaiting N cells —
                 the range-expansion and re-await cost
     :star       B_i = (+ A1 i)                 N dependents on ONE cell; an edit
                 fans out to all of them at once
     :dyn        B_i = $(str \"A\" i)             dynamic refs, whose targets are
                 resolved at run time — and which `set-cell!` structurally
                 rebuilds on ANY edit (see the dynref note in CLAUDE.md), so
                 this is where that cost shows up
     :xl         B_i = (pmt …) over A_i          a borrowed Excel function per
                 cell, to price the adapter inside real reactive work

   The shape vocabulary is borrowed from rechentafel's bench suite (see
   `doc/rechentafel-evaluation.md`); the measurements are ours, because a
   push-based Spin graph and a pull-based dirty/topo recalc fail in completely
   different places."
  (:require [uno.michelada.saltrim.addr :as addr]))

(defn- col [ci ri] (addr/make ci ri))

(defn chain [n]
  (into [["A1" "0"]]
        (for [i (range 1 n)]
          [(col 0 i) (str "=(inc $A" i ")")])))

(defn wide [n]
  (into (vec (for [i (range n)] [(col 0 i) (str i)]))
        (for [i (range n)]
          [(col 1 i) (str "=(* $A" (inc i) " 2)")])))

(defn aggregate [n]
  (conj (vec (for [i (range n)] [(col 0 i) (str i)]))
        ["B1" (str "=(sum $A1:A" n ")")]))

(defn star [n]
  (into [["A1" "1"]]
        (for [i (range n)]
          [(col 1 i) (str "=(+ $A1 " i ")")])))

(defn dyn [n]
  (into (vec (for [i (range n)] [(col 0 i) (str i)]))
        (for [i (range n)]
          [(col 1 i) (str "=$(str \"A\" " (inc i) ")")])))

(defn xl [n]
  (into (vec (for [i (range n)] [(col 0 i) "0.08"]))
        (for [i (range n)]
          [(col 1 i) (str "=(pmt $A" (inc i) " 10 -1000)")])))

(def all
  "Shape name -> [builder root-cell edited-value], where `root` is the cell an
   edit benchmark changes: the one every other cell in the shape ultimately
   depends on."
  {:chain     [chain     "A1" "1"]
   :wide      [wide      "A1" "99"]
   :aggregate [aggregate "A1" "99"]
   :star      [star      "A1" "2"]
   :dyn       [dyn       "A1" "99"]
   :xl        [xl        "A1" "0.09"]})
