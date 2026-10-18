(ns fedstat.sdmx-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.sdmx :as sdmx]))

(def fixture-bytes
  (.getBytes (slurp "test/fixtures/sdmx-31074.xml") "UTF-8"))

;; ---------------------------------------------------------------------------
;; Data

(t/deftest parse-data-shape-test
  (let [rows (sdmx/parse-sdmx-to-table fixture-bytes {})]
    (t/is (= 1489 (count rows)))
    (t/is (every? #(= #{"s_grtov" "s_grtov_code" "s_OKATO" "s_OKATO_code"
                        "s_POK" "s_POK_code" "EI" "PERIOD" "Time" "ObsValue"}
                      (set (keys %)))
                  rows))))

(t/deftest parse-data-lookup-join-test
  ;; value ids are replaced by codelist titles, original ids stay in *_code
  (let [rows (sdmx/parse-sdmx-to-table fixture-bytes {})
        row (first (filter #(and (= "Все товары и услуги" (get % "s_grtov"))
                                 (= "Приморский край" (get % "s_OKATO"))
                                 (= "К предыдущему месяцу" (get % "s_POK")))
                           rows))]
    (t/is (some? row))
    (t/is (= "1" (get row "s_grtov_code")))
    (t/is (= "05000000000" (get row "s_OKATO_code")))
    (t/is (= "44" (get row "s_POK_code")))
    ;; attributes without codelists pass through untouched
    (t/is (= "процент" (get row "EI")))
    (t/is (= "январь" (get row "PERIOD")))
    (t/is (= "2023" (get row "Time")))))

(t/deftest parse-obs-value-test
  (let [rows (sdmx/parse-sdmx-to-table fixture-bytes {})]
    (t/is (every? #(instance? Double (get % "ObsValue")) rows))
    (t/is (some #(= 100.83 (get % "ObsValue")) rows))))

(t/deftest parse-obs-value-as-string-test
  (let [rows (sdmx/parse-sdmx-to-table fixture-bytes {:try-to-parse-obs-value? false})]
    (t/is (every? #(string? (get % "ObsValue")) rows))
    (t/is (some #(= "100,83" (get % "ObsValue")) rows))))

;; ---------------------------------------------------------------------------
;; Dictionary

(t/deftest parse-dictionary-test
  (let [dict (sdmx/parse-sdmx-to-table fixture-bytes {:return-type :dictionary})]
    (t/is (= 1233 (count dict)))
    (t/is (= #{"s_POK" "s_grtov" "s_OKATO"} (set (map :field-id dict))))
    (t/is (some #(= {:field-id "s_POK"
                     :field-title "Виды показателя"
                     :value-id "44"
                     :value-title "К предыдущему месяцу"}
                    %)
                dict))
    (t/is (some #(= {:field-id "s_OKATO"
                     :field-title "Классификатор объектов административно-территориального деления (ОКАТО)"
                     :value-id "05000000000"
                     :value-title "Приморский край"}
                    %)
                dict))))

;; ---------------------------------------------------------------------------
;; HTML and nil input (R warns and returns NULL)

(t/deftest parse-html-csrf-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"HTML CSRF error page instead of SDMX data[\s\S]*fresh token"
         (sdmx/parse-sdmx-to-table (.getBytes "<!DOCTYPE html><html><body>CSRF error</body></html>"
                                              "UTF-8")
                                   {}))))

(t/deftest parse-html-page-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"HTML page instead of SDMX data[\s\S]*Preview:"
         (sdmx/parse-sdmx-to-table (.getBytes "<html><body>oops</body></html>" "UTF-8")
                                   {}))))

(t/deftest parse-string-input-warns-and-returns-nil-test
  (let [err (new java.io.StringWriter)
        result (binding [*err* err]
                 (sdmx/parse-sdmx-to-table "not raw bytes" {}))]
    (t/is (nil? result))
    (t/is (str/includes? (str err) "not raw"))))

(t/deftest parse-nil-input-warns-and-returns-nil-test
  (let [err (new java.io.StringWriter)
        result (binding [*err* err]
                 (sdmx/parse-sdmx-to-table nil {}))]
    (t/is (nil? result))
    (t/is (str/includes? (str err) "not raw"))))

;; ---------------------------------------------------------------------------
;; Synthetic SDMX edge cases

(def sdmx-with-missing-column
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<GenericData xmlns=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/message\">"
       "<CodeLists>"
       "<structure:CodeList xmlns:structure=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/structure\" id=\"s_X\">"
       "<structure:Name xml:lang=\"ru\">X</structure:Name>"
       "<structure:Code value=\"1\">"
       "<structure:Description xml:lang=\"ru\">one</structure:Description>"
       "</structure:Code>"
       "</structure:CodeList>"
       "</CodeLists>"
       "<DataSet>"
       "<generic:Series xmlns:generic=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/generic\">"
       "<generic:SeriesKey><generic:Value concept=\"s_grtov\" value=\"1\"/></generic:SeriesKey>"
       "<generic:Obs><generic:Time>2023</generic:Time>"
       "<generic:ObsValue value=\"5,5\"/></generic:Obs>"
       "</generic:Series>"
       "</DataSet>"
       "</GenericData>"))

(t/deftest parse-missing-column-error-test
  ;; every codelist field id must have a data column, like the R setdiff check
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Expected columns not found in data: s_X"
         (sdmx/parse-sdmx-to-table (.getBytes sdmx-with-missing-column "UTF-8") {}))))

(def sdmx-with-na-lookup
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<GenericData xmlns=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/message\">"
       "<CodeLists>"
       "<structure:CodeList xmlns:structure=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/structure\" id=\"s_X\">"
       "<structure:Name xml:lang=\"ru\">X</structure:Name>"
       "<structure:Code value=\"1\"/>"
       "</structure:CodeList>"
       "</CodeLists>"
       "<DataSet>"
       "<generic:Series xmlns:generic=\"http://www.SDMX.org/resources/SDMXML/schemas/v1_0/generic\">"
       "<generic:SeriesKey><generic:Value concept=\"s_X\" value=\"1\"/></generic:SeriesKey>"
       "<generic:Obs><generic:Time>2023</generic:Time>"
       "<generic:ObsValue value=\"5\"/></generic:Obs>"
       "</generic:Series>"
       "</DataSet>"
       "</GenericData>"))

(t/deftest parse-na-in-lookup-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"NA in lookup sdmx table"
         (sdmx/parse-sdmx-to-table (.getBytes sdmx-with-na-lookup "UTF-8") {}))))
