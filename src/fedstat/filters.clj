(ns fedstat.filters
  "Filtering of data-ids by user-supplied filters.

  Pure functions. Ported from fedstat_data_ids_filter.R (fedstatAPIr).
  Mirrors the R semantics: string normalization (lowercase, collapsed
  whitespace, trimmed) on both sides, \"*\" as select-all special value,
  auto-addition of single-value fields and the Показатель (indicator) filter,
  error on unspecified multi-value filter fields, warnings (to *err*) on
  filter values that do not exist in the data source."
  (:require
   [clojure.string :as str]))

(defn- str-norm
  "Port of str_norm in fedstat_data_ids_filter.R."
  [s]
  (-> (str s) str/trim (str/replace #"\s+" " ") str/lower-case))

(defn- normalize-filters
  "filters map {title (string | coll)} -> vector of [title [values]] pairs
  in map order, values stringified."
  [filters]
  (mapv (fn [[title values]]
          [(str title)
           (if (sequential? values) (mapv str values) [(str values)])])
        filters))

(defn filter-data-ids
  "Filter the :rows of data-ids (from fedstat.data-ids/build-data-ids) by
  filters {filter-field-title (filter-value-title | [titles...] | \"*\")}.

  opts:
  :disable-warnings? - suppress warnings about unmatched filter values
                       (default false)

  Returns {:rows [...] :meta ...} with :meta preserved as-is. Throws ex-info
  with messages mirroring the R originals on erroneous filters."
  [data-ids filters {:keys [disable-warnings?] :or {disable-warnings? false}}]
  (let [rows (:rows data-ids)
        meta (:meta data-ids)
        field-order (distinct (map :filter-field-id rows))
        rows-by-fid (group-by :filter-field-id rows)
        title-by-fid (into {}
                           (map (fn [r]
                                  [(:filter-field-id r) (:filter-field-title r)]))
                           rows)
        one-value-fids (set (filter #(= 1 (count (rows-by-fid %))) field-order))
        fid-by-title-norm (reduce (fn [m fid]
                                    (let [k (str-norm (title-by-fid fid))]
                                      (if (contains? m k) m (assoc m k fid))))
                                  {}
                                  field-order)
        indicator-title (some #(when (= "0" (:filter-field-id %))
                                 (:filter-value-title %))
                              rows)
        requested (cond-> (normalize-filters filters)
                    indicator-title (conj ["Показатель" [indicator-title]]))
        ;; resolve field ids, error on unknown filter field titles
        requested (mapv (fn [[title values]]
                          {:title title
                           :values values
                           :fid (get fid-by-title-norm (str-norm title))})
                        requested)
        unknown (distinct (keep (fn [{:keys [title fid]}]
                                  (when (nil? fid) title))
                                requested))]
    (when (seq unknown)
      (throw (ex-info (str "These filters are named incorrectly or do not exist: "
                           (str/join ", " unknown))
                      {:unknown-filters unknown})))
    ;; warnings for values that do not exist in the data source ("*" excluded)
    (when-not disable-warnings?
      (let [no-match-by-title
            (keep (fn [{:keys [title fid values]}]
                    (let [existing (set (map (comp str-norm :filter-value-title)
                                             (rows-by-fid fid)))
                          bad (filterv (fn [v]
                                         (and (not= "*" (str-norm v))
                                              (not (contains? existing (str-norm v)))))
                                       values)]
                      (when (seq bad) [title bad])))
                  requested)]
        (when (seq no-match-by-title)
          (binding [*out* *err*]
            (println
             (str "No matching filter values were found in the data source "
                  "for the following filter values: \n"
                  (str/join "\n"
                            (map (fn [[title values]]
                                   (str "    " title ": " (str/join ", " values)))
                                 no-match-by-title))
                  "\nThe data will not be filtered by them"))))))
    ;; R's merge sorts user-specified fields alphabetically by normalized
    ;; field title (data.table merge side effect, verified against
    ;; fedstatAPIr 1.1.0); auto-added single-value fields follow in
    ;; filter_field_id order
    (let [by-fid (reduce (fn [acc {:keys [fid values]}]
                           (update acc fid (fnil into []) values))
                         {}
                         requested)
          user-multi (into []
                           (remove (fn [[fid _]] (contains? one-value-fids fid)))
                           by-fid)
          user-multi (vec (sort-by (fn [[fid _]] (str-norm (title-by-fid fid)))
                                   compare user-multi))
          specified-fids (set (map first user-multi))
          not-specified (remove specified-fids field-order)
          not-specified-multi (remove one-value-fids not-specified)]
      (when (seq not-specified-multi)
        (let [titles (map title-by-fid not-specified-multi)]
          (throw (ex-info (str "The following filter fields must be explicitly "
                               "specified in filters:\n"
                               (str/join "\n" (map #(str "  - " %) titles))
                               "\n\nUse \"*\" to select all values for a filter field."
                               "\nExample: list(\"" (first titles) "\" = \"*\")"
                               "\n\nTo see all available filters, inspect the "
                               "data_ids table from fedstat_get_data_ids().")
                          {:missing-filters titles}))))
      (let [singles (sort-by first (filter one-value-fids not-specified))
            effective (into user-multi
                            (map (fn [fid] [fid ["*"]]))
                            singles)
            ;; R compares the RAW filter value to "*" (not normalized):
            ;; " * " is just a value that matches nothing
            star-mixed (filter (fn [[_ values]]
                                 (and (some #(= "*" (str %)) values)
                                      (< 1 (count values))))
                               effective)]
        (when (seq star-mixed)
          (throw (ex-info (str "Special value for "
                               (str/join ", " (map (comp title-by-fid first) star-mixed))
                               " field is in a filter vector of length greater than 1")
                          {})))
        (let [selected (map (fn [[fid values]]
                              (let [fid-rows (rows-by-fid fid)
                                    star? (= "*" (str (first values)))]
                                (if star?
                                  fid-rows
                                  (let [norms (set (map str-norm values))]
                                    (filter (fn [r]
                                              (contains? norms (str-norm (:filter-value-title r))))
                                            fid-rows)))))
                            effective)
              empty-fids (map ffirst
                              (filter (fn [[_ fid-rows]] (empty? fid-rows))
                                      (map vector effective selected)))]
          (when (seq empty-fids)
            (throw (ex-info (str "No filter values were found for filters fields: "
                                 (str/join ", " (map title-by-fid empty-fids)))
                            {:empty-filters empty-fids})))
          {:rows (vec (mapcat identity selected))
           :meta meta})))))
