(ns uno.michelada.saltrim.bench.main
  "Run the engine benchmarks and print a markdown report.

     clojure -M:bench                 # default sizes
     clojure -M:bench 200 1000        # explicit sizes
     clojure -M:bench --shapes chain,star 1000

   Every number is a median over 5 runs after 2 warmups, on a sheet built fresh
   for each run. Numbers are only comparable against other numbers from the same
   machine — commit them to `doc/bench.md` with the machine noted, and compare
   RATIOS across changes rather than absolutes."
  (:require [clojure.string :as str]
            [uno.michelada.saltrim.addr :as addr]
            [uno.michelada.saltrim.bench.runner :as r]
            [uno.michelada.saltrim.bench.shapes :as shapes]
            [uno.michelada.saltrim.sheet :as sheet]
            [uno.michelada.saltrim.version :as version]))

(def default-sizes [100 1000])

(defn- run-shape
  "Measure one shape at one size. Returns a row map."
  [shape-name n opts]
  (let [[builder root new-val] (get shapes/all shape-name)
        cells   (builder n)
        addrs   (mapv first cells)
        ;; build and load are measured on a throwaway sheet each run; edit and
        ;; read need a built sheet, so they get one outside the measurement.
        build   (r/measure opts #(r/build! cells))
        load    (r/measure opts #(r/load! cells))
        ;; edit/read need a built sheet. If the shape cannot even be built at
        ;; this size, say so once and skip the rest rather than reporting a
        ;; misleading zero.
        built   (try (r/build! cells) (catch Throwable _ nil))
        edit    (if-not built
                  {:failed "not built"}
                  (r/measure opts
                             (let [i (volatile! 0)]
                               ;; alternate the written value so every iteration is
                               ;; a real change — writing the same value again may
                               ;; legitimately do nothing
                               (fn [] (vswap! i inc)
                                 (sheet/set-cell! built root (str new-val @i))
                                 (sheet/settle! built)))))
        read    (if-not built
                  {:failed "not built"}
                  (r/measure opts #(r/read-all built addrs)))]
    {:shape shape-name :n n :cells (count cells)
     :build build :load load :edit edit :read read}))

(defn- rows [results]
  (for [{:keys [shape n cells build load edit read]} results]
    [(name shape) (str n) (str cells)
     (r/fmt-ms build) (r/fmt-ms load) (r/fmt-ms edit) (r/fmt-ms read)
     (if (map? build) "—" (format "%.2fms" (/ build cells)))]))

(defn- sanity!
  "A benchmark that measures the wrong thing is worse than none. Before timing
   anything, assert each shape actually computed — a formula that silently
   errored would look like a very fast engine."
  []
  (doseq [[shape [builder]] shapes/all]
    (let [cells (builder 20)
          s     (r/build! cells)
          bad   (keep (fn [[a _]] (let [v (sheet/value s a)]
                                    (when (and (map? v) (:error v)) [a v])))
                      cells)]
      (when (seq bad)
        (throw (ex-info (str "shape " shape " has failing cells — benchmark would be meaningless")
                        {:shape shape :failures (take 3 bad)}))))))

(defn -main [& args]
  (let [[flags args] ((juxt filter remove) #(str/starts-with? % "--") args)
        wanted (if-let [f (some #(when (str/starts-with? % "--shapes") %) flags)]
                 (let [names (set (str/split (subs f (inc (str/index-of f "="))) #","))]
                   (filterv #(names (name %)) (keys shapes/all)))
                 (vec (keys shapes/all)))
        sizes  (if (seq args) (mapv parse-long args) default-sizes)
        opts   {:runs 5 :warmup 2}]
    (println "SaltRim engine benchmarks —" (version/current))
    (println (str "JVM " (System/getProperty "java.version")
                  " · " (System/getProperty "os.name")
                  " · " (.availableProcessors (Runtime/getRuntime)) " cores"))
    (println)
    (print "sanity check… ") (flush)
    (sanity!)
    (println "ok")
    (println)
    (let [results (doall (for [n sizes, shape wanted]
                           (do (print (str "  " (name shape) " " n " … ")) (flush)
                               (let [row (run-shape shape n opts)]
                                 (println "done") row))))]
      (println)
      (println (r/table ["shape" "n" "cells" "build" "load" "edit" "read all" "build/cell"]
                        (rows results)))
      (println)
      (println "build = set-cell! per cell + settle (what typing/importing costs)")
      (println "load  = load-document! bulk path + settle (what opening a sheet costs)")
      (println "edit  = one write to the root cell + settle (what a collaborator waits for)")
      (println "read  = sheet/value over every cell")
      (doseq [{:keys [shape n build]} results
              :when (map? build)]
        (println (format "\nFAILED  %s n=%d — %s" (name shape) n (:failed build))))))
  (shutdown-agents))
