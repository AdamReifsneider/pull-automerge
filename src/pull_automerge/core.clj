(ns pull-automerge.core
  (:require
    [clojure.pprint :refer [pprint]]
    [org.httpkit.client :as http] 
    [cheshire.core :refer :all]
  )
  (:gen-class))

(defn print-json-object [json-object]
  (println (generate-string json-object {:pretty true})))

(defn print-json-string [json-string]
  (print-json-object (parse-string json-string true)))

(defn generate-options [token]
  {
    :headers {
      "Authorization" (str "token " token)
      "Accept" "application/vnd.github.v3+json"
    }
  })

(defn get-pull-search-url []
  (str "https://api.github.com/search/issues?q="
    (clojure.string/join "+" [
      "repo:api-testing"
      "user:AdamReifsneider"
      "type:pr"
      "state:open"
      "label:automerge"
      ])
    "&sort=created&order=asc"))

(defn generate-merge-url [pr-number]
  (str "https://api.github.com/repos/AdamReifsneider/api-testing/pulls/" pr-number "/merge"))

(defn merge-pull-number [pull-number options]
  (println "MERGING PR#" pull-number)
    (def merge-result @(http/put (generate-merge-url pull-number) options))
    (println "MERGE STATUS: " (merge-result :status))
    (if (merge-result :error)
      (println "MERGE ERROR: " (merge-result :error))))

(defn -main
  [& args]
  (def options (generate-options (first args)))
  (def auto-pulls-result @(http/get (get-pull-search-url) options))
  (println "AUTO PULLS: " (auto-pulls-result :status))
  (def auto-pulls ((parse-string(auto-pulls-result :body) true) :items))
  ; (if (> 0 (count auto-pulls)))
  (println "AUTO PULL COUNT: " (count auto-pulls))
  (def pull1-result @(http/get (((first auto-pulls) :pull_request) :url) options))
  (println "PULL 1: " (auto-pulls-result :status))
  (def pull1 (parse-string (pull1-result :body) true))
  (println "MERGEABLE: " (pull1 :mergeable))
  (println "MERGEABLE_STATE: " (pull1 :mergeable_state))
  (if (and (= (pull1 :mergeable) true) (= (pull1 :mergeable_state) "clean"))
    (merge-pull-number (pull1 :number) options)
    (println (str "CANNOT MERGE OLDEST AUTOMERGE PR#" (pull1 :number) ":")
      "\n  mergeable =" (pull1 :mergeable)
      "\n  mergeable_state =" (pull1 :mergeable_state))))