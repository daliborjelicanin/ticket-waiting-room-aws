#!/usr/bin/env bash
# One comment per kind on a PR, edited on each push rather than added again: the comment is
# found by a hidden marker, so the plan summary and the cost estimate (terraform.yml) never
# overwrite each other, as `gh pr comment --edit-last` would.
#
#   GH_TOKEN=... scripts/pr-comment.sh <pr-number> <marker> <body-file>
set -euo pipefail
pr=${1:?usage: pr-comment.sh <pr-number> <marker> <body-file>}
marker=${2:?usage: pr-comment.sh <pr-number> <marker> <body-file>}
file=${3:?usage: pr-comment.sh <pr-number> <marker> <body-file>}
repo=${GITHUB_REPOSITORY:?}
tag="<!-- $marker -->"

body=$(printf '%s\n\n%s\n' "$tag" "$(cat "$file")")
id=$(gh api --paginate "repos/$repo/issues/$pr/comments" \
  --jq ".[] | select(.user.login == \"github-actions[bot]\" and (.body | startswith(\"$tag\"))) | .id" | head -1)

if [[ -n "$id" ]]; then
  gh api -X PATCH "repos/$repo/issues/comments/$id" -f body="$body" --jq '"updated comment " + (.id | tostring)'
else
  gh api -X POST "repos/$repo/issues/$pr/comments" -f body="$body" --jq '"posted comment " + (.id | tostring)'
fi
