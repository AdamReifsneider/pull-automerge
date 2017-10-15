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

(defn get-pr-search-url []
  (str "https://api.github.com/search/issues?q="
    (clojure.string/join "+" [
      "repo:api-testing"
      "user:AdamReifsneider"
      "type:pr"
      "state:open"
      "label:automerge"
      ])
    "&sort=created&order=asc"))

(defn generate-options [token]
  {
    :headers {
      "Authorization" (str "token " token)
      "Accept" "application/vnd.github.v3+json"
    }
  })

(defn -main
  [& args]
  (def options (generate-options (first args)))
  (def auto-pulls-result (http/get (get-pr-search-url) options))
  (def auto-pulls ((parse-string(@auto-pulls-result :body) true) :items))
  (def auto-pull-urls (map (fn [pull] ((pull :pull_request) :url)) auto-pulls))
  (pprint auto-pull-urls)
)