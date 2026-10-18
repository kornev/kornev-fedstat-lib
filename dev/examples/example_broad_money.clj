#!/usr/bin/env bb
;; ============================================================================
;; example_broad_money.clj
;; End-to-end live example: fedstat-lib on babashka.
;;
;; Closest M2-related series published on fedstat.ru: indicator 62278
;; "Годовой темп роста широкой денежной массы" (Банк России, ЦУР).
;; Note: the money multiplier itself (M2 multiplier) is NOT published on
;; fedstat.ru - the catalog (5172 indicators) has no "мультипликатор";
;; CBR's fedstat section carries SDG indicators only. The multiplier lives
;; on cbr.ru (Денежно-кредитная статистика), not EMISS.
;;
;; Run from the fedstat-lib directory:
;;   bb dev/examples/example_broad_money.clj
;; ============================================================================
(ns example-broad-money
  (:require [fedstat.core :as fedstat]
            [fedstat.indicators :as indicators]
            [fedstat.transport :as transport]))

(def indicator-id "62278")

(defn- find-indicator []
  (let [catalog (indicators/indicator-info transport/default-transport {})]
    (some #(when (= indicator-id (:id %)) %)
          catalog)))

(defn -main [& _]
  (println "== catalog: indicator" indicator-id "==")
  (when-let [info (find-indicator)]
    (println (:department info) "/" (:group info))
    (println (:title info)))

  (println "\n== get-data-ids ==")
  (let [data-ids (fedstat/get-data-ids indicator-id {:transport transport/default-transport})]
    (doseq [[_ rows] (group-by :filter-field-title (:rows data-ids))]
      (println " " (:filter-field-title (first rows)) "- values:" (count rows))))

  (println "\n== load-with-filters: all years ==")
  (let [table (fedstat/load-with-filters indicator-id
                                         {:transport transport/default-transport
                                          :filters {"Год" "*"}
                                          :loading-steps-verbose? false})]
    (doseq [row table]
      (println (str " " (get row "Time") ": " (get row "ObsValue") " " (get row "EI"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
