;; Clojure-side parity harness: replays every step of dev/verify/r_all.R
;; through the fedstat-lib port and dumps artifacts to dev/verify/out/.
;;
;; Run from the fedstat-lib directory:
;;   bb -cp src:dev/verify dev/verify/bb_all.clj
(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[fedstat.core :as core]
         '[fedstat.data-ids :as data-ids]
         '[fedstat.filters :as filters]
         '[fedstat.indicators :as indicators]
         '[fedstat.page :as page]
         '[fedstat.post :as post]
         '[fedstat.sdmx :as sdmx]
         '[fedstat.transport :as transport])
(import '[java.io ByteArrayOutputStream PrintStream PrintWriter])

(def out "dev/verify/out")

(defn write-lines!
  [path lines]
  (spit path (if (seq lines) (str (str/join "\n" lines) "\n") "")))

(defn slurp-lines [path] (str/split-lines (slurp path)))

;; ---------------------------------------------------------------------------
;; V1: url-encode vs R URLencode(reserved = TRUE)
;; ---------------------------------------------------------------------------
(let [strs (remove str/blank? (slurp-lines "dev/verify/urlencode-strings.txt"))]
  (write-lines! (str out "/bb-urlencode.txt") (map post/url-encode strs)))

;; ---------------------------------------------------------------------------
;; V3: data-ids from the R-captured page bytes (file transport), natural order
;; ---------------------------------------------------------------------------
(let [body (byte-array (java.nio.file.Files/readAllBytes
                        (.toPath (io/file (str out "/page-live.html")))))
      tr (fn [_] {:status 200 :headers {"content-type" "text/html"} :body body})
      ds (core/get-data-ids "31074" {:transport tr :sleep (fn [_])})
      cols [:filter-field-id :filter-field-title :filter-value-id
            :filter-value-title :filter-field-object-ids]]
  (write-lines! (str out "/bb-data_ids.tsv")
                (map #(str/join "\t" (map (fn [c] (str (get % c))) cols)) (:rows ds)))
  (write-lines! (str out "/bb-data_ids-attrs.txt")
                [(:fedstat/csrf-token-name (:meta ds))
                 (:fedstat/csrf-token (:meta ds))
                 (:fedstat/indicator-id (:meta ds))
                 (:fedstat/base-url (:meta ds))]))

;; CSRF via port on the same bytes
(let [html (slurp (str out "/page-live.html"))
      csrf (page/csrf-token html)]
  (write-lines! (str out "/bb-csrf.txt") [(:token-name csrf) (:token csrf)]))

;; ---------------------------------------------------------------------------
;; V2: parse-filters-js / parse-object-ids-js on the same extracted scripts
;; ---------------------------------------------------------------------------
(let [scripts (->> (file-seq (io/file (str out "/scripts")))
                   (filter #(.isFile ^java.io.File %)) (map str) sort)
      f (first (filter (fn [p]
                         (some #(str/includes? % "filters: {")
                               (str/split-lines (slurp p))))
                       scripts))
      lines (str/split-lines (slurp f))
      pj1 (page/parse-filters-js lines)
      pj2 (into (sorted-map)
                (map (fn [[cls ids]] [(str cls) (mapv str ids)]))
                (page/parse-object-ids-js lines))]
  (spit (str out "/bb-filters.json") (json/generate-string pj1 {:pretty true}))
  (spit (str out "/bb-objects.json") (json/generate-string pj2 {:pretty true})))

;; ---------------------------------------------------------------------------
;; V4: filter-data-ids on the R data_ids dump (identical input order)
;; ---------------------------------------------------------------------------
(def data-ids-r
  {:rows (mapv (fn [l]
                 (let [[fid ft vid vt obj] (str/split l #"\t" -1)]
                   {:filter-field-id fid
                    :filter-field-title ft
                    :filter-value-id vid
                    :filter-value-title vt
                    :filter-field-object-ids obj}))
               (slurp-lines (str out "/r-data_ids.tsv")))
   :meta {}})

(let [scen (json/parse-stream (io/reader "dev/verify/scenarios.json") false)
      cols [:filter-field-id :filter-field-title :filter-value-id
            :filter-value-title :filter-field-object-ids]]
  (doseq [s scen]
    (let [sid (get s "id")
          flt (into (array-map)
                    (map (fn [p] [(get p 0) (mapv str (get p 1))]))
                    (get s "filters"))
          err (volatile! nil)
          warns (ByteArrayOutputStream.)
          result (binding [*err* (PrintWriter. warns true)]
                   (try (filters/filter-data-ids data-ids-r flt {})
                        (catch Throwable t (vreset! err t) ::error)))]
      (if @err
        (write-lines! (str out "/bb-filters-" sid ".msg") ["ERROR" (ex-message @err)])
        (do (write-lines! (str out "/bb-filters-" sid ".msg") ["OK"])
            (write-lines! (str out "/bb-filters-" sid ".tsv")
                          (map #(str/join "\t" (map (fn [c] (str (get % c))) cols))
                               (:rows result)))))
      (write-lines! (str out "/bb-filters-" sid ".warn")
                    (str/split-lines (str (.toString warns "UTF-8")))))))

;; ---------------------------------------------------------------------------
;; V5: POST body via build-body on identically filtered rows
;; ---------------------------------------------------------------------------
(let [[tn tk iid base] (slurp-lines (str out "/r-data_ids-attrs.txt"))
      ds {:rows (:rows data-ids-r)
          :meta {:fedstat/csrf-token tk
                 :fedstat/csrf-token-name tn
                 :fedstat/indicator-id iid
                 :fedstat/base-url base
                 :fedstat/cookie-jar nil}}
      scen (json/parse-stream (io/reader "dev/verify/scenarios.json") false)
      body-scen (first (filter #(= "readme" (get % "id")) scen))
      flt (into (array-map)
                (map (fn [p] [(get p 0) (mapv str (get p 1))]))
                (get body-scen "filters"))
      filtered (filters/filter-data-ids ds flt {})]
  (write-lines! (str out "/bb-body.txt") [(post/build-body filtered)])
  (write-lines! (str out "/bb-post-url.txt")
                [(str base "/indicator/downloadData.do?format=sdmx")]))

;; ---------------------------------------------------------------------------
;; V6: parse-sdmx-to-table on the fixture
;; ---------------------------------------------------------------------------
(defn fmt-num [x]
  (if (nil? x)
    ""
    (let [s (format "%.12g" (double x))]
      (if (str/includes? s ".")
        (str/replace s #"0+$" "")
        s))))

(defn fmt-cell [v]
  (cond
    (nil? v) ""
    (number? v) (let [s (fmt-num v)] (if (str/ends-with? s ".") (subs s 0 (dec (count s))) s))
    :else (str v)))

(defn dump-table! [rows path]
  (let [header (->> rows (mapcat keys) distinct (map str) sort vec)
        sorted (sort (fn [a b]
                       (compare (mapv #(fmt-cell (get a %)) header)
                                (mapv #(fmt-cell (get b %)) header)))
                     rows)]
    (write-lines! path
                  (into [(str/join "\t" header)]
                        (map #(str/join "\t" (map (fn [h] (fmt-cell (get % h))) header)))
                        sorted))))

(let [raw (byte-array (java.nio.file.Files/readAllBytes
                       (.toPath (io/file "test/fixtures/sdmx-31074.xml"))))
      tab (sdmx/parse-sdmx-to-table raw {:return-type :data})
      dict (sdmx/parse-sdmx-to-table raw {:return-type :dictionary})]
  (dump-table! tab (str out "/bb-sdmx-data.tsv"))
  (dump-table! dict (str out "/bb-sdmx-dict.tsv"))
  (write-lines! (str out "/bb-sdmx-meta.txt")
                [(str "rows " (count tab))
                 (str "dict_rows " (count dict))
                 (str "cols " (str/join "," (sort (map str (distinct (mapcat keys tab))))))]))

;; ---------------------------------------------------------------------------
;; V7: indicator-info — live fetch via curl transport, parse via port
;; ---------------------------------------------------------------------------
(let [resp (transport/default-transport
            {:method :get
             :url "https://www.fedstat.ru/organizations/"
             :timeout-seconds 180})
      rows (indicators/parse-indicators (String. ^bytes (:body resp) "UTF-8"))]
  (write-lines! (str out "/bb-indicators.tsv")
                (map #(str/join "\t" (map (fn [c] (str (get % c)))
                                          [:department :group :id :title]))
                     rows))
  (write-lines! (str out "/bb-indicators-meta.txt") [(str "rows " (count rows))]))

(println "bb harness done")
