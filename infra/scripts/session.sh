#!/usr/bin/env bash
# The session stack, brought up in the order D23 requires and down again: one script, run by
# the owner now and wrapped by session.yml from slice 4, so the order lives in one place.
#
#   source infra/scripts/tf-env.sh
#   infra/scripts/session.sh up                     # everything, in stages
#   infra/scripts/session.sh up -- -var task_size='{"seat-inventory":"proof"}'
#   infra/scripts/session.sh down                   # destroy, then the account sweep
#
# up:
#   1. apply data, edge, saga and seat-inventory (it waits for steady state). seat-inventory
#      goes first because the refund path needs its idempotent confirm before the saga runs.
#   2. migrate.sh checkout, then ticketing: the databases and roles exist before their
#      services start, and a failed migration stops here, before any service sees the schema.
#   3. apply every service.
# Each apply asks for approval; AUTO_APPROVE=1 skips that (the workflow, from slice 4).
# Arguments after `--` go to every apply (and the destroy), e.g. a -var for the load test.
#
# Needs the five service images' digests in TF_VAR_image_digests (tf-env.sh reads them from
# SSM /twr/<env>/images, written when an image is pushed).
set -euo pipefail
CMD=${1:?up or down}
shift
[[ "${1:-}" == "--" ]] && shift
EXTRA=("$@")
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/../live/session"

ALL_SERVICES='["seat-inventory","waiting-room","checkout","issuance","notification"]'
# Everything the services need. WAF included: the saga's own requests pass it (D5), and its
# baseline is reviewed against them before it blocks (D6).
TOGGLES=(-var enable_nat=true -var enable_alb=true -var enable_waf=true
         -var enable_db=true -var enable_cache=true -var enable_saga=true)

approve=()
[[ "${AUTO_APPROVE:-}" == 1 ]] && approve=(-auto-approve)

stage() { echo; echo "=== session.sh: $*"; }

apply() {
  terraform -chdir="$ROOT" apply -input=false "${approve[@]}" "${TOGGLES[@]}" -var "services=$1" "${EXTRA[@]}"
}

case "$CMD" in
  up)
    missing=$(python3 -c '
import json, os, sys
have = json.loads(os.environ.get("TF_VAR_image_digests") or "{}")
need = ["seat-inventory-service", "waiting-room-service", "checkout-payment-service",
        "ticket-issuance-service", "notification-service"]
print(" ".join(r for r in need if r not in have))')
    [[ -z "$missing" ]] || { echo "no image digest for: $missing (push it, then re-source tf-env.sh)" >&2; exit 2; }

    stage "1/3 data, edge, saga, seat-inventory"
    apply '["seat-inventory"]'

    stage "2/3 migrations"
    "$HERE/migrate.sh" checkout
    "$HERE/migrate.sh" ticketing

    stage "3/3 every service"
    apply "$ALL_SERVICES"

    stage "up"
    terraform -chdir="$ROOT" output -raw invoke_url >/dev/null && echo "edge: terraform -chdir=infra/live/session output -raw invoke_url"
    ;;
  down)
    stage "destroy"
    terraform -chdir="$ROOT" destroy -input=false "${approve[@]}" "${TOGGLES[@]}" -var "services=$ALL_SERVICES" "${EXTRA[@]}"
    stage "sweep"
    "$HERE/../../scripts/teardown-sweep.sh"
    ;;
  *)
    echo "usage: session.sh up|down [-- <terraform args>]" >&2
    exit 2
    ;;
esac
