#!/usr/bin/env bash
# After purchase-race.js: how every saga of the run ended, from Step Functions itself.
# k6 can only see "PAID" or "still 404"; this says whether a 404 was SeatUnavailable (the
# expected loss) or something else.
#   load-test/scripts/settle.sh <eventId>
set -euo pipefail
EVENT=${1:?event id}
REGION=${AWS_DEFAULT_REGION:-eu-central-1}
SM=arn:aws:states:$REGION:<account-id>:stateMachine:checkout-saga
SINCE=$(date -u -d '-2 hours' +%s)
aws stepfunctions list-executions --region "$REGION" --state-machine-arn "$SM" --max-results 1000 --output json \
| jq -r --argjson since "$SINCE" '.executions[] | select((.startDate|tonumber) > $since) | .executionArn' \
| while read -r arn; do
    aws stepfunctions describe-execution --region "$REGION" --execution-arn "$arn" --query '[status, error, input]' --output json \
    | jq -r --arg e "$EVENT" 'select(.[2] | fromjson | .eventId == $e) | "\(.[0]) \(.[1] // "-")"'
  done | sort | uniq -c
