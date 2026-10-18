(ns fedstat.post
  "Posting filtered data-ids to fedstat.ru and downloading the data payload.

  Port of fedstat_post_data_ids_filtered.R and fedstat_config.R (fedstatAPIr).
  The transport is an injectable fn of one request map
  {:method :get|:post :url ...} -> {:status :headers :body} so tests stay
  offline; fedstat.transport provides the real curl-backed implementation.

  Live-site note (2026-10-14, found empirically): EMISS sits behind a WAF.
  The indicator page GET succeeds with the default honest User-Agent, but the
  downloadData.do POST may be rejected with HTTP 302 to /indicator/<id>.do
  (server-side reject, exactly the situation the R original documents for
  302) or, once the WAF flags the client, with HTTP 403 'Request ID' pages.
  This is server-side anti-bot behavior, not a request-shape problem: bodies
  byte-identical to the R construction (and to the site's own FGrid.js
  downloadFile) were tried, with and without Referer/Origin, with %20 and
  +-encoded titles, and with both honest and browser User-Agents. The R
  package's README recommends custom browser-like headers for exactly this
  situation; override :headers in the transport if needed."
  (:require
   [clojure.string :as str]
   [fedstat.page :as page]))

(def default-headers
  "Port of fedstat_default_config() in fedstatAPIr (default request headers)."
  {"User-Agent" "fedstat-lib (Clojure port of fedstatAPIr; https://github.com/DenchPokepon/fedstatAPIr)"
   "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
   "Accept-Language" "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
   "Connection" "keep-alive"})

;; ---------------------------------------------------------------------------
;; URL encoding

(defn url-encode
  "Port of utils::URLencode(reserved = TRUE): only [A-Za-z0-9] and - _ . ~
  pass through (verified against R 4.6.1), every other byte of the UTF-8
  representation becomes %XX."
  [^String s]
  (let [sb (StringBuilder.)]
    (doseq [b (.getBytes s "UTF-8")]
      (let [c (char (bit-and (int b) 0xff))]
        (if (or (and (<= (int \a) (int c)) (<= (int c) (int \z)))
                (and (<= (int \A) (int c)) (<= (int c) (int \Z)))
                (and (<= (int \0) (int c)) (<= (int c) (int \9)))
                (contains? #{\- \_ \. \~} c))
          (.append sb c)
          (.append sb (format "%%%02X" (bit-and (int b) 0xff))))))
    (str sb)))

;; ---------------------------------------------------------------------------
;; Body assembly (pure)

(defn build-body
  "Build the application/x-www-form-urlencoded POST body from filtered
  data-ids {:rows [...] :meta ...}. Pure; mirrors the R body assembly:
  title (URL-encoded), struts.token.name, <token-name>=<token>, id,
  lineObjectIds, columnObjectIds, one selectedFilterIds per row
  (<field-id>_<value-id>), filterObjectIds."
  [{:keys [rows meta]}]
  (let [indicator (or (first (filter #(= "0" (:filter-field-id %)) rows))
                      (throw (ex-info (str "data_ids has no indicator (filter_field_id == \"0\") row, "
                                           "cannot build POST body")
                                      {})))
        indicator-id (or (:fedstat/indicator-id meta)
                         (:filter-value-id indicator))
        token-name (:fedstat/csrf-token-name meta)
        token (:fedstat/csrf-token meta)
        class-by-fid (reduce (fn [m r]
                               (if (contains? m (:filter-field-id r))
                                 m
                                 (assoc m (:filter-field-id r)
                                        (:filter-field-object-ids r))))
                             {}
                             rows)
        fids (distinct (map :filter-field-id rows))
        fids-of (fn [object-class]
                  (filter #(= object-class (class-by-fid %)) fids))]
    (str/join
     "&"
     (concat
      [(str "title=" (url-encode (:filter-value-title indicator)))
       (str "struts.token.name=" token-name)
       (str token-name "=" token)
       (str "id=" indicator-id)]
      (map #(str "lineObjectIds=" %) (fids-of "lineObjectIds"))
      (map #(str "columnObjectIds=" %) (fids-of "columnObjectIds"))
      (map #(str "selectedFilterIds=" (:filter-field-id %) "_" (:filter-value-id %))
           rows)
      (map #(str "filterObjectIds=" %) (fids-of "filterObjectIds"))))))

;; ---------------------------------------------------------------------------
;; POST

(defn- body-preview [^bytes body]
  (when body
    (String. body 0 (min 500 (alength body)) "UTF-8")))

(defn- warn! [msg]
  (binding [*out* *err*]
    (println (str "WARNING: " msg))))

(defn- fetch-csrf!
  "Fallback port of the token re-fetch in fedstat_post_data_ids_filtered.R:
  GET the indicator page and extract the CSRF token from it."
  [data-ids transport base-url timeout-seconds]
  (let [{:keys [rows meta]} data-ids
        indicator-id (or (:fedstat/indicator-id meta)
                         (:filter-value-id (first (filter #(= "0" (:filter-field-id %)) rows))))
        resp (transport {:method :get
                         :url (str base-url "/indicator/" indicator-id)
                         :headers default-headers
                         :cookie-jar (:fedstat/cookie-jar meta)
                         :timeout-seconds timeout-seconds})]
    (when (>= (:status resp) 400)
      (throw (ex-info (str "Failed to fetch CSRF token for indicator " indicator-id ". "
                           "HTTP status: " (:status resp))
                      {:status (:status resp)})))
    (let [csrf (page/csrf-token (String. ^bytes (:body resp) "UTF-8"))]
      (when (or (nil? (:token-name csrf)) (nil? (:token csrf)))
        (throw (ex-info (str "Could not extract CSRF token from indicator page for "
                             indicator-id ". EMISS may have changed their page structure.")
                        {})))
      csrf)))

(defn post-data-ids
  "POST data-ids to /indicator/downloadData.do?format=<data-format> via
  transport. Returns the raw response body bytes on success.

  opts:
  :transport        - fn request -> response (required)
  :data-format      - :sdmx (default) or :excel
  :timeout-seconds  - per-request timeout (default 180)
  :retry-max-times  - reserved for API parity with the R original; POST itself
                      is never retried (CSRF tokens are single-use)

  Error messages mirror the R originals (302/403/503/5xx, CSRF error page,
  HTML page instead of data, unknown content type)."
  [data-ids {:keys [transport data-format timeout-seconds]
             :or {data-format :sdmx timeout-seconds 180}}]
  (let [{:keys [rows meta]} data-ids
        base-url (or (:fedstat/base-url meta) "https://www.fedstat.ru")
        token (:fedstat/csrf-token meta)
        token-name (:fedstat/csrf-token-name meta)
        meta (if (or (nil? token) (nil? token-name))
               (do (warn! (str "No CSRF token found in data_ids meta. "
                               "Fetching a fresh token from fedstat.ru (extra GET request). "
                               "To avoid this, use get-data-ids in the same session "
                               "and pass its result directly into post-data-ids."))
                   (let [csrf (fetch-csrf! data-ids transport base-url timeout-seconds)]
                     (assoc meta
                            :fedstat/csrf-token (:token csrf)
                            :fedstat/csrf-token-name (:token-name csrf))))
               meta)
        data-ids (assoc data-ids :meta meta)
        resp (transport {:method :post
                         :url (str base-url "/indicator/downloadData.do?format=" (name data-format))
                         :headers default-headers
                         :content-type "application/x-www-form-urlencoded"
                         :body (build-body data-ids)
                         :cookie-jar (:fedstat/cookie-jar meta)
                         :follow-redirects false
                         :timeout-seconds timeout-seconds})
        status (:status resp)]
    (cond
      (= 302 status)
      (throw (ex-info (str "EMISS rejected the download request (HTTP 302 redirect). "
                           "Possible causes:\n"
                           "  - Filter combination produces no data\n"
                           "  - Invalid filter value IDs\n"
                           "  - Query too broad or too narrow\n"
                           "Try adjusting your filters or inspect filter values with get-data-ids.")
                      {:status status}))

      (>= status 400)
      (let [preview (body-preview (:body resp))]
        (cond
          (= 503 status)
          (throw (ex-info (str "EMISS returned 503 (Service Unavailable). "
                               "The server is overloaded. Try again later or reduce query size.\n"
                               "Response: " preview)
                          {:status status}))
          (= 403 status)
          (throw (ex-info (str "EMISS returned 403 (Forbidden). "
                               "Your request was blocked by anti-bot protection.\n"
                               "Response: " preview)
                          {:status status}))
          :else
          (throw (ex-info (str "EMISS returned HTTP " status ".\n"
                               "Response: " preview)
                          {:status status}))))

      :else
      (let [content-type (get (:headers resp) "content-type")]
        (if (contains? #{"text/xml" "application/vnd.ms-excel"} content-type)
          (:body resp)
          (let [body-text (body-preview (:body resp))]
            (cond
              (and body-text (re-find #"(?i)CSRF" body-text))
              (throw (ex-info (str "EMISS rejected the request: CSRF token validation failed. "
                                   "The token may have expired or been already used. "
                                   "Call get-data-ids again to obtain a fresh token.")
                              {:content-type content-type}))
              (and body-text (re-find #"(?i)<html" body-text))
              (throw (ex-info (str "EMISS returned an HTML page instead of data. "
                                   "This usually means the request was rejected by the server. "
                                   "Content-Type: " (or content-type "<none>") "\n"
                                   "Response: " body-text)
                              {:content-type content-type}))
              :else
              (throw (ex-info (str "No data found with specified filters or the fedstat is lagging. "
                                   "Content-Type: " (or content-type "<none>"))
                              {:content-type content-type})))))))))
