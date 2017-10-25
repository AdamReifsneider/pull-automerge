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

(defn remove-label-and-exit [options org repo issue-number label reason]
  (println reason)
  (println (str "Removing label '" label "'"))
  (def delete-url (generate-delete-label-url org repo issue-number label))
  (def delete-result @(http/delete delete-url options))
  (println "Delete label result:" (delete-result :status))
  (System/exit 0))

(defn get-pull-request [options org repo pull-number]
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number) options))
  (println (str "Get pull request " pull-number " status: " (result :status)))
  (parse-string (result :body) true))

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
    (do (remove-label-and-exit options org repo pull-number label
      "Issue has no key 'pull_request' present, so it must not be a pull request")))

  (def pull (get-pull-request options org repo pull-number))
  (def state (pull :mergeable_state))
  (def mergeable (pull :mergeable))

  (if (or (= "dirty" state) (= "blocked" state))
    (do (remove-label-and-exit options org repo pull-number label
      (str "Pull request's mergeable_state is '" state "'"))))

  (println "staate:" state "mergeable_state:" mergeable)

  (if (not mergeable)
    (do (do (remove-label-and-exit options org repo pull-number label
    (str "Pull request is not mergeable ")))))

  (println "No action taken")
)

;;X mergeable_state dirty means merge conflict?
;;X mergeable_state blocked means requests changes?
;;mergeable_state behind means out of date
;;mergeable_state clean means it can be merged?
;;X mergeable means the github can do an auto merge

;;to update a PR: https://developer.github.com/v3/pulls/#update-a-pull-request

;;behind takes priority over blocked -- maybe check out statuses EEEEEWWWWWW

;;1142: merge conflicts, approved
;; mergeable_state: dirty
;; mergeable: false

;;1247 out of date, no merge conflicts, needs review, tests pass
;; mergeable_state: behind
;; mergeable: true (i have admin, though...)

;;1178 up to date, approved, tests pass
;; mergeable_state: clean
;; mergeable: true

;;8 up to date, no merge conflicts, requested changes
;;mergeable_state: blocked
;;mergeable: true

;;1189 out of date, no merge conflicts, requested changes
