(ns pull-automerge.core
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler])
  (:require
    [clojure.pprint :refer [pprint]]
    [org.httpkit.client :as http] 
    [cheshire.core :refer :all]
  ))
  
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

(defn get-pull-search-url [org repo label]
  (str "https://api.github.com/repos/" org "/" repo "/issues"
      "?labels=" label
      "&state=open"
      "&page=0&per_page=1"
      "&sort=created&direction=asc"))

(defn generate-delete-label-url [org repo issue-number label]
  (str 
  "https://api.github.com/repos/" org "/" repo "/issues/" issue-number "/labels/" label))

; (defn merge-pull-number [pull-number options]
;   (println "Merging PR#" pull-number)
;     (def merge-result @(http/put (generate-merge-url pull-number) options))
;     (println "Merge status: " (merge-result :status))
;     (if (merge-result :error)
;       (println "MERGE ERROR: " (merge-result :error))))

; (defn try-merge [pull-url options]
;   (def pull-result @(http/get pull-url options))
;     (println "Retrieve pull status: " (pull-result :status))
;     (def pull (parse-string (pull-result :body) true))
;     (if (and (= (pull :mergeable) true) (= (pull :mergeable_state) "clean"))
;       (merge-pull-number (pull :number) options)
;       (println (str "Cannot merge oldest PR#" (pull :number) ":")
;         "\n  mergeable =" (pull :mergeable)
;         "\n  mergeable_state =" (pull :mergeable_state))))

(defn -main
  [& args]
  (def token (first args))
  (def org "ai-labs-team")
  (def repo "axiom-platform")
  (def label "Automerge")
  (def options (generate-options token))
  ; TODO crawl to /issues from root url
  (def pulls-result @(http/get 
    (get-pull-search-url org repo label)
    options))
  (println "Retrieve automerge pulls status: " (pulls-result :status))
  (def pulls(parse-string (pulls-result :body) true))
  (if (= 0 (count pulls))
    (do    
      (println (str "No automergeable pull requests in '" 
        org "/" repo "' with label '" label "'"))
      (println "Exiting...")
      (System/exit 0)))
  (def pull (first pulls))
  (def pull-number (pull :number))
  (println "Found Issue with Title/Number: " (pull :title) "/" pull-number)
  (if (nil? (pull :pull_request))
    (do
      (println "No key 'pull_request' present. Removing label" label)
      (def delete-url (generate-delete-label-url org repo pull-number label))
      (def delete-result @(http/delete delete-url options))
      (println "Delete label result:" (delete-result :status)))
  )
)
