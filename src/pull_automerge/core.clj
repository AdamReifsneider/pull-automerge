(ns pull-automerge.core
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler])
  (:require
    [clojure.data.json :as json]
    [clojure.pprint :refer [pprint]]
    [clojure.data.json :as json]
    [clojure.string :as s]
    [clojure.java.io :as io]
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

(defn get-pull-search-url [org repo & labels]
  (str "https://api.github.com/repos/" org "/" repo "/issues"
      "?labels=" (apply str(interpose "," labels))
      "&state=open"
      "&sort=created&direction=asc"))

(defn generate-delete-label-url [org repo issue-number label]
  (str 
  "https://api.github.com/repos/" org "/" repo "/issues/" issue-number "/labels/" label))

(defn exit [context]
  (if (not= context nil) (.done context nil 0))
  (System/exit 0))

(defn check-rate-limit [options]
  (def result @(http/get "https://api.github.com/rate_limit" options))
  (def rate-limit (((parse-string (result :body) true) :resources) :core))
  (println (str "Rate limit: " (rate-limit :limit)
    ", remaining: " (rate-limit :remaining))))

(defn remove-label-and-exit [options org repo issue-number label reason context]
  (println reason)
  (println (str "Removing label '" label "'"))
  (def delete-url (generate-delete-label-url org repo issue-number label))
  (def delete-result @(http/delete delete-url options))
  (println "Delete label result:" (delete-result :status))
  (exit context))

(defn get-pull-request [options org repo pull-number]
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number) options))
  (println (str "Get pull request " pull-number " status: " (result :status)))
  (parse-string (result :body) true))

(defn update-pull-branch-and-exit 
[options org repo pull-number label source-branch target-branch context]
  (println (str "PR# " pull-number " is out of date."
    " Merging '" source-branch "' into '" target-branch "'"))
  (def body (generate-string { :head source-branch :base target-branch }))
  (def all-options (merge options {:body body}))
  (def result @(http/post
    (str "https://api.github.com/repos/" org "/" repo "/merges" ) all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label-and-exit options org repo pull-number label context
      (str "MERGE ERROR: " (result :error))))
  (exit context))

(defn squash-merge-pull-and-exit [options org repo pull-number pull-title label context]
  (println "Attempting to squash merge PR#" pull-number)
  (def body (generate-string {:commit_title pull-title :merge_method "squash"}))
  (def all-options (merge options {:body body}))
  (def result @(http/put
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number "/merge")
      all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label-and-exit options org repo pull-number label context
      (str "MERGE ERROR: " (result :error))))
  (exit context))

(defn statuses-for-ref [options org repo ref]
  (println "Getting statuses for" ref)
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/commits/" ref "/statuses")
    options))
  (println "Get statuses result:" (result :status))
  result)

;Get all objects in response data that contains the specified label
(defn filter-by-label [ response-data label ]
  (filter 
    #(> (count (filter (fn[m] (= (m :name) label)) (% :labels))) 0) 
    (json/read-str (response-data :body) :key-fn keyword)))

;Get entry from list with the earliest creation date
(defn get-oldest-entry [ coll ]
  (last (sort-by :created_at coll)))

(defn execute [args context]
  (def token (first args))
  (def org "ai-labs-team")
  (def repo "axiom-platform")
  (def label "Automerge")
  (def priority "High Priority")
  (def options (generate-options token))
  (check-rate-limit options)
 
  ; ***************************************************************************
  ; GET OPEN LABELED ISSUES
  ; ***************************************************************************
  (def pulls-result @(http/get
    (get-pull-search-url org repo label)
    options))
  (println "Retrieve automerge issues status: " (pulls-result :status))
  (def priorities (filter-by-label pulls-result priority))
  (def pull (get-oldest-entry priorities) )
  (if (= 0 (count priorities))
    (do
      (def pull  
	(get-oldest-entry (json/read-str (pulls-result :body) :key-fn keyword)))))
  (println "PR to merge: " pull)
 
  ; ***************************************************************************
  ; EXIT IF NO LABELED ISSUES FOUND
  ; ***************************************************************************
  (if (nil? pull)
    (do
      (println (str "No automergeable issues in '" 
        org "/" repo "' with label '" label "'"))
      (exit context)))

  ; ***************************************************************************
  ; SET OLDEST ISSUE
  ; ***************************************************************************
  (def pull-number (pull :number))
  (println "Found Issue with Title/Number: " (pull :title) "/" pull-number)

  ; ***************************************************************************
  ; REMOVE LABEL FROM NON-PULL-REQUEST
  ; ***************************************************************************
  (if (nil? (pull :pull_request))
    (remove-label-and-exit options org repo pull-number label context
      "Issue has no key 'pull_request' present, so it must not be a pull request"))

  ; ***************************************************************************
  ; RETRIEVE ISSUE AS PULL REQUEST
  ; ***************************************************************************
  (def pull (get-pull-request options org repo pull-number))
  (def head-sha ((pull :head) :sha))
  (def state (pull :mergeable_state))
  (println "mergeable_state is" state)

  ; ***************************************************************************
  ; MERGE READY PULL REQUEST
  ; ***************************************************************************
  (if (= "clean" state)
    (squash-merge-pull-and-exit options org repo pull-number (pull :title) label context))

  ; ***************************************************************************
  ; REMOVE LABEL FROM CONFLICTED BRANCH
  ; ***************************************************************************
  (if (= "dirty" state)
    (remove-label-and-exit options org repo pull-number label context
      (str "Pull request's 'mergeable_state is' '" state "'")))
  
  ; ***************************************************************************
  ; UPDATE OUT OF DATE BRANCH
  ; ***************************************************************************
  (def head-branch ((pull :head) :ref))
  (def base-branch ((pull :base) :ref))
  (if (= "behind" state)
    (update-pull-branch-and-exit options org repo pull-number label base-branch head-branch context))

  ; ***************************************************************************
  ; WAIT TO POLL AGAIN IF JENKINS CHECK IS PENDING
  ; ***************************************************************************
  (if (= "blocked" state) (do 
    (def statuses-result (statuses-for-ref options org repo head-sha))
    (def statuses (parse-string (statuses-result :body)))
    (def latest-jenkins-status (first (filter 
      (fn [status] (= "continuous-integration/jenkins/branch" (get status "context")))
    statuses)))
    (if (= nil latest-jenkins-status) (do
      (println (str "Could not find jenkins status for " head-sha ":"
      " Must wait for jenkins status to be reported."))
      (exit context)))
    (def jenkins-state (get latest-jenkins-status "state"))
    (println (str "Lastest jenkins status is '" jenkins-state "'."))
    (if (= "pending" jenkins-state) (do
      (println "Must wait for jenkins result.")
      (exit context)))))

  ; ***************************************************************************
  ; REMOVE LABEL BECAUSE PULL REQUEST LACKS APPROVAL
  ; ***************************************************************************
  (if (= "blocked" state)
    (remove-label-and-exit options org repo pull-number label context
      (str "Pull request's 'mergeable_state is' '" state "': "
      " lacks approval or has requested changes")))

  (println "No action taken on pull request" pull-number))

; ***************************************************************************
; EXECUTE FROM COMMAND LINE
; ***************************************************************************
(defn -main [& args] (execute args nil))

; ***************************************************************************
; EXECUTE BASED ON AWS EVENT
; ***************************************************************************
(defn execute-event [event context] (execute [(event :user-token)] context))

; ***************************************************************************
; MAP CAMEL KEYS TO KEBAB
; ***************************************************************************
(defn key->keyword [key-string]
  (-> key-string
      (s/replace #"([a-z])([A-Z])" "$1-$2")
      (s/replace #"([A-Z]+)([A-Z])" "$1-$2")
      (s/lower-case)
      (keyword)))

; ***************************************************************************
; READ IN AWS EVENT TO BE EXECUTED
; ***************************************************************************
(defn -handleRequest [this input-stream output-stream context]
  (let [w (io/writer output-stream)]
    (-> (json/read (io/reader input-stream) :key-fn key->keyword)
        (execute-event context)
        (json/write w))
    (.flush w)))
