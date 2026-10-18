(ns fedstat.data-ids-test
  (:require
   [clojure.test :as t]
   [fedstat.data-ids :as data-ids]
   [fedstat.test-helpers :as h]))

(def built
  (let [{:keys [filters object-ids csrf]} (h/parsed-fixture)]
    (data-ids/build-data-ids filters object-ids
                             {:token-name (:token-name csrf)
                              :token (:token csrf)
                              :indicator-id "31074"
                              :base-url "https://www.fedstat.ru"
                              :cookie-jar "/tmp/fedstat-test.jar"})))

(def rows (:rows built))

(defn- find-row [fid vid]
  (first (filter #(and (= fid (:filter-field-id %))
                       (= vid (:filter-value-id %)))
                 rows)))

(t/deftest build-data-ids-shape-test
  (t/is (= 1276 (count rows)))
  (t/is (every? #(= #{:filter-field-id :filter-field-title
                      :filter-value-id :filter-value-title
                      :filter-field-object-ids}
                    (set (keys %)))
                rows)))

(t/deftest build-data-ids-rows-test
  (let [year (find-row "3" "2002")]
    (t/is (= "Год" (:filter-field-title year)))
    (t/is (= "2002" (:filter-value-title year)))
    (t/is (= "columnObjectIds" (:filter-field-object-ids year))))
  (let [indicator (find-row "0" "31074")]
    (t/is (= "Показатель" (:filter-field-title indicator)))
    (t/is (= "Индексы потребительских цен на товары и услуги"
             (:filter-value-title indicator)))
    ;; synthetic filterObjectIds "0" appended by the R logic
    (t/is (= "filterObjectIds" (:filter-field-object-ids indicator))))
  ;; js filterObjectIds (30611) is renamed to lineObjectIds, like in R
  (t/is (= "lineObjectIds" (:filter-field-object-ids (find-row "30611" "950473")))))

(t/deftest build-data-ids-unescapes-quot-test
  (t/is (some #(= "Овсяные хлопья \"Геркулес\", кг" (:filter-value-title %))
              rows)))

(t/deftest build-data-ids-meta-test
  (t/is (= {:fedstat/csrf-token-name "token"
            :fedstat/indicator-id "31074"
            :fedstat/base-url "https://www.fedstat.ru"
            :fedstat/cookie-jar "/tmp/fedstat-test.jar"}
           (dissoc (:meta built) :fedstat/csrf-token)))
  (t/is (string? (get-in built [:meta :fedstat/csrf-token])))
  (t/is (not (clojure.string/blank? (get-in built [:meta :fedstat/csrf-token])))))

(t/deftest build-data-ids-rows-sorted-by-fid-test
  ;; the R assembly merge-sorts rows by filter_field_id (data.table merge);
  ;; the port mirrors it with a stable sort so downstream ordering
  ;; (filters, POST body) matches the R original bit for bit
  (let [fids (map :filter-field-id rows)]
    (t/is (= (sort fids) fids)))
  ;; within one field the page order is preserved (stable sort)
  (let [goods-ids (map :filter-value-id (filter #(= "58273" (:filter-field-id %)) rows))
        page-order (map first (get-in (h/parsed-fixture) [:filters "58273" "values"]))]
    (t/is (= page-order goods-ids))))

(t/deftest build-data-ids-defaults-test
  (let [m (data-ids/build-data-ids
           {"5" {"title" "Т" "values" {"1" {"title" "v"}}}}
           [["lineObjectIds" ["7"]]]
           {:indicator-id "1"})]
    (t/is (= "lineObjectIds" (:filter-field-object-ids (first (:rows m)))))))

(t/deftest build-data-ids-empty-values-error-test
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no filter values in the \"Т\" filter"
         (data-ids/build-data-ids
          {"5" {"title" "Т" "values" {}}}
          []
          {:indicator-id "1"}))))
