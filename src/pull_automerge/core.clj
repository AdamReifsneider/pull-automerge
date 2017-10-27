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

(defn exit [] (System/exit 0))

(defn remove-label-and-exit [options org repo issue-number label reason]
  (println reason)
  (println (str "Removing label '" label "'"))
  (def delete-url (generate-delete-label-url org repo issue-number label))
  (def delete-result @(http/delete delete-url options))
  (println "Delete label result:" (delete-result :status))
  (exit))

(defn get-pull-request [options org repo pull-number]
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number) options))
  (println (str "Get pull request " pull-number " status: " (result :status)))
  (parse-string (result :body) true))

(defn update-pull-branch-and-exit 
[options org repo pull-number label source-branch target-branch]
  (println (str "PR# " pull-number " is out of date."
    " Merging '" source-branch "' into '" target-branch "'"))
  (def body (generate-string { :head source-branch :base target-branch }))
  (def all-options (merge options {:body body}))
  (def result @(http/post
    (str "https://api.github.com/repos/" org "/" repo "/merges" ) all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label-and-exit options org repo pull-number label 
      (str "MERGE ERROR: " (result :error))))
  (exit))

(defn squash-merge-pull-and-exit [options org repo pull-number pull-title label]
  (println "Attemption to squash merge PR#" pull-number)
  (def all-options (merge options 
    {
      :commit_title (pull-title)
      :merge_method "squash"
    }))
  (def result @(http/put
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number "/merge")
      all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label-and-exit options org repo pull-number label 
      (str "MERGE ERROR: " (result :error))))
  (exit))

(defn statuses-for-ref [options org repo ref]
  (println "Getting statuses for" ref)
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/commits/" ref "/statuses")
    options))
  (println "Get statuses result:" (result :status))
  result)

(defn -main
  [& args]
  (def token (first args))
  (def org "ai-labs-team")
  (def repo "axiom-platform")
  (def label "Automerge")
  (def options (generate-options token))
  ; TODO crawl to /issues from root url

  ; ***************************************************************************
  ; GET OPEN LABELED ISSUES
  ; ***************************************************************************
  (def pulls-result @(http/get 
    (get-pull-search-url org repo label)
    options))
  (println "Retrieve automerge issues status: " (pulls-result :status))
  (def pulls (parse-string (pulls-result :body) true))

  ; ***************************************************************************
  ; EXIT IF NO LABELED ISSUES FOUND
  ; ***************************************************************************
  (if (= 0 (count pulls))
    (do
      (println (str "No automergeable issues in '" 
        org "/" repo "' with label '" label "'"))
      (println "Exiting...")
      (exit)))

  ; ***************************************************************************
  ; SET OLDEST ISSUE
  ; ***************************************************************************
  (def pull (first pulls))
  (def pull-number (pull :number))
  (println "Found Issue with Title/Number: " (pull :title) "/" pull-number)

  ; ***************************************************************************
  ; REMOVE LABEL FROM NON-PULL-REQUEST
  ; ***************************************************************************
  (if (nil? (pull :pull_request))
    (remove-label-and-exit options org repo pull-number label
      "Issue has no key 'pull_request' present, so it must not be a pull request"))

  ; ***************************************************************************
  ; RETRIEVE ISSUE AS PULL REQUEST
  ; ***************************************************************************
  (def pull (get-pull-request options org repo pull-number))
  (def head-sha ((pull :head) :sha))
  (def state (pull :mergeable_state))
  (println "mergeable_state is" state)
  (print-json-object pull)

  ; ***************************************************************************
  ; MERGE PULL REQUEST
  ; ***************************************************************************
  (if (= "clean" state)
    (squash-merge-pull-and-exit options org repo pull-number (pull :title) label))

  ; ***************************************************************************
  ; REMOVE LABEL FROM CONFLICTED BRANCH
  ; ***************************************************************************
  (if (= "dirty" state)
    (remove-label-and-exit options org repo pull-number label
      (str "Pull request's 'mergeable_state is' '" state "'")))
  
  ; ***************************************************************************
  ; UPDATE OUT OF DATE BRANCH
  ; ***************************************************************************
  (def head-branch ((pull :head) :ref))
  (def base-branch ((pull :base) :ref))
  (if (= "behind" state)
    (update-pull-branch-and-exit options org repo pull-number label base-branch head-branch))

  ; ***************************************************************************
  ; WAIT TO POLL AGAIN IF JENKINS CHECK IS PENDING
  ; ***************************************************************************
  (if (= "blocked" state) (do 
    (def statuses-result (statuses-for-ref options org repo head-sha))
    (def statuses (parse-string (statuses-result :body)))
    ; (println "status context:" (get (first statuses) "context"))
    (def latest-jenkins-status (first (filter 
      (fn [status] (= "continuous-integration/jenkins/branch" (get status "context")))
    statuses)))
    (def jenkins-state (get latest-jenkins-status "state"))
    (if (= "pending" jenkins-state) (do
      (println "Latest jenkins status is 'pending'. Must wait for result")))))

  ; ***************************************************************************
  ; REMOVE LABEL BECAUSE PULL REQUEST LACKS APPROVAL
  ; ***************************************************************************
  (if (= "blocked" state)
    (remove-label-and-exit options org repo pull-number label
      (str "Pull request's 'mergeable_state is' '" state "': "
      " lacks approval or has requested changes")))

  (println "No action taken on pull request" pull-number)
)
