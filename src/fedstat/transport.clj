(ns fedstat.transport
  "Real HTTP transport backed by babashka.curl.

  babashka.curl shells out to the curl binary, so TLS verification uses the
  system CA store (see AGENTS.md #24: JVM HTTP clients inside babashka's
  native image use a trimmed trust store and fail on some hosts; the curl
  binary does not). The namespace is built into babashka; on the JVM it
  comes from the babashka/babashka.curl dependency in deps.edn."
  (:require
   [babashka.curl :as curl]
   [clojure.string :as str]))

(defn default-transport
  "Transport fn matching the contract used by fedstat.core/fedstat.post:
  request map {:method :get|:post :url :headers :body :content-type
               :cookie-jar :follow-redirects :timeout-seconds}
  -> {:status :headers :body} with :body as bytes.

  Process-level failures (connection refused, timeout) throw ex-info, which
  the retry logic in fedstat.core treats as a retryable attempt.
  :cookie-jar is used for both reading and writing (--cookie/--cookie-jar),
  which preserves the session between the indicator page GET and the data
  POST like the httr::handle in the R original."
  [{:keys [method url headers body content-type cookie-jar follow-redirects
           timeout-seconds]}]
  (let [headers (cond-> (or headers {})
                  content-type (assoc "Content-Type" content-type))
        raw-args (cond-> []
                   cookie-jar (conj "--cookie-jar" cookie-jar
                                    "--cookie" cookie-jar)
                   timeout-seconds (conj "--max-time" (str (long timeout-seconds))))
        opts (cond-> {:url url
                      :method method
                      :headers headers
                      :throw false
                      :as :bytes
                      :raw-args raw-args}
               body (assoc :body body)
               (false? follow-redirects) (assoc :follow-redirects false))
        resp (curl/request opts)]
    (when (nil? (:status resp))
      (throw (ex-info (str "curl failed: " (or (:err resp) "no response")
                           " (" (str/upper-case (name method)) " " url ")")
                      {:url url :method method :exit (:exit resp)})))
    {:status (:status resp)
     :headers (:headers resp)
     :body (:body resp)}))
