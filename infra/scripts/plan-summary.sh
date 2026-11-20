#!/usr/bin/env bash
# The change summary of a saved plan, as Markdown: resource addresses and their actions, read
# from `terraform show -json` (D14). Never attribute values: a full plan is full of ARNs, ids
# and hostnames, and this text is posted as a PR comment, which GitHub does not mask.
#
#   infra/scripts/plan-summary.sh <root-dir> <planfile> [title]
set -euo pipefail
root=${1:?usage: plan-summary.sh <root-dir> <planfile> [title]}
plan=${2:?usage: plan-summary.sh <root-dir> <planfile> [title]}
title=${3:-$root}
here=$(dirname "$0")

terraform -chdir="$root" show -json "$plan" | jq -r --arg title "$title" '
  [ .resource_changes[]?
    | select(.mode == "managed" and .change.actions != ["no-op"])
    | { address,
        action: (.change.actions
          | if (sort == ["create", "delete"]) then "replace" else join(",") end) } ] as $changes
  | "### Plan: `\($title)`\n\n"
  + if ($changes | length) == 0 then "No changes.\n"
    else
      ([ "create", "update", "replace", "delete" ]
        | map(. as $a | "\($a) \([ $changes[] | select(.action == $a) ] | length)")
        | join(" · "))
      + "\n\n| action | address |\n|---|---|\n"
      + ($changes | sort_by(.action, .address) | map("| \(.action) | `\(.address)` |") | join("\n"))
      + "\n"
    end
' | "$here/mask-ids.sh"
