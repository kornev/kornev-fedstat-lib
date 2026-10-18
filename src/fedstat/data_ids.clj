(ns fedstat.data-ids
  "Assembly of the data-ids structure from parsed indicator page data.
  Pure functions. Ported from the table assembly inside fedstat_get_data_ids
  (fedstatAPIr, R)."
  (:require
   [clojure.string :as str]))

(defn object-classes
  "Map field-id -> object class (lineObjectIds / columnObjectIds /
  filterObjectIds), first occurrence wins.

  object-ids-pairs is the ordered output of fedstat.page/parse-object-ids-js.
  Mirrors the R logic: js filterObjectIds are renamed to lineObjectIds by the
  parser, and a synthetic [\"filterObjectIds\" [\"0\"]] is appended when no
  field id equals \"0\"."
  [object-ids-pairs]
  (let [pairs (cond-> (vec object-ids-pairs)
                (every? (fn [[_ ids]] (every? #(not= "0" %) ids))
                        object-ids-pairs)
                (conj ["filterObjectIds" ["0"]]))
        flat (for [[cls ids] pairs, id ids] [id cls])]
    (reduce (fn [m [id cls]]
              (if (contains? m id) m (assoc m id cls)))
            {}
            flat)))

(defn build-data-ids
  "Assemble {:rows [...] :meta {...}}.

  filters is the parsed filters map from fedstat.page/parse-filters-js:
  {field-id {\"title\" .. \"values\" {value-id {\"title\" ..}}}}.
  opts carries the session metadata that the R version attaches as
  data.frame attributes:
  :token-name, :token     - CSRF credentials from the indicator page
  :indicator-id           - indicator id as requested
  :base-url               - fedstat base URL
  :cookie-jar             - path to the session cookie jar (curl handle
                            equivalent of the R httr::handle)"
  [filters object-ids-pairs {:keys [token-name token indicator-id
                                    base-url cookie-jar]}]
  (let [classes (object-classes object-ids-pairs)
        page-order-rows (mapcat
                         (fn [[fid f]]
                           (let [values (get f "values")]
                             (when (empty? values)
                               (throw (ex-info (str "fedstat returned erroneous data_ids. "
                                                    "It's probably lagging. There are no filter "
                                                    "values in the \"" (get f "title") "\" filter ")
                                               {:filter-field-id fid})))
                             (map (fn [[vid v]]
                                    {:filter-field-id fid
                                     :filter-field-title (get f "title")
                                     :filter-value-id (str vid)
                                     :filter-value-title (str/replace (str (get v "title"))
                                                                      "&quot;" "\"")
                                     :filter-field-object-ids (get classes fid "lineObjectIds")})
                                  values)))
                         filters)
        ;; the R assembly merges through data.table which re-sorts rows by
        ;; filter_field_id; mirrored here with a stable sort so that every
        ;; downstream ordering (filters, POST body) matches R bit for bit
        rows (vec (sort-by :filter-field-id page-order-rows))]
    (when (not= (count rows) (count (distinct rows)))
      (throw (ex-info (str "data_ids table with non unique filter_field_id "
                           "and filter_value_ids pairs")
                      {})))
    {:rows rows
     :meta {:fedstat/csrf-token token
            :fedstat/csrf-token-name token-name
            :fedstat/indicator-id (str indicator-id)
            :fedstat/base-url base-url
            :fedstat/cookie-jar cookie-jar}}))
