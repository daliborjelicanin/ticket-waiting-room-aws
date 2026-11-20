#!/usr/bin/env bash
# One purchase through the edge and the saga, end to end, and what it left behind: the slice 3
# verify's happy path and refund drill (runbook-checkout-saga.md), one buyer at a time.
#
#   source infra/scripts/tf-env.sh
#   export API=$(terraform -chdir=infra/live/session output -raw invoke_url)
#   load-test/scripts/mint-tokens.sh 2
#   load-test/scripts/saga-purchase.sh <event> <seat> <buyer-index> [payment-token]
#
# Seeds the seat at PRICE cents (default 12345; idempotent, an existing seat is skipped),
# joins the buyer to the queue and admits one (so use a fresh event per buyer, or the buyer
# admitted may not be this one), purchases with the buyer's ID token, waits for the
# execution, then prints the states it entered, its result, the order as the buyer sees it,
# and the seat as the table holds it. Payment tokens: tok_ok (default), tok_decline,
# tok_error, tok_refund_error (MockPaymentGateway).
#
# A browser User-Agent, because the WAF's Bot Control blocks HTTP libraries on the two buyer
# entry points (D6). Nothing printed names the account or an address.
set -euo pipefail
EVENT=${1:?event id} SEAT=${2:?seat id} BUYER=${3:?buyer index from .tokens.json (0-based)} PAY=${4:-tok_ok}
API=${API:?set API to the stage invoke URL}
PRICE=${PRICE:-12345}
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 2; }
TOKENS="$(dirname "$0")/../.tokens.json"
[[ -f "$TOKENS" ]] || { echo "no $TOKENS -- run mint-tokens.sh first" >&2; exit 2; }
ENV_NAME=${TWR_ENV:-dev}
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'

ops=$(jq -r .ops "$TOKENS")
access=$(jq -r ".users[$BUYER].accessToken" "$TOKENS")
id=$(jq -r ".users[$BUYER].idToken" "$TOKENS")
[[ "$access" != null ]] || { echo "no buyer $BUYER in $TOKENS" >&2; exit 2; }
call() { curl -sS -A "$UA" -w '\n%{http_code}' "$@"; }
status_of() { tail -1 <<<"$1"; }
body_of() { sed '$d' <<<"$1"; }

r=$(call -X POST -H "Authorization: Bearer $ops" -H 'Content-Type: application/json' \
  -d "{\"seatIds\":[\"$SEAT\"],\"priceCents\":$PRICE}" "$API/admin/events/$EVENT/seats")
echo "seed $SEAT at $PRICE: $(status_of "$r") $(body_of "$r" | jq -c '{created, skipped}')"

r=$(call -X POST -H "Authorization: Bearer $access" "$API/events/$EVENT/queue")
echo "join: $(status_of "$r") $(body_of "$r" | jq -c '{status, position}')"
r=$(call -X POST -H "Authorization: Bearer $ops" -H 'Content-Type: application/json' -d '{"count":1}' \
  "$API/events/$EVENT/queue/admit")
echo "admit 1: $(status_of "$r") $(body_of "$r" | jq -c '{admitted}')"

order=$(uuidgen)
r=$(call -X POST -H "Authorization: Bearer $id" -H 'Content-Type: application/json' \
  -d "{\"orderId\":\"$order\",\"seatId\":\"$SEAT\",\"paymentToken\":\"$PAY\"}" "$API/events/$EVENT/purchase")
echo "purchase ($PAY): $(status_of "$r") $(body_of "$r" | tr -d '\n ')"
[[ $(status_of "$r") == 202 ]] || exit 1

sm=$(aws stepfunctions list-state-machines \
  --query "stateMachines[?name=='twr-$ENV_NAME-checkout-saga'].stateMachineArn | [0]" --output text)
execution="${sm/:stateMachine:/:execution:}:$order"
for _ in $(seq 60); do
  st=$(aws stepfunctions describe-execution --execution-arn "$execution" --query status --output text 2>/dev/null || echo PENDING)
  [[ "$st" == RUNNING || "$st" == PENDING ]] || break
  sleep 3
done
echo "states: $(aws stepfunctions get-execution-history --execution-arn "$execution" \
  --query 'events[?stateEnteredEventDetails].stateEnteredEventDetails.name' --output text | tr '\t' ' ')"
echo "execution: $(aws stepfunctions describe-execution --execution-arn "$execution" --query '[status, error]' --output text)"

r=$(call -H "Authorization: Bearer $access" "$API/orders/$order")
echo "order ${order:0:8}…: $(status_of "$r") $(body_of "$r" | jq -c '{status, amountCents, currency}' 2>/dev/null || true)"
table=$(aws ssm get-parameter --name "/twr/$ENV_NAME/foundation/dynamodb/seat_table_name" --query Parameter.Value --output text)
echo "seat $SEAT: $(aws dynamodb get-item --table-name "$table" --consistent-read \
  --key "{\"pk\":{\"S\":\"EVENT#$EVENT\"},\"sk\":{\"S\":\"SEAT#$SEAT\"}}" \
  --projection-expression '#s' --expression-attribute-names '{"#s":"status"}' --query 'Item.status.S' --output text)"
echo "order id: $order"
