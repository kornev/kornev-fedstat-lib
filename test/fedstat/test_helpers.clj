(ns fedstat.test-helpers
  "Shared fixture helpers."
  (:require
   [clojure.string :as str]
   [fedstat.page :as page]))

(defn page-html []
  (slurp "test/fixtures/page-31074.html"))

(defn filters-script-lines []
  (-> (page-html)
      (page/script-nodes)
      (nth 11)
      (str/split-lines)))

(defn parsed-fixture []
  (let [lines (filters-script-lines)]
    {:filters (page/parse-filters-js lines)
     :object-ids (page/parse-object-ids-js lines)
     :csrf (page/csrf-token (page-html))}))
