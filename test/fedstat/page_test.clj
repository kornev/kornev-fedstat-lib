(ns fedstat.page-test
  (:require
   [clojure.string :as str]
   [clojure.test :as t]
   [fedstat.page :as page]))

(def page-html (slurp "test/fixtures/page-31074.html"))

(t/deftest csrf-token-test
  (let [{:keys [token-name token]} (page/csrf-token page-html)]
    (t/is (= "token" token-name))
    (t/is (string? token))
    (t/is (not (str/blank? token)))))

(t/deftest script-nodes-test
  (let [scripts (page/script-nodes page-html)]
    (t/is (= 23 (count scripts)))
    ;; parity with the R package: filters live in the 12th script node (index 11)
    (t/is (every? #(not (str/includes? % "filters: {")) (take 11 scripts)))
    (t/is (str/includes? (nth scripts 11) "filters: {"))
    (t/is (= 11 (page/filters-script-index page-html)))))

(def filters-script-lines
  (str/split-lines (nth (page/script-nodes page-html) 11)))

(t/deftest parse-filters-js-test
  (let [m (page/parse-filters-js filters-script-lines)]
    (t/is (= #{"0" "3" "57937" "58273" "57831" "30611" "33560"} (set (keys m))))
    (t/is (= "Показатель" (get-in m ["0" "title"])))
    (t/is (= "Год" (get-in m ["3" "title"])))
    (t/is (= 25 (count (get-in m ["3" "values"]))))
    (t/is (= "2002" (get-in m ["3" "values" "2002" "title"])))
    (t/is (= 1105 (count (get-in m ["58273" "values"]))))
    (t/is (= 119 (count (get-in m ["57831" "values"]))))))

(t/deftest parse-object-ids-js-test
  (t/is (= [["lineObjectIds" ["57831" "58273"]]
            ["columnObjectIds" ["3" "33560" "57937"]]
            ["lineObjectIds" []]
            ["lineObjectIds" ["30611"]]]
           (page/parse-object-ids-js filters-script-lines))))

(t/deftest js-fragment->edn-test
  (t/is (= {"3" {"title" "Год", "values" {"2002" {"title" "2002"},
                                          "2003" {"title" "2003"}}}}
           (page/js-fragment->edn ["3: {"
                                   "    title: 'Год',"
                                   "    values: {"
                                   "        2002: {title: '2002'},"
                                   "        2003: {title: '2003'}"
                                   "    }"
                                   "}"])))
  (t/is (= {"a" ["1" "2"]} (page/js-fragment->edn ["a: [", "1, 2", "]"])))
  ;; odd number of quotes on a line: falls back to whole-text processing
  ;; (parity with the R original). A string literal spanning several lines
  ;; is not supported by the quote trick and fails JSON parsing, exactly
  ;; like in R.
  (t/is (thrown? Exception
                 (page/js-fragment->edn ["a: {title: 'x"
                                         "y'},"]))))
