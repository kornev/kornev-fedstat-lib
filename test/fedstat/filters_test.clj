(ns fedstat.filters-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.data-ids :as data-ids]
   [fedstat.filters :as filters]
   [fedstat.test-helpers :as h]))

(def built
  (let [{parsed-filters :filters object-ids :object-ids csrf :csrf} (h/parsed-fixture)]
    (data-ids/build-data-ids parsed-filters object-ids
                             {:token-name (:token-name csrf)
                              :token (:token csrf)
                              :indicator-id "31074"
                              :base-url "https://www.fedstat.ru"
                              :cookie-jar "/tmp/fedstat-test.jar"})))

(def rows (:rows built))

(def base-filters
  {"Год" ["2023"]
   "Период" ["январь"]
   "Виды показателя" ["К предыдущему месяцу"]
   "Виды товаров и услуг" ["Все товары и услуги"]
   "Классификатор объектов административно-территориального деления (ОКАТО)"
   ["Российская Федерация"]})

;; ---------------------------------------------------------------------------
;; Basic filtering

(t/deftest filter-all-fields-test
  (let [result (filters/filter-data-ids built base-filters {})
        fids (set (map :filter-field-id (:rows result)))]
    (t/is (= #{"0" "3" "57937" "58273" "57831" "30611" "33560"} fids))
    ;; 5 explicitly filtered fields (1 row each) + auto-added single-value
    ;; fields Показатель and Единица измерения = 7 rows over 7 fields
    (t/is (= 7 (count (:rows result))))
    (t/is (= (:meta built) (:meta result)))))

(t/deftest filter-preserves-row-keys-test
  (let [result (filters/filter-data-ids built base-filters {})
        key-order (keys (first rows))]
    (t/is (every? #(= key-order (keys %)) (:rows result)))))

(t/deftest filter-scalar-value-test
  ;; a single string (not a vector) is accepted as a one-value filter
  (let [result (filters/filter-data-ids built
                                        (assoc base-filters "Период" "январь")
                                        {})]
    (t/is (= 7 (count (:rows result))))))

(t/deftest filter-multiple-values-test
  (let [result (filters/filter-data-ids built
                                        (assoc base-filters "Год" ["2022" "2023"])
                                        {})
        years (filter #(= "3" (:filter-field-id %)) (:rows result))]
    (t/is (= #{"2022" "2023"} (set (map :filter-value-title years))))))

(t/deftest filter-normalization-test
  ;; str_norm: lowercase, collapse whitespace, trim - on both sides
  (let [result (filters/filter-data-ids built
                                        (assoc base-filters
                                               "год" ["  2023 "]
                                               "Период" ["ЯНВАРЬ"])
                                        {})]
    (t/is (= 7 (count (:rows result))))))

(t/deftest filter-wildcard-test
  (let [result (filters/filter-data-ids built
                                        (assoc base-filters
                                               "Виды товаров и услуг" "*")
                                        {})
        goods (filter #(= "58273" (:filter-field-id %)) (:rows result))]
    (t/is (= 1105 (count goods)))))

;; ---------------------------------------------------------------------------
;; Auto-added filters

(t/deftest filter-auto-adds-single-value-fields-test
  ;; Единица измерения (single value) is auto-added even when not requested
  (let [result (filters/filter-data-ids built base-filters {})
        units (filter #(= "30611" (:filter-field-id %)) (:rows result))]
    (t/is (= 1 (count units)))
    (t/is (= "процент" (:filter-value-title (first units))))))

(t/deftest filter-auto-adds-indicator-test
  ;; Показатель is auto-added from the indicator title (field id "0")
  (let [result (filters/filter-data-ids built base-filters {})
        indicator (filter #(= "0" (:filter-field-id %)) (:rows result))]
    (t/is (= 1 (count indicator)))
    (t/is (= "Индексы потребительских цен на товары и услуги"
             (:filter-value-title (first indicator))))))

;; ---------------------------------------------------------------------------
;; Warnings

(defn- filter-with-err [data-ids f opts]
  (let [err (new java.io.StringWriter)]
    (binding [*err* err]
      (filters/filter-data-ids data-ids f opts))
    (str err)))

(t/deftest filter-warns-on-unmatched-values-test
  (let [err (filter-with-err built
                             (assoc base-filters "Год" ["2023" "1999"])
                             {})]
    (t/is (str/includes? err "Год"))
    (t/is (str/includes? err "1999"))
    (t/is (str/includes? err "The data will not be filtered by them"))
    ;; the matched 2023 row is still returned
    (let [result (binding [*err* (new java.io.StringWriter)]
                   (filters/filter-data-ids built
                                            (assoc base-filters "Год" ["2023" "1999"])
                                            {}))]
      (t/is (= 7 (count (:rows result)))))))

(t/deftest filter-disable-warnings-test
  (let [err (filter-with-err built
                             (assoc base-filters "Год" ["2023" "1999"])
                             {:disable-warnings? true})]
    (t/is (str/blank? err))))

;; ---------------------------------------------------------------------------
;; Errors

(t/deftest filter-unknown-field-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"incorrectly or do not exist: Несуществующее поле"
         (filters/filter-data-ids built
                                  (assoc base-filters "Несуществующее поле" "x")
                                  {}))))

(t/deftest filter-missing-multi-value-field-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be explicitly specified in filters:([\s\S]*?)Период"
         (filters/filter-data-ids built
                                  (dissoc base-filters "Период")
                                  {}))))

(t/deftest filter-wildcard-in-vector-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"filter vector of length greater than 1"
         (filters/filter-data-ids built
                                  (assoc base-filters "Год" ["2023" "*"])
                                  {}))))

(t/deftest filter-no-values-found-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No filter values were found for filters fields: Год"
         (filters/filter-data-ids built
                                  (assoc base-filters "Год" ["1999"])
                                  {:disable-warnings? true}))))

;; ---------------------------------------------------------------------------
;; Parity with the R original (verified against fedstatAPIr 1.1.0, dev/verify/)

(t/deftest filter-output-order-test
  ;; R sorts user-specified multi-value fields alphabetically by normalized
  ;; field title (data.table merge side effect), then appends auto-added
  ;; single-value fields in filter_field_id order
  (let [result (filters/filter-data-ids built base-filters {})
        fids (map :filter-field-id (:rows result))]
    (t/is (= ["57937" "58273" "3" "57831" "33560" "0" "30611"]
             (distinct fids)))))

(t/deftest filter-star-must-be-literal-test
  ;; R compares the raw filter value to "*"; " * " is NOT a wildcard - it
  ;; matches nothing and errors, exactly like any unknown value
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"No filter values were found for filters fields: Год"
         (filters/filter-data-ids built
                                  (assoc base-filters "Год" [" * "])
                                  {:disable-warnings? true}))))

(t/deftest filter-missing-multi-r-error-text-test
  ;; error texts mirror the R originals verbatim (including R syntax in the
  ;; usage hint), verified against fedstatAPIr 1.1.0
  (try
    (filters/filter-data-ids built (dissoc base-filters "Период") {})
    (t/is false "expected an error")
    (catch clojure.lang.ExceptionInfo e
      (t/is (str/includes? (ex-message e)
                           "Example: list(\"Период\" = \"*\")"))
      (t/is (str/includes? (ex-message e)
                           "inspect the data_ids table from fedstat_get_data_ids()")))))
