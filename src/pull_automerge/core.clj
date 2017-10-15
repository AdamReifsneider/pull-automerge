(ns pull-automerge.core
  (:require 
    [org.httpkit.client :as http] 
    [cheshire.core :refer :all]
  )
  (:gen-class))

(defn with-title [pulls title] 
  (filter (fn [pull] (= (pull :title) title)) pulls))

(defn pull-by-title [body title]
  (first (with-title (parse-string body true) title)))

(defn print-json-object [json-object]
  (println (generate-string json-object {:pretty true})))

(defn print-json-string [json-string]
  (print-json-object (parse-string json-string true)))

(defn extract-root-keys [body keys]
  (map (fn [item] (select-keys item keys)) ((parse-string body true) :items)))

(defn print-pr-fields [body keys]
  (println "FOUND:\n" (clojure.string/join "\n" (extract-root-keys body keys))))

(defn query [url options]
  (let [{:keys [status body headers message]} @(http/get url options)]
    ; (println status)
    ; (print-json-string body)
    ; (print-pr-fields :id :title :pull_request)
  ))

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

(defn query-prs [options]
  (let 
    [{:keys [status body headers message]} @(http/get
    (get-pr-search-url)
    options)]
    (def pulls (extract-root-keys body [:id :title :pull_request]))
    (loop [pull (first pulls)] [pulls]
      (let 
        [{:keys [status body headers message]} @(http/get
          ((pull :pull_request) :url)
          options
        )]
        (print-json-string body)
      ))))

(defn generate-options [token]
  {
    :headers {
      "Authorization" (str "token " token)
      "Accept" "application/vnd.github.v3+json"
    }
  })

(defn -main
  [& args]
  (query-prs (generate-options (first args))))
