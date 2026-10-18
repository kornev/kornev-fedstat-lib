(ns fedstat.post-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.post :as post]))

(def sample
  {:rows
   [{:filter-field-id "0"
     :filter-field-title "Показатель"
     :filter-value-id "31074"
     :filter-value-title "Индексы потребительских цен на товары и услуги"
     :filter-field-object-ids "filterObjectIds"}
    {:filter-field-id "3"
     :filter-field-title "Год"
     :filter-value-id "2023"
     :filter-value-title "2023"
     :filter-field-object-ids "columnObjectIds"}
    {:filter-field-id "57831"
     :filter-field-title "Территория"
     :filter-value-id "1688487"
     :filter-value-title "Российская Федерация"
     :filter-field-object-ids "lineObjectIds"}
    {:filter-field-id "30611"
     :filter-field-title "Единица измерения"
     :filter-value-id "950473"
     :filter-value-title "процент"
     :filter-field-object-ids "lineObjectIds"}]
   :meta
   {:fedstat/csrf-token "tok123"
    :fedstat/csrf-token-name "token"
    :fedstat/indicator-id "31074"
    :fedstat/base-url "https://www.fedstat.ru"
    :fedstat/cookie-jar "/tmp/fedstat-test.jar"}})

(def encoded-title
  "%D0%98%D0%BD%D0%B4%D0%B5%D0%BA%D1%81%D1%8B%20%D0%BF%D0%BE%D1%82%D1%80%D0%B5%D0%B1%D0%B8%D1%82%D0%B5%D0%BB%D1%8C%D1%81%D0%BA%D0%B8%D1%85%20%D1%86%D0%B5%D0%BD%20%D0%BD%D0%B0%20%D1%82%D0%BE%D0%B2%D0%B0%D1%80%D1%8B%20%D0%B8%20%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8")

;; ---------------------------------------------------------------------------
;; url-encode (R URLencode(reserved = TRUE) semantics)

(t/deftest url-encode-test
  (t/is (= encoded-title
           (post/url-encode "Индексы потребительских цен на товары и услуги")))
  ;; R utils::URLencode(reserved = TRUE): only alphanumerics and - _ . ~
  ;; pass through, everything else is %XX (UTF-8 bytes); space -> %20
  (t/is (= "a%20b_~.-" (post/url-encode "a b_~.-")))
  (t/is (= "abcXYZ019" (post/url-encode "abcXYZ019"))))

;; ---------------------------------------------------------------------------
;; build-body (pure)

(t/deftest build-body-test
  (t/is (= (str "title=" encoded-title
                "&struts.token.name=token"
                "&token=tok123"
                "&id=31074"
                "&lineObjectIds=57831"
                "&lineObjectIds=30611"
                "&columnObjectIds=3"
                "&selectedFilterIds=0_31074"
                "&selectedFilterIds=3_2023"
                "&selectedFilterIds=57831_1688487"
                "&selectedFilterIds=30611_950473"
                "&filterObjectIds=0")
           (post/build-body sample))))

(t/deftest build-body-indicator-id-fallback-test
  ;; without :fedstat/indicator-id in meta the id is taken from the field "0" row
  (let [data-ids (update-in sample [:meta] dissoc :fedstat/indicator-id)
        body (post/build-body data-ids)]
    (t/is (str/includes? body "&id=31074&"))))

;; ---------------------------------------------------------------------------
;; post-data-ids (fake transport)

(defn- fake-transport
  "Returns [transport seen-requests] where the transport records every request
  and responds from the given queue of responses (cycled if exhausted)."
  [responses]
  (let [seen (atom [])
        responses (atom responses)]
    [(fn [req]
       (swap! seen conj req)
       (let [r (first @responses)]
         (swap! responses rest)
         (if (fn? r) (r req) r)))
     seen]))

(def ok-response
  {:status 200
   :headers {"content-type" "text/xml"}
   :body (.getBytes "<xml>data</xml>" "UTF-8")})

(t/deftest post-success-test
  (let [[transport seen] (fake-transport [ok-response])
        result (post/post-data-ids sample {:transport transport})
        req (first @seen)]
    (t/is (= "<xml>data</xml>" (String. ^bytes result "UTF-8")))
    (t/is (= :post (:method req)))
    (t/is (= "https://www.fedstat.ru/indicator/downloadData.do?format=sdmx" (:url req)))
    (t/is (= (post/build-body sample) (:body req)))
    (t/is (= "/tmp/fedstat-test.jar" (:cookie-jar req)))
    (t/is (= "application/x-www-form-urlencoded" (:content-type req)))
    (t/is (= false (:follow-redirects req)))))

(t/deftest post-excel-format-test
  (let [[transport seen] (fake-transport [(assoc ok-response
                                                 :headers {"content-type" "application/vnd.ms-excel"})])
        result (post/post-data-ids sample {:transport transport
                                           :data-format :excel})]
    (t/is (= "https://www.fedstat.ru/indicator/downloadData.do?format=excel"
             (:url (first @seen))))
    (t/is (bytes? result))))

(t/deftest post-redirect-error-test
  (let [[transport _] (fake-transport [{:status 302 :headers {} :body (byte-array 0)}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"EMISS rejected the download request \(HTTP 302 redirect\)[\s\S]*Filter combination produces no data"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-503-error-test
  (let [[transport _] (fake-transport [{:status 503
                                        :headers {}
                                        :body (.getBytes "overloaded" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"EMISS returned 503 \(Service Unavailable\)[\s\S]*Response: overloaded"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-403-error-test
  (let [[transport _] (fake-transport [{:status 403
                                        :headers {}
                                        :body (.getBytes "denied" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"EMISS returned 403 \(Forbidden\)[\s\S]*Response: denied"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-other-http-error-test
  (let [[transport _] (fake-transport [{:status 500
                                        :headers {}
                                        :body (.getBytes "boom" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"EMISS returned HTTP 500[\s\S]*Response: boom"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-csrf-body-error-test
  (let [[transport _] (fake-transport [{:status 200
                                        :headers {"content-type" "text/html"}
                                        :body (.getBytes "CSRF token mismatch" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"CSRF token validation failed[\s\S]*get-data-ids"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-html-body-error-test
  (let [[transport _] (fake-transport [{:status 200
                                        :headers {"content-type" "text/html"}
                                        :body (.getBytes "<html><body>oops</body></html>" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"HTML page instead of data[\s\S]*text/html"
           (post/post-data-ids sample {:transport transport})))))

(t/deftest post-no-data-error-test
  (let [[transport _] (fake-transport [{:status 200
                                        :headers {"content-type" "text/plain"}
                                        :body (.getBytes "nothing" "UTF-8")}])]
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No data found with specified filters[\s\S]*text/plain"
           (post/post-data-ids sample {:transport transport})))))

;; ---------------------------------------------------------------------------
;; CSRF fallback: token missing in meta -> fresh GET for the token

(def page-html
  (slurp "test/fixtures/page-31074.html"))

(t/deftest post-refetches-csrf-token-test
  (let [[transport seen] (fake-transport [(fn [_] {:status 200
                                                   :headers {"content-type" "text/html"}
                                                   :body (.getBytes page-html "UTF-8")})
                                          ok-response])
        data-ids (update sample :meta dissoc :fedstat/csrf-token)
        err (new java.io.StringWriter)
        result (binding [*err* err]
                 (post/post-data-ids data-ids {:transport transport}))]
    (t/is (= "<xml>data</xml>" (String. ^bytes result "UTF-8")))
    ;; two requests: GET for the token page, then the POST
    (t/is (= [:get :post] (map :method @seen)))
    (t/is (= "https://www.fedstat.ru/indicator/31074" (:url (first @seen))))
    ;; warning about the extra GET was printed
    (t/is (str/includes? (str err) "No CSRF token found"))
    ;; the token from the page was used in the POST body
    (t/is (str/includes? (:body (second @seen)) "struts.token.name=token&token="))))
