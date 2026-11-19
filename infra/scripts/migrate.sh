#!/usr/bin/env bash
# Runs one database's migration task and exits with its result (D10): the service image in its
# migrate profile, as the RDS master, creates the database and the service role, migrates, and
# grants the role DML (migration-starter). Run it before the service is updated; a non-zero exit
# stops the deploy.
#
#   source infra/scripts/tf-env.sh
#   infra/scripts/migrate.sh checkout       # then: ticketing
#
# Needs the session up with enable_db and enable_nat: the task reaches ECR's API, Secrets
# Manager and CloudWatch Logs through the NAT (D4). On-demand Fargate, not Spot: an interrupted
# migration is a failed deploy, and the run costs seconds.
#
# Exit codes: the migrate container's own (0 migrated, 1 failed), or 2 when it never ran (no
# cluster or task definition, no NAT, an image or secret ECS could not fetch) or the wait timed
# out. Prints the task's log tail with the RDS host and the account id masked.
set -euo pipefail
DB=${1:?database: checkout or ticketing}
[[ "$DB" == checkout || "$DB" == ticketing ]] || { echo "database is checkout or ticketing, not '$DB'" >&2; exit 2; }
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 2; }
ENV_NAME=${TWR_ENV:-dev}
CLUSTER="twr-$ENV_NAME"
FAMILY="twr-$ENV_NAME-migrate-$DB"
LOG_GROUP="/twr/$ENV_NAME/ecs/migrate"
SSM="/twr/$ENV_NAME/foundation"

mask() { sed -E 's/[0-9]{12}/<account-id>/g; s/[a-z0-9-]+\.[a-z0-9]+\.[a-z0-9-]+\.rds\.amazonaws\.com/<rds-host>/g'; }
fail() { echo "migrate $DB: $*" >&2; exit 2; }

ssm() { aws ssm get-parameter --name "$SSM/$1" --query Parameter.Value --output text; }
subnets=$(ssm network/app_subnet_ids)
sg=$(ssm network/sg/migrate)
vpc=$(ssm network/vpc_id)

[[ $(aws ecs describe-clusters --clusters "$CLUSTER" --query 'clusters[0].status' --output text) == ACTIVE ]] \
  || fail "no cluster $CLUSTER: apply the session with enable_db"
aws ecs describe-task-definition --task-definition "$FAMILY" --query taskDefinition.revision --output text >/dev/null 2>&1 \
  || fail "no task definition $FAMILY: apply the session with enable_db, after the image is pushed"
[[ $(aws ec2 describe-nat-gateways --filter "Name=vpc-id,Values=$vpc" "Name=state,Values=available" \
  --query 'length(NatGateways)' --output text) -gt 0 ]] \
  || fail "no NAT gateway: the task cannot reach ECR, Secrets Manager or Logs; apply with enable_nat"

started=$(date +%s)
task=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$FAMILY" --launch-type FARGATE --count 1 \
  --started-by migrate.sh \
  --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$sg],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)
[[ "$task" == arn:* ]] || fail "run-task started nothing"
id=${task##*/}
echo "migrate $DB: task $id started, waiting for it to stop"

# Polls every 6 s, up to 10 minutes: an image pull and a Spring start are about a minute.
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task" || fail "task $id did not stop within 10 minutes"

# The backticks are a JMESPath string literal, not a shell expansion.
# shellcheck disable=SC2016
read -r code reason < <(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" \
  --query 'tasks[0].[containers[?name==`migrate`]|[0].exitCode, stoppedReason]' --output text)

# The migration's own lines (migration-starter's loggers) and anything WARN or worse; on a
# failure, the last 40 lines in full as well, where Boot says why startup failed. A plain tail
# would be Hibernate's startup chatter: the migration's lines come before it.
log=$(aws logs get-log-events --log-group-name "$LOG_GROUP" --log-stream-name "$DB/migrate/$id" --start-from-head \
  --limit 500 --query 'events[].message' --output text 2>/dev/null | tr '\t' '\n' | mask || true)
echo "--- log ($LOG_GROUP, stream $DB/migrate/$id)"
if [[ -z "$log" ]]; then
  echo "(no log stream: the container never started)"
else
  grep -E 'c\.t\.m\.|c\.t\.migration\.|ticketwaitingroom\.migration| (WARN|ERROR) ' <<<"$log" || true
  [[ "$code" == 0 ]] || { echo "--- last 40 lines"; tail -40 <<<"$log"; }
fi
echo "---"

if [[ "$code" =~ ^[0-9]+$ ]]; then
  echo "migrate $DB: exit $code after $(($(date +%s) - started)) s"
  exit "$code"
fi
echo "migrate $DB: the container never ran: $reason" | mask >&2
exit 2
