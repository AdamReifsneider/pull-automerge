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

(defn get-opened-labelled-issues [org repo options & labels]
  (def pulls-result @(http/get
    (str "https://api.github.com/repos/" org "/" repo "/issues"
      "?labels=" (apply str(interpose "," labels))
      "&state=open"
      "&sort=created&direction=asc")
    options))
  (println "Retrieve automerge issues status: " (pulls-result :status))
  (json/read-str (pulls-result :body) :key-fn keyword))

(defn remove-label [options org repo issue-number label reason]
  (println reason)
  (println (str "Removing label '" label "'"))
  (def delete-url
    (str "https://api.github.com/repos/" org "/" repo
      "/issues/" issue-number "/labels/" label))
  (def delete-result @(http/delete delete-url options))
  (println "Delete label result:" (delete-result :status)))

(defn delete-branch [options org repo branch]
  (println (str "Deleting branch '" branch "'"))
  (def delete-url (str "https://api.github.com/repos/" org "/" repo
    "/git/refs/heads/" branch))
  (def delete-result @(http/delete delete-url options))
    (println "Delete branch result:" (delete-result :status)))

(defn get-pull-request [options org repo pull-number]
  (def result @(http/get
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number) options))
  (println (str "Get pull request " pull-number " status: " (result :status)))
  (parse-string (result :body) true))

(defn contains-label [labels label]
  (> (count (filter #(= (% :name) label) labels)) 0))

(defn filter-by-label [ issues label ]
  (filter #(contains-label (% :labels) label) issues))

(defn get-oldest-issue-as-pull-request [pulls org repo label options priority-label]
  (def pull
    (or
      (first (filter-by-label pulls priority-label))
      (first pulls)))
  (if (nil? pull)
    (do (println (str "No automergeable issues in '"
          org "/" repo "' with label '" label "'"))
        nil)
    (do
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

(defn squash-merge-pull [options org repo pull-number pull-title label branch]
  (println "Attempting to squash merge PR#" pull-number " for branch '" branch "'")
  (def body (generate-string {:commit_title pull-title :merge_method "squash"}))
  (def all-options (merge options {:body body}))
  (def result @(http/put
    (str "https://api.github.com/repos/" org "/" repo "/pulls/" pull-number "/merge")
      all-options))
  (println "Merge result:" (result :status))
  (if (result :error)
    (remove-label options org repo pull-number label
      (str "MERGE ERROR: " (result :error)))
    (delete-branch options org repo branch)))

(defn statuses-for-ref [options org repo ref]
  (println "Getting statuses for" ref)
  (def result @(http/get
    (str "https://api.github.com/repos/" org "/" repo "/commits/" ref "/statuses")
      options))
  (println "Get statuses result:" (result :status))
  result)

(defn required-status-failed [head-sha pull-statuses required-status]
  (def latest-status (first (filter
    (fn [status] (= required-status (get status "context")))
      pull-statuses)))
  (if (= nil latest-status)
    (do
      (println (str "Could not find " required-status " status for " head-sha ":"
        " Must wait for status to be reported."))
      false)
    (do
      (def state (get latest-status "state"))
      (println (str "Lastest " required-status " status is '" state "'."))
      (if (= "pending" state)
        (do (println "Must wait for " required-status " result.") false)
        (not (= "success" state))))))

(defn handle-blocked-state [options org repo label pull state required-status-1 required-status-2 required-status-3 required-status-4 required-status-5 required-status-6 required-status-7 required-status-8 required-status-9 required-status-10 required-status-11 required-status-12 required-status-13 required-status-14]
  (def head-sha ((pull :head) :sha))
  (def statuses (parse-string ((statuses-for-ref options org repo head-sha) :body)))
  (def failed-1 (required-status-failed head-sha statuses required-status-1))
  (def failed-2 (required-status-failed head-sha statuses required-status-2))
  (def failed-3 (required-status-failed head-sha statuses required-status-3))
  (def failed-4 (required-status-failed head-sha statuses required-status-4))
  (def failed-5 (required-status-failed head-sha statuses required-status-5))
  (def failed-6 (required-status-failed head-sha statuses required-status-6))
  (def failed-7 (required-status-failed head-sha statuses required-status-7))
  (def failed-8 (required-status-failed head-sha statuses required-status-8))
  (def failed-9 (required-status-failed head-sha statuses required-status-9))
  (def failed-10 (required-status-failed head-sha statuses required-status-10))
  (def failed-11 (required-status-failed head-sha statuses required-status-11))
  (def failed-12 (required-status-failed head-sha statuses required-status-12))
  (def failed-13 (required-status-failed head-sha statuses required-status-13))
  (def failed-14 (required-status-failed head-sha statuses required-status-14))
  (println (str required-status-1 " failed: " failed-1))
  (println (str required-status-2 " failed: " failed-2))
  (println (str required-status-3 " failed: " failed-3))
  (println (str required-status-4 " failed: " failed-4))
  (println (str required-status-5 " failed: " failed-5))
  (println (str required-status-6 " failed: " failed-6))
  (println (str required-status-7 " failed: " failed-7))
  (println (str required-status-8 " failed: " failed-8))
  (println (str required-status-9 " failed: " failed-9))
  (println (str required-status-10 " failed: " failed-10))
  (println (str required-status-11 " failed: " failed-11))
  (println (str required-status-12 " failed: " failed-12))
  (println (str required-status-13 " failed: " failed-13))
  (println (str required-status-14 " failed: " failed-14))
  (if (or failed-1 failed-2 failed-3 failed-4 failed-5 failed-6 failed-7 failed-8 failed-9 failed-10 failed-11 failed-12 failed-13 failed-14)
    (remove-label options org repo pull-number label
      (str "Pull request's 'mergeable_state is' '" state "':"
        " lacks approval or has requested changes"))))

(defn execute [args]
  (def token (first args))
  (def org "ai-labs-team")
  (def repo "axiom-platform")
  (def label "Automerge")
  (def priority "High Priority")
  (def options (generate-options token))
  (check-rate-limit options)

  (def pulls (get-opened-labelled-issues org repo options label))

  (def pull (get-oldest-issue-as-pull-request pulls org repo label options priority))

  (if (not (nil? pull))
    (do
      (def state (pull :mergeable_state))
      (println "mergeable_state is" state)

      (def merge-pr (fn [] (squash-merge-pull options org repo pull-number (pull :title) label ((pull :head) :ref))))
      (def state-map
        {"clean"
          merge-pr,
        "unstable"
          merge-pr,
        "dirty"
          (fn [] (remove-label options org repo pull-number label
            (str "Pull request's 'mergeable_state is' '" state "'"))),
        "behind"
          (fn [] (update-pull-branch options org repo pull-number label ((pull :base) :ref) ((pull :head) :ref))),
        "blocked"
          (fn [] (handle-blocked-state options org repo label pull state
            "ci/circleci: clone_to_workspace" "ci/circleci: build_initial_admin_docker_image" "ci/circleci: build_initial_web_docker_image" "ci/circleci: build_initial_api_docker_image" "ci/circleci: build_e2e_docker_image" "ci/circleci: test_and_lint_api" "ci/circleci: test_and_lint_ui" "ci/circleci: api_integration_tests" "ci/circleci: end_to_end_test_admin" "ci/circleci: end_to_end_test" "ci/circleci: build_final_api_docker_image" "ci/circleci: build_final_web_docker_image" "ci/circleci: build_final_admin_docker_image" "codeclimate"))})
      (def handle-pull (get state-map state (fn [] ())))
      (handle-pull)
  0)))

; ***************************************************************************
; EXECUTE FROM COMMAND LINE
; ***************************************************************************
(defn -main [& args] (execute args))

; ***************************************************************************
; EXECUTE BASED ON AWS EVENT
; ***************************************************************************
(defn execute-event [event]
  (assert (not (nil? event)) "event is nil")
  (assert (not (nil? (event :user-token))) (str "event token is nil: " event))
  (execute [(event :user-token)]))

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
        (execute-event)
        (json/write w))
    (.flush w)))
