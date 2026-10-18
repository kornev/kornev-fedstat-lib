(ns fedstat.test-runner
  "Runs every fedstat.*-test namespace found under test/ in deterministic
  order and exits non-zero on any failure or error.

  Uses only Clojure standard library so it works both under babashka
  (`bb run test`, `bb -m fedstat.test-runner`) and under the JVM
  (`clojure -M:test`)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- test-namespaces
  "All *_test.clj namespaces under test/, sorted."
  []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile ^java.io.File %))
       (map str)
       (filter #(str/ends-with? % "_test.clj"))
       (map (fn [path]
              (-> path
                  (str/replace #"^test/" "")
                  (str/replace #"/" ".")
                  (str/replace #"\.clj$" "")
                  (str/replace #"_" "-")
                  symbol)))
       (sort)
       (distinct)))

(defn run
  "Require and run all test namespaces. Returns 0 on success, 1 otherwise."
  []
  (let [namespaces (test-namespaces)]
    (println "Running tests in:" (str/join ", " (map str namespaces)))
    (doseq [ns-sym namespaces]
      (require ns-sym))
    (let [{:keys [test pass fail error] :as _summary} (apply t/run-tests namespaces)]
      (println (format "Ran %d tests: %d passed, %d failed, %d errors."
                       test pass fail error))
      (if (zero? (+ fail error)) 0 1))))

(defn -main [& _]
  (System/exit (run)))
