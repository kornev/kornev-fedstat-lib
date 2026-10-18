(ns fedstat.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.core :as core]))

(def page-html (slurp "test/fixtures/page-31074.html"))
(def sdmx-bytes (.getBytes (slurp "test/fixtures/sdmx-31074.xml") "UTF-8"))

(defn- fake-transport
  "Records requests, responds from the given queue; a queue entry can be a
  response map or a fn of the request (to raise or compute a response).
  The last entry is reused once the queue is exhausted."
  [responses]
  (let [seen (atom [])
        queue (atom responses)
        last-r (volatile! nil)]
    [(fn [req]
       (swap! seen conj req)
       (when (seq @queue)
         (vreset! last-r (first @queue))
         (swap! queue rest))
       (let [r @last-r]
         (if (fn? r) (r req) r)))
     seen]))

(def ok-get {:status 200
             :headers {"content-type" "text/html"}
             :body (.getBytes page-html "UTF-8")})

(def ok-post {:status 200
              :headers {"content-type" "text/xml"}
              :body sdmx-bytes})

(def base-filters
  {"Год" ["2023"]
   "Период" ["январь"]
   "Виды показателя" ["К предыдущему месяцу"]
   "Виды товаров и услуг" ["Все товары и услуги"]
   "Классификатор объектов административно-территориального деления (ОКАТО)"
   ["Российская Федерация"]})

;; ---------------------------------------------------------------------------
;; get-data-ids

(t/deftest get-data-ids-test
  (let [[transport seen] (fake-transport [ok-get])
        result (core/get-data-ids "31074" {:transport transport
                                           :cookie-jar "/tmp/fedstat-test.jar"})
        req (first @seen)]
    (t/is (= :get (:method req)))
    (t/is (= "https://www.fedstat.ru/indicator/31074" (:url req)))
    (t/is (= 180 (:timeout-seconds req)))
    (t/is (= "/tmp/fedstat-test.jar" (:cookie-jar req)))
    (t/is (contains? (:headers req) "User-Agent"))
    (t/is (= 1276 (count (:rows result))))
    (t/is (= "31074" (get-in result [:meta :fedstat/indicator-id])))
    (t/is (string? (get-in result [:meta :fedstat/csrf-token])))
    (t/is (= "token" (get-in result [:meta :fedstat/csrf-token-name])))))

(t/deftest get-data-ids-retry-test
  (let [sleeps (atom [])
        [transport seen] (fake-transport [(fn [_] (throw (ex-info "connection refused" {})))
                                          ok-get])
        result (core/get-data-ids "31074" {:transport transport
                                           :cookie-jar "/tmp/fedstat-test.jar"
                                           :sleep (fn [seconds] (swap! sleeps conj seconds))})]
    (t/is (= 1276 (count (:rows result))))
    (t/is (= 2 (count @seen)))
    ;; growing pauses between attempts (1s, 2s, 4s ... capped at 60)
    (t/is (= [1] @sleeps))))

(t/deftest get-data-ids-exhausted-retries-test
  (let [sleeps (atom [])
        [transport seen] (fake-transport [(fn [_] (throw (ex-info "boom" {})))])
        thrown (try (core/get-data-ids "31074" {:transport transport
                                                :cookie-jar "/tmp/fedstat-test.jar"
                                                :retry-max-times 3
                                                :sleep (fn [seconds] (swap! sleeps conj seconds))})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (t/is (some? thrown))
    (t/is (str/includes? (ex-message thrown) "boom"))
    (t/is (= 3 (count @seen)))
    (t/is (= [1 2] @sleeps))))

(t/deftest get-data-ids-503-test
  (let [[transport seen] (fake-transport [(fn [_] {:status 503 :headers {} :body (byte-array 0)})])
        thrown (try (core/get-data-ids "31074" {:transport transport
                                                :cookie-jar "/tmp/fedstat-test.jar"
                                                :sleep (fn [_])})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (t/is (some? thrown))
    (t/is (str/includes? (ex-message thrown) "EMISS returned 503 (Service Unavailable)"))))

(t/deftest get-data-ids-403-test
  (let [[transport _] (fake-transport [{:status 403 :headers {} :body (byte-array 0)}])
        thrown (try (core/get-data-ids "31074" {:transport transport
                                                :cookie-jar "/tmp/fedstat-test.jar"
                                                :sleep (fn [_])})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (t/is (some? thrown))
    (t/is (str/includes? (ex-message thrown) "EMISS returned 403 (Forbidden)"))))

;; ---------------------------------------------------------------------------
;; load-with-filters

(t/deftest load-with-filters-test
  (let [[transport seen] (fake-transport [ok-get ok-post])
        out (new java.io.StringWriter)
        result (binding [*out* out]
                 (core/load-with-filters "31074"
                                         {:transport transport
                                          :filters base-filters
                                          :cookie-jar "/tmp/fedstat-test.jar"}))]
    (t/is (= 1489 (count result)))
    (t/is (= [:get :post] (map :method @seen)))
    ;; the filtered selection (7 rows) was posted
    (t/is (= 7 (count (re-seq #"selectedFilterIds=" (:body (second @seen))))))
    ;; verbose steps were printed
    (t/is (str/includes? (str out) "Downloading EMISS internal identificators"))
    (t/is (str/includes? (str out) "Downloading data from EMISS"))
    (t/is (str/includes? (str out) "Done"))))

(t/deftest load-with-filters-dictionary-test
  (let [[transport _] (fake-transport [ok-get ok-post])
        result (core/load-with-filters "31074"
                                       {:transport transport
                                        :filters base-filters
                                        :cookie-jar "/tmp/fedstat-test.jar"
                                        :return-type :dictionary
                                        :loading-steps-verbose? false})]
    (t/is (= 1233 (count result)))
    (t/is (= #{"s_POK" "s_grtov" "s_OKATO"} (set (map :field-id result))))))

(t/deftest load-with-filters-get-retry-test
  ;; GET fails once, then the whole flow succeeds (fresh CSRF on retry)
  (let [[transport seen] (fake-transport [(fn [_] (throw (ex-info "lagging" {})))
                                          ok-get
                                          ok-post])
        result (core/load-with-filters "31074"
                                       {:transport transport
                                        :filters base-filters
                                        :cookie-jar "/tmp/fedstat-test.jar"
                                        :loading-steps-verbose? false
                                        :sleep (fn [_])})]
    (t/is (= 1489 (count result)))
    (t/is (= [:get :get :post] (map :method @seen)))))

(t/deftest load-with-filters-post-retry-test
  ;; GET succeeds, POST fails once: the whole cycle repeats with a fresh GET
  ;; (CSRF tokens are single-use), then succeeds
  (let [[transport seen] (fake-transport [ok-get
                                          {:status 503 :headers {} :body (byte-array 0)}
                                          ok-get
                                          ok-post])
        result (core/load-with-filters "31074"
                                       {:transport transport
                                        :filters base-filters
                                        :cookie-jar "/tmp/fedstat-test.jar"
                                        :loading-steps-verbose? false
                                        :sleep (fn [_])})]
    (t/is (= 1489 (count result)))
    (t/is (= [:get :post :get :post] (map :method @seen)))))

(t/deftest load-with-filters-exhausted-test
  (let [[transport seen] (fake-transport [(fn [_] (throw (ex-info "always down" {})))])
        thrown (try (core/load-with-filters "31074"
                                            {:transport transport
                                             :filters base-filters
                                             :cookie-jar "/tmp/fedstat-test.jar"
                                             :retry-max-times 2
                                             :loading-steps-verbose? false
                                             :sleep (fn [_])})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (t/is (some? thrown))
    (t/is (str/includes? (ex-message thrown) "always down"))
    ;; retries nest like in the R original: the wrapper loop (2) times the
    ;; internal GET retries (2) = up to 4 GET attempts
    (t/is (= 4 (count @seen)))))
