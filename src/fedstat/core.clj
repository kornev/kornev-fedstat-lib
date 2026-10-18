(ns fedstat.core
  "High-level API: downloading data-ids from an indicator page and the
  full get -> filter -> post -> parse cycle.

  Ports the HTTP parts of fedstat_get_data_ids.R and the retrying wrapper
  fedstat_data_load_with_filters.R (fedstatAPIr). The transport is an
  injectable fn of a request map (see fedstat.post); pass
  fedstat.transport/default-transport for real HTTP."
  (:require
   [clojure.string :as str]
   [fedstat.data-ids :as data-ids]
   [fedstat.filters :as filters]
   [fedstat.page :as page]
   [fedstat.post :as post]
   [fedstat.sdmx :as sdmx]))

(defn- warn! [msg]
  (binding [*out* *err*]
    (println (str "WARNING: " msg))))

(defn- default-sleep [seconds]
  (Thread/sleep (long (* 1000 seconds))))

(defn- temp-cookie-jar []
  (let [f (java.io.File/createTempFile "fedstat-cookies" ".txt")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn- http-error!
  "Port of the http_error block in fedstat_get_data_ids.R."
  [status indicator-id]
  (cond
    (= 403 status)
    (throw (ex-info (str "EMISS returned 403 (Forbidden) for indicator " indicator-id ". "
                         "Your request was likely blocked by anti-bot protection.")
                    {:status status :indicator-id indicator-id}))
    (= 503 status)
    (throw (ex-info (str "EMISS returned 503 (Service Unavailable) for indicator " indicator-id ". "
                         "The server is overloaded. Try again later.")
                    {:status status :indicator-id indicator-id}))
    :else
    (throw (ex-info (str "EMISS returned HTTP " status " for indicator " indicator-id ".")
                    {:status status :indicator-id indicator-id}))))

(defn get-data-ids
  "Download the indicator page and assemble the data-ids structure
  {:rows [...] :meta {...}} (see fedstat.data-ids/build-data-ids).

  opts:
  :transport       - fn request -> response (required)
  :timeout-seconds - per-attempt timeout (default 180)
  :retry-max-times - GET attempts (default 3); pauses between attempts grow
                     exponentially (1s, 2s, 4s ... capped at 60), like
                     httr::RETRY in the R original
  :cookie-jar      - path for the session cookie jar (default: fresh temp file)
  :sleep           - fn of seconds, injectable for tests"
  [indicator-id {:keys [transport timeout-seconds retry-max-times cookie-jar sleep]
                 :or {timeout-seconds 180 retry-max-times 3}}]
  (let [base-url "https://www.fedstat.ru"
        url (str base-url "/indicator/" indicator-id)
        cookie-jar (or cookie-jar (temp-cookie-jar))
        sleep (or sleep default-sleep)
        attempt (fn [] (try
                         {:response (transport {:method :get
                                                :url url
                                                :headers post/default-headers
                                                :cookie-jar cookie-jar
                                                :timeout-seconds timeout-seconds})}
                         (catch Throwable t {:error t})))
        resp (loop [n 1]
               (let [{:keys [response error]} (attempt)]
                 (cond
                   (and response (< (:status response) 400))
                   response

                   (< n retry-max-times)
                   (do (sleep (min 60 (long (Math/pow 2 (dec n)))))
                       (recur (inc n)))

                   error
                   (throw error)

                   :else
                   (http-error! (:status response) indicator-id))))
        html (String. ^bytes (:body resp) "UTF-8")
        scripts (vec (page/script-nodes html))
        script-index (or (page/filters-script-index html)
                         (throw (ex-info (str "Could not find the filters script "
                                              "on the indicator page for " indicator-id)
                                         {:indicator-id indicator-id})))
        script-lines (str/split-lines (nth scripts script-index))
        parsed-filters (page/parse-filters-js script-lines)
        object-ids (page/parse-object-ids-js script-lines)
        csrf (page/csrf-token html)]
    (when (nil? (:token csrf))
      (warn! (str "Could not extract CSRF token from indicator page for "
                  indicator-id ". post-data-ids will attempt to fetch one automatically.")))
    (data-ids/build-data-ids parsed-filters object-ids
                             {:token-name (:token-name csrf)
                              :token (:token csrf)
                              :indicator-id indicator-id
                              :base-url base-url
                              :cookie-jar cookie-jar})))

(defn load-with-filters
  "One-call API for downloading indicator data. Runs the cycle
  get-data-ids -> filter-data-ids -> post-data-ids -> parse-sdmx-to-table,
  retrying the whole cycle up to :retry-max-times times (a fresh CSRF token
  is fetched on every retry, tokens are single-use).

  opts: :filters (default {}), :timeout-seconds (180), :retry-max-times (3),
  :disable-warnings? (false), :loading-steps-verbose? (true),
  :return-type (:data | :dictionary), :try-to-parse-obs-value? (true),
  :cookie-jar, :sleep, :transport (required).

  Returns the parsed table (vector of maps), like
  fedstat_data_load_with_filters.R."
  [indicator-id {:keys [transport filters timeout-seconds retry-max-times
                        disable-warnings? loading-steps-verbose? return-type
                        try-to-parse-obs-value? cookie-jar sleep]
                 :or {filters {} timeout-seconds 180 retry-max-times 3
                      disable-warnings? false loading-steps-verbose? true
                      return-type :data try-to-parse-obs-value? true}}]
  (let [step (fn [msg] (when loading-steps-verbose? (println msg)))
        get-opts {:transport transport
                  :timeout-seconds timeout-seconds
                  :retry-max-times retry-max-times
                  :cookie-jar cookie-jar
                  :sleep sleep}]
    (loop [attempt 1]
      (when (< 1 attempt)
        (step (str "Retry attempt " attempt " of " retry-max-times " ...")))
      (step (str "Downloading EMISS internal identificators for the "
                 "https://www.fedstat.ru/indicator/" indicator-id " ..."))
      (let [[data-ids get-error] (try [(get-data-ids indicator-id get-opts) nil]
                                      (catch Throwable t [nil t]))]
        (if (nil? data-ids)
          (if (>= attempt retry-max-times)
            (throw get-error)
            (do (step "GET failed, retrying ...")
                (recur (inc attempt))))
          (let [filtered (filters/filter-data-ids data-ids filters
                                                  {:disable-warnings? disable-warnings?})]
            (step "Downloading data from EMISS ...")
            (let [[raw post-error]
                  (try [(post/post-data-ids filtered
                                            {:transport transport
                                             :timeout-seconds timeout-seconds
                                             :data-format :sdmx})
                        nil]
                       (catch Throwable t [nil t]))]
              (if (nil? raw)
                (if (>= attempt retry-max-times)
                  (throw post-error)
                  (do (step (str "POST failed: " (ex-message post-error)))
                      (step "Re-fetching CSRF token ...")
                      (recur (inc attempt))))
                (do (step "Parsing data ...")
                    (let [result (sdmx/parse-sdmx-to-table
                                  raw
                                  {:return-type return-type
                                   :try-to-parse-obs-value? try-to-parse-obs-value?})]
                      (step "Done")
                      result))))))))))
