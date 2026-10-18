(ns fedstat.sdmx
  "Parsing of SDMX (GenericData) payloads into plain tables.

  Pure functions. Port of fedstat_parse_sdmx_to_table.R (fedstatAPIr).
  Unlike the R original this does not depend on readsdmx: the GenericData
  XML is parsed directly with clojure.data.xml (built into babashka).
  Column naming differs accordingly: the R readsdmx renaming artefacts
  (\"X30.OKATO\" -> \"30-OKATO\" fixes) do not exist here, columns are named
  by the SDMX concept ids directly.

  Namespace handling: fedstat SDMX documents carry several namespace
  prefixes (generic:, structure:, xml:, xsi:) and a default namespace.
  To keep the parser simple the raw text is preprocessed: prefixed tag
  names are reduced to their local name and prefixed/default namespace
  declarations are dropped. Element local names and attribute values
  (concept, value, id) are unaffected by this."
  (:require
   [clojure.data.xml :as xml]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; XML preprocessing and tree access

(defn- strip-namespaces
  "Reduce prefixed tags to local names and drop namespace declarations
  (xmlns / xmlns:* / xsi:* / xml:* attributes)."
  [^String s]
  (-> s
      (str/replace #"<(/?)[a-zA-Z][a-zA-Z0-9]*:" "<$1")
      (str/replace #" [a-zA-Z][a-zA-Z0-9]*:[a-zA-Z][a-zA-Z0-9]*=\"[^\"]*\"" "")
      (str/replace #" xmlns(:[a-zA-Z][a-zA-Z0-9]*)?=\"[^\"]*\"" "")))

(defn- elem? [x]
  (and (map? x) (contains? x :tag)))

(defn- elems [node tag]
  (filter #(and (elem? %) (= tag (:tag %))) (:content node)))

(defn- descendants
  "All descendant elements with the given tag (recursive descent)."
  [node tag]
  (mapcat (fn [c]
            (when (elem? c)
              (concat (when (= tag (:tag c)) [c])
                      (descendants c tag))))
          (:content node)))

(defn- first-elem [node tag]
  (first (elems node tag)))

(defn- text
  "Concatenated direct text content of an element (port of xml2::xml_text
  for elements whose text lives in a single child)."
  [e]
  (str/join (filter string? (:content e))))

;; ---------------------------------------------------------------------------
;; CodeLists

(defn- parse-code-list [cl]
  (let [id (get (:attrs cl) :id)
        title (some-> (first-elem cl :Name) text)
        codes (elems cl :Code)]
    (map (fn [code]
           {:field-id id
            :field-title title
            :value-id (get (:attrs code) :value)
            :value-title (some-> (or (first-elem code :Description)
                                     (first-elem code :Name))
                                 text)})
         codes)))

(defn- dictionary
  "All CodeLists flattened to [{:field-id :field-title :value-id :value-title}]."
  [root]
  (let [dict (mapcat parse-code-list (descendants root :CodeList))]
    (when (some (fn [{:keys [field-id field-title value-id value-title]}]
                  (or (nil? field-id) (str/blank? (str field-id))
                      (nil? field-title) (str/blank? field-title)
                      (nil? value-id)
                      (nil? value-title) (str/blank? value-title)))
                dict)
      (throw (ex-info "NA in lookup sdmx table" {})))
    (vec dict)))

;; ---------------------------------------------------------------------------
;; DataSet

(defn- series-rows [series]
  (let [series-key (first-elem series :SeriesKey)
        attributes (first-elem series :Attributes)
        value-map (fn [parent]
                    (into {}
                          (map (fn [v]
                                 [(get (:attrs v) :concept)
                                  (get (:attrs v) :value)]))
                          (elems parent :Value)))
        dims (if series-key (value-map series-key) {})
        attrs (if attributes (value-map attributes) {})]
    (map (fn [obs]
           (merge dims
                  attrs
                  {"Time" (some-> (first-elem obs :Time) text)
                   "ObsValue" (some-> (:attrs (first-elem obs :ObsValue))
                                      (get :value))}))
         (elems series :Obs))))

(defn- data-rows [root]
  (vec (mapcat series-rows (descendants root :Series))))

;; ---------------------------------------------------------------------------
;; ObsValue numeric parsing

(defn- parse-obs-value!
  "Port of the try_to_parse_ObsValue block: ',' -> '.', string -> double.
  Blank values become nil; a non-blank value that does not parse is an error."
  [rows try-to-parse?]
  (if-not try-to-parse?
    rows
    (let [parsed (mapv (fn [row]
                         (let [v (get row "ObsValue")]
                           (if (or (nil? v) (str/blank? (str v)))
                             (assoc row "ObsValue" nil)
                             (try
                               (assoc row "ObsValue"
                                      (Double/parseDouble
                                       (str/replace (str v) "," ".")))
                               (catch NumberFormatException _
                                 ::unparsable)))))
                       rows)]
      (when (some #(= ::unparsable (get % "ObsValue")) parsed)
        (throw (ex-info (str "Unable to parse ObsValue from character to numeric "
                             "type automatically, set :try-to-parse-obs-value? to false")
                        {})))
      parsed)))

;; ---------------------------------------------------------------------------
;; Public API

(defn- warn! [msg]
  (binding [*out* *err*]
    (println (str "WARNING: " msg))))

(defn parse-sdmx-to-table
  "Parse SDMX raw bytes into a table. Mirrors fedstat_parse_sdmx_to_table.R.

  data-raw: bytes (a String or nil is accepted the way the R original accepts
            non-raw input: warns and returns nil)
  opts:
  :return-type             - :data (default) or :dictionary
  :try-to-parse-obs-value? - parse ObsValue strings into doubles (default true)

  :data returns a vector of maps: codelist concept columns hold value titles
  (joined from the CodeLists), the original value ids are kept next to them
  in <concept>_code columns, remaining columns (Time, ObsValue, attributes)
  pass through.
  :dictionary returns [{:field-id :field-title :value-id :value-title}]."
  [data-raw {:keys [return-type try-to-parse-obs-value?]
             :or {return-type :data try-to-parse-obs-value? true}}]
  (if (or (nil? data-raw) (string? data-raw))
    (do (warn! "data_raw is not raw (binary) type")
        nil)
    (let [raw-text (String. ^bytes data-raw "UTF-8")]
      (when (re-find #"(?i)<!DOCTYPE\s+html|<html" raw-text)
        (if (re-find #"(?i)CSRF" raw-text)
          (throw (ex-info (str "Received an HTML CSRF error page instead of SDMX data. "
                               "The CSRF token may have expired. "
                               "Please re-run from get-data-ids to obtain a fresh token.")
                          {}))
          (throw (ex-info (str "Received an HTML page instead of SDMX data. "
                               "EMISS likely returned an error page.\n"
                               "Preview: " (subs raw-text 0 (min 300 (count raw-text))))
                          {}))))
      (let [root (xml/parse-str (strip-namespaces raw-text))
            dict (dictionary root)]
        (if (= :dictionary return-type)
          dict
          (let [rows (data-rows root)
                columns (vec (distinct (mapcat keys rows)))
                field-ids (distinct (map :field-id dict))
                missing (remove (set columns) field-ids)]
            (when (seq missing)
              (throw (ex-info (str "Expected columns not found in data: "
                                   (str/join ", " missing) "\n"
                                   "there are only these columns in the data: "
                                   (str/join ", " columns))
                              {:missing missing})))
            (let [lookup (reduce (fn [m {:keys [field-id value-id value-title]}]
                                   (assoc-in m [field-id value-id] value-title))
                                 {}
                                 dict)
                  non-codelist (remove (set field-ids) columns)
                  joined (mapv (fn [row]
                                 (into {}
                                       (concat
                                        (keep (fn [f]
                                                (when (contains? row f)
                                                  [f (get-in lookup [f (get row f)])]))
                                              field-ids)
                                        (keep (fn [f]
                                                (when (contains? row f)
                                                  [(str f "_code") (get row f)]))
                                              field-ids)
                                        (map (fn [k] [k (get row k)]) non-codelist))))
                               rows)]
              (parse-obs-value! joined try-to-parse-obs-value?))))))))
