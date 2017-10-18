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
      "repo:axiom-platform"
      "org:ai-labs-team"
      "type:pr"
      "state:open"
      "label:Automerge"
      ])
    "&sort=created&order=asc"))

(defn generate-merge-url [pr-number]
  (str "https://api.github.com/repos/AdamReifsneider/api-testing/pulls/" pr-number "/merge"))

(defn merge-pull-number [pull-number options]
  (println "Merging PR#" pull-number)
    (def merge-result @(http/put (generate-merge-url pull-number) options))
    (println "Merge status: " (merge-result :status))
    (if (merge-result :error)
      (println "MERGE ERROR: " (merge-result :error))))

(defn try-merge [pull-url options]
  (def pull-result @(http/get pull-url options))
    (println "Retrieve pull status: " (pull-result :status))
    (def pull (parse-string (pull-result :body) true))
    (if (and (= (pull :mergeable) true) (= (pull :mergeable_state) "clean"))
      (merge-pull-number (pull :number) options)
      (println (str "Cannot merge oldest PR#" (pull :number) ":")
        "\n  mergeable =" (pull :mergeable)
        "\n  mergeable_state =" (pull :mergeable_state))))

(defn -main
  [& args]
  (def options (generate-options (first args)))
  (def auto-pulls-result @(http/get (get-pull-search-url) options))
  (println "Retrieve automerge pulls: " (auto-pulls-result :status))
  (def auto-pulls ((parse-string(auto-pulls-result :body) true) :items))
  (println "Automerge pull count: " (count auto-pulls))
  (if (> (count auto-pulls) 0)
    (try-merge (((first auto-pulls) :pull_request) :url) options)
    (println "No automergeable pull requests")))
  