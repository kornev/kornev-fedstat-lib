(ns fedstat.indicators
  "Indicator catalogue parsing from https://www.fedstat.ru/organizations/.

  Port of fedstat_indicator_info.R (fedstatAPIr), verified byte-for-byte
  against the R original (fedstatAPIr 1.1.0) on a captured page — see
  dev/verify/. The R original uses xml2 xpath over the page HTML; here the
  same information is extracted from a lightweight div tree built by
  tokenizing <div>/<\\/div> inside the #orgsTree subtree (babashka has no
  HTML parser). Semantics mirrored from the R xpath logic:

    - only the #orgsTree subtree is read (items = its direct div children;
      the page also carries a flat thematic tree outside it which R ignores);
    - department = the first descendant <span class=\"i_name org\"> of the
      item (its id=groupN. header);
    - groups = element children of the item's first <div class=\"ved_child\">;
    - group title = first <span class=\"i_name org\"> or <span class=\"i_name ci\">
      inside the group's first <div class=\"ved_pl dtable group\">;
    - indicators = i_actual divs at depth >= 2 under the group's
      <div class=\"ved_child\"> (the R xpath 'div//*[@class=...]' searches
      BELOW the ved_child's child divs, so i_actual divs sitting directly
      under the ved_child are NOT selected; deeper subgroups still roll up
      to the first-level group). class=\"ved_item group i_excluded hide\"
      and friends are NOT matched;
    - <span class=\"i_name\"> may carry extra attributes (e.g. style on
      hidden indicators) - matched by class prefix, not by exact tag;
    - ids come from every <a href>: \"/indicator/<id>\" -> <id>, href=\"#\"
      -> hidden; hidden indicators are renumbered #1, #2, ... globally in
      order of appearance (R: cumsum by id);
    - rows are unique by [department group id], first occurrence wins
      (R: data.table unique)."
  (:require
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; div tree
;;
;; The page is not valid XML, but div nesting is well-formed enough for a
;; token scanner. Nodes know their open/close offsets so titles and <a>
;; contents can be extracted from the original string on demand.

(def ^:private div-token-re #"(?is)<div\b[^>]*>|</div>")
(def ^:private span-org-re #"(?is)<span class=\"i_name org\"[^>]*>([^<]*)</span>")
(def ^:private span-org-or-ci-re
  #"(?is)<span class=\"i_name(?: org| ci)?\"[^>]*>([^<]*)</span>")
(def ^:private span-name-re #"(?is)<span class=\"i_name\"[^>]*>([^<]*)</span>")
(def ^:private a-tag-re #"(?is)<a\b[^>]*>.*?</a>")
(def ^:private href-re #"(?is)href=\"([^\"]*)\"")
(def ^:private orgs-tree-re #"(?is)<div[^>]*id=\"orgsTree\"[^>]*>")

(defn- div-attr
  "Attribute value from a div open tag (nil when absent)."
  [^String tag ^String name]
  (second (re-find (re-pattern (str "(?is)\\b" name "\\s*=\\s*\"([^\"]*)\"")) tag)))

(defn- build-div-tree
  "Parse the div structure of s starting just after an opening <div> tag
  (at open-start..open-end). Returns the root node for that div with
  nested :children. Unclosed divs at the end of input are closed at the
  end of the string."
  [^String s open-start open-end]
  (let [open-tag (subs s open-start open-end)
        m (re-matcher div-token-re (subs s open-end))
        root {:open-start open-start
              :open-end open-end
              :class (div-attr open-tag "class")
              :id (div-attr open-tag "id")
              :children []}]
    (loop [stack [root]]
      (if (.find m)
        (let [tok-start (+ open-end (.start m))
              tok-end (+ open-end (.end m))
              tok (.group m)]
          (if (str/starts-with? tok "<div")
            (recur (conj stack {:open-start tok-start
                                :open-end tok-end
                                :class (div-attr tok "class")
                                :id (div-attr tok "id")
                                :children []}))
            ;; </div>: close the innermost open node, attach to its parent
            (let [done (assoc (peek stack) :close-end tok-start)
                  stack' (pop stack)]
              (if (seq stack')
                (recur (conj (pop stack') (update (peek stack') :children conj done)))
                done))))
        ;; end of input: fold any unclosed nodes back up to the root
        (loop [stack stack]
          (let [done (assoc (peek stack) :close-end (count s))
                stack' (pop stack)]
            (if (seq stack')
              (recur (conj (pop stack') (update (peek stack') :children conj done)))
              done)))))))

(defn- first-child-div
  "First child div whose class attribute equals cls exactly."
  [node cls]
  (first (filter #(= cls (:class %)) (:children node))))

(defn- descendants-with-class
  "All descendant divs with class attribute exactly cls, document order."
  [node cls]
  (mapcat (fn [c]
            (concat (when (= cls (:class c)) [c])
                    (descendants-with-class c cls)))
          (:children node)))

(defn- ^String node-region
  [^String s node]
  (subs s (:open-end node) (:close-end node)))

(defn- ^String head-region
  "Text of node before its first child div."
  [^String s node]
  (subs s (:open-end node) (or (:open-start (first (:children node)))
                               (:close-end node))))

(defn- ^String tail-region
  "Text of node after its last child div."
  [^String s node]
  (subs s (or (:close-end (peek (:children node))) (:open-end node))
        (:close-end node)))

(defn- first-descendant
  "First regex match group (document order) among node's own head, all
  descendants, then its own tail."
  [^String s node re]
  (or (second (re-find re (head-region s node)))
      (some #(first-descendant s % re) (:children node))
      (second (re-find re (tail-region s node)))))

;; ---------------------------------------------------------------------------
;; parse

(defn- svc-item-divs
  "i_actual item divs under a group's ved_child, mirroring the R xpath
  'div//*[@class=\"ved_item group i_actual\"]': elements under the ved_child's
  child divs, i.e. depth >= 2 — direct children of the ved_child itself are
  NOT selected (verified against fedstatAPIr 1.1.0 on the live page:
  departments whose i_actual items sit directly under the group's ved_child
  are absent from fedstat_indicator_info output)."
  [ved-child]
  (mapcat (fn [c]
            (cond->> (descendants-with-class c "ved_item group i_actual")
              (= "ved_item group i_actual" (:class c)) (drop 1)))
          (:children ved-child)))

(defn- item-rows
  "Rows of one indicator item div: one per <a> (R scans all <a> and all
  a//*[@class=\"i_name\"] positionally; a link without href yields NA id and
  is dropped at the end, so we skip it right away)."
  [^String s item-node department group]
  (keep (fn [a-tag]
          (let [href (second (re-find href-re a-tag))]
            (when href
              {:department department
               :group group
               ;; R: sub("/indicator/", "", href) - first occurrence removed
               :id (str/replace-first href "/indicator/" "")
               :title (or (second (re-find span-name-re a-tag)) "")})))
        (re-seq a-tag-re (node-region s item-node))))

(defn parse-indicators
  "Parse the organizations page HTML into a vector of indicator rows
  {:department :group :id :title :hidden}, mirroring fedstat_indicator_info
  (R). :group is nil for rows without a thematic group (NA in R)."
  [^String html]
  (let [m (re-matcher orgs-tree-re html)
        items (loop [acc []]
                (if (.find m)
                  (let [root (build-div-tree html (.start m) (.end m))]
                    (recur (into acc (:children root))))
                  acc))
        raw-rows (mapcat
                  (fn [item]
                    (let [department (first-descendant html item span-org-re)
                          ved-child (first-child-div item "ved_child")
                          groups (or (:children ved-child) [])]
                      (mapcat
                       (fn [group-node]
                         (let [group (when-let [pl (first-child-div group-node
                                                                    "ved_pl dtable group")]
                                       (first-descendant html pl span-org-or-ci-re))
                               sub-ved-child (first-child-div group-node "ved_child")
                               item-divs (if sub-ved-child
                                           (svc-item-divs sub-ved-child)
                                           [])]
                           (mapcat #(item-rows html % department group) item-divs)))
                       groups)))
                  items)
        ;; hidden numbering is global in order of appearance (R: cumsum by id)
        rows (loop [rs raw-rows n 0 acc (transient [])]
               (if (seq rs)
                 (let [row (first rs)]
                   (if (= "#" (:id row))
                     (recur (rest rs) (inc n)
                            (conj! acc {:department (:department row)
                                        :group (:group row)
                                        :id (str "#" (inc n))
                                        :title (:title row)
                                        :hidden true}))
                     (recur (rest rs) n (conj! acc (assoc row :hidden false)))))
                 (persistent! acc)))
        seen (volatile! #{})]
    ;; R: unique by (department, group, id), first occurrence wins
    (reduce (fn [acc row]
              (let [k ((juxt :department :group :id) row)]
                (if (contains? @seen k)
                  acc
                  (do (vswap! seen conj k)
                      (conj acc row)))))
            []
            rows)))

(defn indicator-info
  "Download and parse the organizations page via transport (fn of a request
  map, see fedstat.post). Returns the same as parse-indicators."
  [transport {:keys [timeout-seconds] :or {timeout-seconds 180}}]
  (let [resp (transport {:method :get
                         :url "https://www.fedstat.ru/organizations/"
                         :timeout-seconds timeout-seconds})]
    (when (>= (:status resp) 400)
      (throw (ex-info (str "Failed to download indicator info from fedstat.ru. "
                           "HTTP status: " (:status resp))
                      {:status (:status resp)})))
    (parse-indicators (String. ^bytes (:body resp) "UTF-8"))))
