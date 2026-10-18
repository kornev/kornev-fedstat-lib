(ns fedstat.indicators-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.indicators :as indicators]))

(def orgs-html (slurp "test/fixtures/orgs.html"))

;; ---------------------------------------------------------------------------
;; Whole-fixture parse (test/fixtures/orgs.html, captured 2026-10-14).
;; Expected values were produced by the ORIGINAL fedstatAPIr 1.1.0
;; (fedstat_indicator_info) run against this very fixture — see dev/verify/ —
;; so these tests pin parser parity with R.

(t/deftest parse-orgs-shape-test
  (let [rows (indicators/parse-indicators orgs-html)]
    ;; the R original returns unique(department, group, id) rows
    (t/is (= 5172 (count rows)))
    (t/is (every? #(= #{:department :group :id :title :hidden} (set (keys %)))
                  rows))
    (t/is (= 172 (count (filter :hidden rows))))
    (t/is (= 34 (count (distinct (map :department rows)))))))

(t/deftest parse-orgs-r-parity-test
  ;; byte-for-byte parity with fedstat_indicator_info output: the expected
  ;; TSV (department group id title) was dumped by R on this same fixture
  (let [rows (indicators/parse-indicators orgs-html)
        actual (->> rows
                    (map #(str/join "\t" (map (fn [k] (str (get % k)))
                                              [:department :group :id :title])))
                    sort
                    (str/join "\n"))
        expected (->> (slurp "test/fixtures/orgs-expected.tsv")
                      str/split-lines
                      (drop 1)
                      sort
                      (str/join "\n"))]
    (t/is (= expected actual))))

(t/deftest parse-orgs-dedup-test
  ;; R: unique by (department, group, id), first occurrence wins
  (let [rows (indicators/parse-indicators orgs-html)
        keys (map (juxt :department :group :id) rows)]
    (t/is (= 5172 (count (distinct keys))))))

(t/deftest parse-orgs-content-test
  (let [rows (indicators/parse-indicators orgs-html)]
    (t/is (some #(and (= "37429" (:id %))
                      (= "Наличие сельскохозяйственной техники (окончательные итоги)"
                         (:title %)))
                rows))
    (t/is (= "Федеральная служба государственной статистики"
             (:department (first rows))))))

(t/deftest parse-orgs-scope-test
  ;; only the #orgsTree subtree is read: the page also carries a flat
  ;; thematic tree (group ids without dots) that fedstatAPIr ignores
  (t/is (str/includes? orgs-html "id=\"group947208\""))
  (let [rows (indicators/parse-indicators orgs-html)]
    (t/is (every? #(not= "group947208" (:group %)) rows))
    (t/is (every? :department rows))))

;; ---------------------------------------------------------------------------
;; Synthetic fragments

(def synthetic
  (str "<div id=\"orgsTree\">"
       ;; department A with a group at depth 3 and a subgroup at depth 4
       "<div class=\"ved_item\">"
       "<div class=\"ved_pl dtable group\" id=\"group1.\">"
       "<div class=\"ved_plus dcell\">+</div>"
       "<div class=\"ved_txt dcell\"><span class=\"i_code\">1.</span>"
       "<span class=\"i_name org\">Dept A</span></div></div>"
       "<div class=\"ved_child\">"
       "<div class=\"ved_item\">"
       "<div class=\"ved_pl dtable group\" id=\"group1.100.200.\">"
       "<span class=\"i_name ci\">Group A</span></div>"
       "<div class=\"ved_child\">"
       "<div class=\"ved_item\">"
       "<div class=\"ved_pl dtable group\" id=\"group1.100.200.300.\">"
       "<span class=\"i_name ci\">Subgroup A1</span></div>"
       "<div class=\"ved_child\">"
       "<div class=\"ved_item group i_actual\">"
       "<a href=\"/indicator/111\" class=\"lnk ved_pl dtable\">"
       "<div class=\"ved_txt dcell\"><span class=\"i_name\">Title 1</span></div></a>"
       "</div>"
       "<div class=\"ved_item group i_actual\">"
       "<a href=\"/indicator/#\" class=\"lnk ved_pl dtable\">"
       "<div class=\"ved_txt dcell\"><span class=\"i_name\">Hidden one</span></div></a>"
       "</div>"
       "<div class=\"ved_item group i_actual\">"
       "<a href=\"/indicator/#\" class=\"lnk ved_pl dtable\">"
       "<div class=\"ved_txt dcell\"><span class=\"i_name\">Hidden two</span></div></a>"
       "</div>"
       "</div></div></div></div></div></div>"
       ;; flat department B: an i_actual div directly under the item's
       ;; ved_child - the R xpath 'div//*[@class=...]' only searches BELOW
       ;; the ved_child's child divs, so this yields no rows (verified
       ;; against fedstatAPIr 1.1.0: flat departments are absent from its
       ;; output; empty-group rows do not exist in the catalog)
       "<div class=\"ved_item\">"
       "<div class=\"ved_pl dtable group\" id=\"group2.\">"
       "<span class=\"i_name org\">Dept B</span></div>"
       "<div class=\"ved_child\">"
       "<div class=\"ved_item group i_actual\">"
       "<a href=\"/indicator/222\" class=\"lnk ved_pl dtable\">"
       "<div class=\"ved_txt dcell\"><span class=\"i_name\">Title 2</span></div></a>"
       "</div>"
       "</div></div>"
       "</div>"))

(t/deftest parse-synthetic-test
  (let [rows (indicators/parse-indicators synthetic)]
    (t/is (= 3 (count rows)))
    ;; subgroup indicators roll up to the first-level group
    (t/is (= {:department "Dept A"
              :group "Group A"
              :id "111"
              :title "Title 1"
              :hidden false}
             (first rows)))
    ;; hidden indicators are renumbered #1, #2 in order of appearance
    (t/is (= [{:department "Dept A" :group "Group A" :id "#1"
               :title "Hidden one" :hidden true}
              {:department "Dept A" :group "Group A" :id "#2"
               :title "Hidden two" :hidden true}]
             (filter :hidden rows)))
    ;; flat department: an i_actual directly under the item's ved_child is
    ;; treated as a group by R, has no ved_child of its own -> no rows
    (t/is (empty? (filter #(= "Dept B" (:department %)) rows)))))

(t/deftest parse-dedup-test
  ;; duplicate (department, group, id) appears once (first occurrence wins).
  ;; The i_actual items sit inside wrapper div.ved_item, like on the real
  ;; page (the R xpath 'div//*[@class=...]' only finds items at depth >= 2).
  (let [html (str "<div id=\"orgsTree\"><div class=\"ved_item\">"
                  "<div class=\"ved_pl dtable group\" id=\"group1.\">"
                  "<span class=\"i_name org\">Dept A</span></div>"
                  "<div class=\"ved_child\"><div class=\"ved_item\">"
                  "<div class=\"ved_pl dtable group\" id=\"group1.100.\">"
                  "<span class=\"i_name ci\">Group A</span></div>"
                  "<div class=\"ved_child\">"
                  "<div class=\"ved_item\"><div class=\"ved_item group i_actual\"><a href=\"/indicator/111\">"
                  "<span class=\"i_name\">T1</span></a></div></div>"
                  "<div class=\"ved_item\"><div class=\"ved_item group i_actual\"><a href=\"/indicator/111\">"
                  "<span class=\"i_name\">T1 dup</span></a></div></div>"
                  "</div></div></div></div></div>")
        rows (indicators/parse-indicators html)]
    (t/is (= 1 (count rows)))
    (t/is (= "T1" (:title (first rows))))))
