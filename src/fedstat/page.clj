(ns fedstat.page
  "Parsing of fedstat.ru indicator pages: CSRF token extraction and the
  java-script-to-JSON conversion used to obtain filter ids.

  Pure functions, no HTTP. Ported from fedstatAPIr (R): parse_js1, parse_js2
  and the CSRF extraction in fedstat_get_data_ids."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; CSRF token

(defn csrf-token
  "Extract the CSRF token from the indicator page HTML.

  Reads the #downloadTokenHolder div: the input named struts.token.name
  holds the token parameter name, the input with that name holds the token.

  Returns {:token-name <string> :token <string>}, with nil values when the
  page carries no token (same NULL-ability as the R original)."
  [^String html]
  (let [holder (second (re-find #"(?is)<div[^>]*id=\"downloadTokenHolder\"[^>]*>(.*?)</div>"
                                html))
        input-tags (when holder
                     (re-seq #"(?is)<input\b[^>]*>" holder))
        attrs (map (fn [tag]
                     {:name (some-> (re-find #"(?is)name=\"([^\"]*)\"" tag) second)
                      :value (some-> (re-find #"(?is)value=\"([^\"]*)\"" tag) second)})
                   input-tags)
        by-name (into {} (map (juxt :name :value)) attrs)
        token-name (get by-name "struts.token.name")]
    {:token-name token-name
     :token (get by-name token-name)}))

;; ---------------------------------------------------------------------------
;; Script nodes

(defn script-nodes
  "All <script> element bodies in document order. Scripts loaded via src have
  empty bodies; their positions still count, exactly like xml2::xml_find_all
  in the R original."
  [^String html]
  (map second (re-seq #"(?is)<script\b[^>]*>(.*?)</script>" html)))

(defn filters-script-index
  "Index (0-based) of the inline script holding the filters data, or nil."
  [^String html]
  (->> (script-nodes html)
       (keep-indexed (fn [i body]
                       (when (str/includes? body "filters: {") i)))
       first))

;; ---------------------------------------------------------------------------
;; JS object literal -> JSON

(defn- quote-unquoted-keys
  "Port of fedstatAPIr's gsub(\"\\\\b(?=([^']*'[^']*')*[^']*$)\", \"'\") trick:
  inserts a quote at every word boundary outside single-quoted strings,
  thereby quoting previously unquoted JS keys."
  [^String text]
  (str/replace text #"\b(?=([^']*'[^']*')*[^']*$)" "'"))

(defn js-fragment->edn
  "Parse a JS object-literal fragment (a seq of source lines) into Clojure
  data. Applies the quote trick and parses the result as JSON, mirroring
  parse_js1/parse_js2. Fast path processes line by line, which is equivalent
  to the whole-text processing as long as every line contains an even number
  of single quotes (strings do not span lines); falls back to whole-text
  otherwise."
  [lines]
  (let [lines (vec lines)
        balanced? (every? #(even? (count (filter #{\'} %))) lines)
        text (if balanced?
               (str/join "\n" (map quote-unquoted-keys lines))
               (quote-unquoted-keys (str/join "\n" lines)))
        json-text (str/replace text "'" "\"")]
    (json/parse-string (str "{" json-text "}") false)))

;; ---------------------------------------------------------------------------
;; parse_js1 / parse_js2 ports

(defn- first-index-containing
  [lines s]
  (first (keep-indexed (fn [i line]
                         (when (str/includes? line s) i))
                       lines)))

(defn parse-filters-js
  "Port of parse_js1. Given the lines of the filters script, returns the
  filters block as Clojure data: {field-id {\"title\" .. \"values\"
  {value-id {\"title\" ..}}}}. All ids are strings."
  [script-lines]
  (let [lines (vec script-lines)
        start (first-index-containing lines "filters: {")
        end (first-index-containing lines "left_columns: [")]
    (when (and start end)
      (js-fragment->edn (subvec lines (inc start) (dec end))))))

(defn parse-object-ids-js
  "Port of parse_js2. Given the lines of the filters script, returns an
  ordered vector of [object-class field-ids] pairs for left_columns,
  top_columns, groups and filterObjectIds, renamed like the R original:
  left_columns/groups/filterObjectIds -> lineObjectIds,
  top_columns -> columnObjectIds. Ids are strings; empty groups are kept
  as empty vectors so positional parity with the page is preserved."
  [script-lines]
  (let [lines (vec script-lines)
        start (first-index-containing lines "left_columns: [")
        end (first-index-containing lines "grid.init();")
        rename {"left_columns" "lineObjectIds"
                "top_columns" "columnObjectIds"
                "groups" "lineObjectIds"
                "filterObjectIds" "lineObjectIds"}]
    (when (and start end)
      (into []
            (keep (fn [[k v]]
                    (when-let [cls (rename k)]
                      [cls (mapv str v)])))
            (js-fragment->edn (subvec lines start (dec end)))))))
