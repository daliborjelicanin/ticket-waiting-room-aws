#!/usr/bin/env bash
# The seat race's claim, read from the table itself: each of the S seeded seats is HELD
# exactly once, with exactly one holdId, and nothing else exists for the event.
#
#   load-test/scripts/seat-holds.sh <eventId> <seats>
#
# A seat is one item, so the conditional write is the whole lock: two winners would mean a
# second holdId overwriting the first, which k6 would count as two 201s for one seat. That is
# why the check compares the table with k6's own count: k6 holds_won must equal the HELD seats
# here, and both must equal S.
#
# Run within the 5-minute hold (seat-inventory.hold-duration): after that the cleanup Lambda
# reclaims the holds and the seats read AVAILABLE again. Phase 2 table name from SSM (D25);
# the checkout/ticketing invariants of the purchase race are slice 3's reconciliation task (D16).
set -euo pipefail
EVENT=${1:?event id}
SEATS=${2:?number of seats the race was run with}
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 1; }
TABLE=$(aws ssm get-parameter --name "/twr/${TWR_ENV:-dev}/foundation/dynamodb/seat_table_name" --query Parameter.Value --output text)

items=$(aws dynamodb query --table-name "$TABLE" --consistent-read \
  --key-condition-expression 'pk = :pk' --expression-attribute-values "{\":pk\":{\"S\":\"EVENT#$EVENT\"}}" \
  --projection-expression 'sk, #s, holdId' --expression-attribute-names '{"#s":"status"}' --output json)

TOTAL=$(jq '.Items | length' <<<"$items")
HELD=$(jq '[.Items[] | select(.status.S == "HELD")] | length' <<<"$items")
HELD_WITH_ONE_ID=$(jq '[.Items[] | select(.status.S == "HELD" and (.holdId.S // "") != "")] | length' <<<"$items")
OTHER=$(jq '[.Items[] | select(.status.S != "HELD")] | length' <<<"$items")

echo "== DynamoDB $TABLE, event $EVENT"
jq -r '.Items | group_by(.status.S) | map("  \(.[0].status.S): \(length)") | .[]' <<<"$items"
printf "  seats in the table          : %s (seeded %s)\n" "$TOTAL" "$SEATS"
printf "  HELD, each with its holdId  : %s of %s\n" "$HELD_WITH_ONE_ID" "$HELD"
printf "  not HELD                    : %s (must be 0 inside the 5-minute hold)\n" "$OTHER"

if [ "$TOTAL" = "$SEATS" ] && [ "$HELD" = "$SEATS" ] && [ "$HELD_WITH_ONE_ID" = "$SEATS" ] && [ "$OTHER" = 0 ]; then
  echo "  RESULT: every seat held exactly once -- compare with k6's holds_won, which must also be $SEATS"
else
  echo "  RESULT: MISMATCH" >&2
  exit 1
fi
