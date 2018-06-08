# pull-automerge

Automatically merge all pull requests in the repo with the `Automerge` label from oldest to newest.

## Running it

`lein run [github access token]`

## How it works

This is a stateless run-once application. That is meant to be run in a polling fashion using something like AWS.

During one run it:
1)
Finds the oldest pull request in the repository with the `Automerge` label

2)
If that PR has merge conflicts or a github status has failed (e.g. CI check failure), the label on that PR is removed and the app exits.
If that PR still has outstanding github statuses (that is, they are currently still running), nothing is done and the app exits.
If that PR has no merge conflicts and all github statuses have passed, the PR is merged and the branch is deleted.

## AWS Tip

It expects a JSON Constant in the events when run through AWS like this: `{ "userToken": "<USER_TOKEN>" }`