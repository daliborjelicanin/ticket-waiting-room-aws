#!/usr/bin/env bash
# terraform init for a root that keeps its state in the twr-dev state bucket.
#
#   source infra/scripts/tf-env.sh && infra/scripts/tf-init.sh infra/live/foundation
#
# The bucket name contains the account id, so it is computed here and passed as partial
# backend configuration; it never appears in a committed file (CLAUDE.md, Secrets).
set -euo pipefail

root="${1:?usage: tf-init.sh <root-dir> [extra terraform init args]}"
shift
: "${TF_VAR_account_id:?run 'source infra/scripts/tf-env.sh' first}"
env="${TF_VAR_env:-dev}"

exec terraform -chdir="$root" init -input=false \
  -backend-config="bucket=twr-${env}-tfstate-${TF_VAR_account_id}" "$@"
