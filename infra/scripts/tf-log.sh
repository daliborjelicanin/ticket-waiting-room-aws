#!/usr/bin/env bash
# A filter for `terraform plan|apply -json` in CI (D14): prints each resource's address and
# action, the change summary and every diagnostic, and nothing else, so no attribute value
# (an ARN, an id, a hostname) reaches the public log. Diagnostics go through mask-ids.sh.
#
#   terraform -chdir=<root> plan -json ... | infra/scripts/tf-log.sh
set -euo pipefail
here=$(dirname "$0")

jq -r --unbuffered '
  def addr: .resource.addr // "";
  if .type == "diagnostic" then
    "\(.["@level"] | ascii_upcase): \(.diagnostic.summary)"
    + (if .diagnostic.address then " [\(.diagnostic.address)]" else "" end)
    + (if (.diagnostic.detail // "") != "" then "\n  \(.diagnostic.detail)" else "" end)
  elif .type == "planned_change" then "  plan   \(.change.action)\t\(.change | addr)"
  elif .type == "apply_complete" and .hook.action == "read" then empty
  elif .type == "apply_complete" then "  done   \(.hook.action)\t\(.hook | addr) (\(.hook.elapsed_seconds)s)"
  elif .type == "apply_errored" then "  FAILED \(.hook.action)\t\(.hook | addr)"
  elif .type == "change_summary" then .["@message"]
  else empty end
' | "$here/mask-ids.sh"
