(ns pull-automerge.core
  (:gen-class
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler])
  (:require
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

(defn get-opened-labelled-issues [org repo label options]
  (def pulls-result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/issues"
      "?labels=" label
      "&state=open"
      "&page=0&per_page=1"
      "&sort=created&direction=asc")
    options))
  (println "Retrieve automerge issues status: " (pulls-result :status))
  (parse-string (pulls-result :body) true))

(defn remove-label [options org repo issue-number label reason]
  (println reason)
  (println (str "Removing label '" label "'"))
  (def delete-url 
    (str "https://api.github.com/repos/" org "/" repo 
      "/issues/" issue-number "/labels/" label))
  (def delete-result @(http/delete delete-url options))
  (println "Delete label result:" (delete-result :status)))

(defn get-pull-request [options org repo pull-number]
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number) options))
  (println (str "Get pull request " pull-number " status: " (result :status)))
  (parse-string (result :body) true))

(defn get-oldest-issue-as-pull-request [pulls org repo label options]
  (if (= 0 (count pulls))
    (do (println (str "No automergeable issues in '" 
          org "/" repo "' with label '" label "'"))
        nil)
    (do
      (def pull (first pulls))
      (def pull-number (pull :number))
      (println "Found Issue with Title/Number: " (pull :title) "/" pull-number)
      (if (nil? (pull :pull_request))
        (do 
          (remove-label options org repo pull-number label
            "Issue has no key 'pull_request' present, so it must not be a pull request")
          nil)
        (do (get-pull-request options org repo pull-number))))))

(defn check-rate-limit [options]
  (def result @(http/get "https://api.github.com/rate_limit" options))
  (def rate-limit (((parse-string (result :body) true) :resources) :core))
  (println (str "Rate limit: " (rate-limit :limit)
    ", remaining: " (rate-limit :remaining))))

(defn update-pull-branch
[options org repo pull-number label source-branch target-branch]
  (println (str "PR# " pull-number " is out of date."
    " Merging '" source-branch "' into '" target-branch "'"))
  (def body (generate-string { :head source-branch :base target-branch }))
  (def all-options (merge options {:body body}))
  (def result @(http/post
    (str "https://api.github.com/repos/" org "/" repo "/merges" ) all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label options org repo pull-number label
      (str "MERGE ERROR: " (result :error)))))

(defn squash-merge-pull [options org repo pull-number pull-title label]
  (println "Attempting to squash merge PR#" pull-number)
  (def body (generate-string {:commit_title pull-title :merge_method "squash"}))
  (def all-options (merge options {:body body}))
  (def result @(http/put
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number "/merge")
      all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label options org repo pull-number label
      (str "MERGE ERROR: " (result :error)))))

(defn statuses-for-ref [options org repo ref]
  (println "Getting statuses for" ref)
  (def result @(http/get 
    (str "https://api.github.com/repos/" org "/" repo "/commits/" ref "/statuses")
    options))
  (println "Get statuses result:" (result :status))
  result)

(defn handle-blocked-state [options org repo label pull state]
  (def head-sha ((pull :head) :sha))
  (def statuses-result (statuses-for-ref options org repo head-sha))
  (def statuses (parse-string (statuses-result :body)))
  (def latest-jenkins-status (first (filter 
    (fn [status] (= "continuous-integration/jenkins/branch" (get status "context")))
      statuses)))
  (if (= nil latest-jenkins-status) (do
    (println (str "Could not find jenkins status for " head-sha ":"
      " Must wait for jenkins status to be reported."))))
  (def jenkins-state (get latest-jenkins-status "state"))
  (println (str "Lastest jenkins status is '" jenkins-state "'."))
  (if (= "pending" jenkins-state) 
    (println "Must wait for jenkins result.")
    (do 
      (remove-label options org repo pull-number label
      (str "Pull request's 'mergeable_state is' '" state "': "
        " lacks approval or has requested changes")))))

(defn execute [args]
  (def token (first args))
  (def org "ai-labs-team")
  (def repo "axiom-platform")
  (def label "Automerge")
  (def options (generate-options token))
  (check-rate-limit options)

  (def pulls (get-opened-labelled-issues org repo label options))

  (def pull (get-oldest-issue-as-pull-request pulls org repo label options))

  (if (not (nil? pull))
    (do
      (def state (pull :mergeable_state))
      (println "mergeable_state is" state)

      (def state-map 
        {"clean" 
          (fn [] (squash-merge-pull options org repo pull-number (pull :title) label)),
        "dirty"
          (fn [] (remove-label options org repo pull-number label
            (str "Pull request's 'mergeable_state is' '" state "'"))),
        "behind"
          (fn [] (update-pull-branch options org repo pull-number label ((pull :base) :ref) ((pull :head) :ref))),
        "blocked"
          (fn [] (handle-blocked-state options org repo label pull state))})
      (def handle-pull (state-map state))
      (handle-pull)
  0)))

; ***************************************************************************
; EXECUTE FROM COMMAND LINE
; ***************************************************************************
(defn -main [& args] (execute args))

; ***************************************************************************
; EXECUTE BASED ON AWS EVENT
; ***************************************************************************
(defn execute-event [event] (execute [(event :user-token)]))

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
(defn -handleRequest [this input-stream output-stream]
  (let [w (io/writer output-stream)]
    (-> (json/read (io/reader input-stream) :key-fn key->keyword)
        (execute-event)
        (json/write w))
    (.flush w)))
