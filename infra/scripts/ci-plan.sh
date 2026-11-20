#!/usr/bin/env bash
# CI's plan of one root (D14, D15), with the role's credentials already in the environment:
#
#   TF_VAR_account_id=... infra/scripts/ci-plan.sh <root-dir> <planfile>
#
# Resolves the image digests from SSM /twr/<env>/images (as tf-env.sh does locally: at
# dispatch, so the plan shows which image runs), inits against the state bucket with the
# committed lock file, and plans into <planfile> inside the root. The log gets addresses,
# actions and diagnostics only (tf-log.sh); the full plan stays in the file, on the runner.
#
# Exit: terraform's -detailed-exitcode. 0 no changes, 2 changes, 1 error.
set -uo pipefail
root=${1:?usage: ci-plan.sh <root-dir> <planfile>}
plan=${2:?usage: ci-plan.sh <root-dir> <planfile>}
: "${TF_VAR_account_id:?the account id, from the AWS_ACCOUNT_ID secret}"
here=$(dirname "$0")

TF_VAR_image_digests=$(aws ssm get-parameters-by-path --path "/twr/${TF_VAR_env:-dev}/images" \
  --query 'Parameters[].[Name,Value]' --output json |
  jq -c 'map({key: (.[0] | split("/") | last), value: .[1]}) | from_entries') || exit 1
export TF_VAR_image_digests
echo "image digests for: $(jq -r 'keys | join(", ")' <<<"$TF_VAR_image_digests")"

"$here/tf-init.sh" "$root" -lockfile=readonly -no-color 2>&1 | "$here/mask-ids.sh"
((PIPESTATUS[0] == 0)) || exit 1

terraform -chdir="$root" plan -input=false -lock-timeout=5m -detailed-exitcode \
  -out="$plan" -json | "$here/tf-log.sh"
exit "${PIPESTATUS[0]}"
