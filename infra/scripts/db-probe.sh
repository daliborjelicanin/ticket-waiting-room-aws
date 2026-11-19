#!/usr/bin/env bash
# Checks, on the live instance, that a service's database role can do what D9 says and nothing
# more: logged in as checkout_app (or ticketing_app) with the password from its own secret, it
# reads and writes its own tables, and Postgres refuses it DDL, the Flyway history, and the other
# service's database. The live half of migration-starter's MigrateProfileIT.
#
#   source infra/scripts/tf-env.sh
#   infra/scripts/db-probe.sh checkout       # then: ticketing
#
# Run after migrate.sh, with the session up with enable_db and enable_nat. It registers a
# throwaway task definition (psql from public ECR, the migrate roles and security group: the
# execution role already resolves both service secrets), runs it once, and deregisters and
# deletes that definition again on any exit. Exits 0 when every check went as expected, 1 when
# one did not, 2 when the probe never ran.
set -euo pipefail
DB=${1:?database: checkout or ticketing}
case "$DB" in
  checkout)  OTHER=ticketing; OWN_TABLE=orders ;;
  ticketing) OTHER=checkout;  OWN_TABLE=tickets ;;
  *) echo "database is checkout or ticketing, not '$DB'" >&2; exit 2 ;;
esac
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 2; }
ENV_NAME=${TWR_ENV:-dev}
CLUSTER="twr-$ENV_NAME"
FAMILY="twr-$ENV_NAME-db-probe"
LOG_GROUP="/twr/$ENV_NAME/ecs/migrate"
SSM="/twr/$ENV_NAME/foundation"
REGION=${AWS_REGION:-eu-central-1}
# psql only; the server's major, from AWS's mirror of the Docker library (no Docker Hub limits).
IMAGE=public.ecr.aws/docker/library/postgres:18-alpine

fail() { echo "db-probe $DB: $*" >&2; exit 2; }
# psql's connection errors name the server: the RDS endpoint and its private address.
mask() { sed -E 's/[0-9]{12}/<account-id>/g; s/[a-z0-9-]+\.[a-z0-9]+\.[a-z0-9-]+\.rds\.amazonaws\.com/<rds-host>/g; s/\(([0-9]{1,3}\.){3}[0-9]{1,3}\)/(<private-ip>)/g'; }
ssm() { aws ssm get-parameter --name "$SSM/$1" --query Parameter.Value --output text; }

host=$(aws rds describe-db-instances --db-instance-identifier "$(ssm db/instance_identifier)" \
  --query 'DBInstances[0].Endpoint.Address' --output text 2>/dev/null) || fail "no RDS instance: apply the session with enable_db"
secret=$(aws secretsmanager describe-secret --secret-id "$(ssm "db/secret_name/${DB}_app")" --query ARN --output text)
subnets=$(ssm network/app_subnet_ids)
sg=$(ssm network/sg/migrate)

# One psql call per claim, each on its own connection, so a refusal cannot abort the next check.
# Prints "PROBE <check> ALLOWED" or "PROBE <check> REFUSED: <Postgres's message>".
# shellcheck disable=SC2016  # expanded by the container's shell, not this one
probe='
q() { check=$1; database=$2; shift 2
  if out=$(psql -X -q -At -v ON_ERROR_STOP=1 -d "$database" -c "$*" 2>&1); then echo "PROBE $check ALLOWED"
  else echo "PROBE $check REFUSED: $(echo "$out" | grep -m1 -E "ERROR|FATAL" || echo "$out" | tail -1)"; fi; }
q read-own       "$OWN_DB"   "SELECT count(*) FROM $OWN_TABLE"
q write-own      "$OWN_DB"   "UPDATE $OWN_TABLE SET id = id WHERE false"
q no-superpowers "$OWN_DB"   "SELECT 1/(NOT (rolsuper OR rolcreatedb OR rolcreaterole))::int FROM pg_roles WHERE rolname = current_user"
q drop-own       "$OWN_DB"   "DROP TABLE $OWN_TABLE"
q create-table   "$OWN_DB"   "CREATE TABLE probe (id int)"
q read-history   "$OWN_DB"   "SELECT count(*) FROM flyway_schema_history"
q connect-other  "$OTHER_DB" "SELECT 1"
echo "PROBE done"'

containers=$(jq -n --arg image "$IMAGE" --arg probe "$probe" --arg secret "$secret" --arg host "$host" \
  --arg own "$DB" --arg other "$OTHER" --arg table "$OWN_TABLE" --arg group "$LOG_GROUP" --arg region "$REGION" '[{
    name: "probe", image: $image, essential: true, command: ["sh", "-c", $probe],
    environment: [
      {name: "PGHOST", value: $host}, {name: "PGSSLMODE", value: "require"},
      {name: "OWN_DB", value: $own}, {name: "OTHER_DB", value: $other}, {name: "OWN_TABLE", value: $table}],
    secrets: [
      {name: "PGUSER", valueFrom: ($secret + ":username::")},
      {name: "PGPASSWORD", valueFrom: ($secret + ":password::")}],
    readonlyRootFilesystem: true, privileged: false,
    linuxParameters: {capabilities: {drop: ["ALL"]}},
    logConfiguration: {logDriver: "awslogs", options: {
      "awslogs-group": $group, "awslogs-region": $region, "awslogs-stream-prefix": "probe"}}}]')

revision=$(aws ecs register-task-definition --family "$FAMILY" --requires-compatibilities FARGATE \
  --network-mode awsvpc --cpu 256 --memory 512 --runtime-platform cpuArchitecture=ARM64,operatingSystemFamily=LINUX \
  --task-role-arn "$(ssm iam/role/migrate-task)" --execution-role-arn "$(ssm iam/role/migrate-execution)" \
  --container-definitions "$containers" --query taskDefinition.taskDefinitionArn --output text)
# shellcheck disable=SC2317  # called by the trap below
cleanup() {
  aws ecs deregister-task-definition --task-definition "$revision" >/dev/null
  aws ecs delete-task-definitions --task-definitions "$revision" >/dev/null
  echo "db-probe $DB: task definition deregistered and deleted"
}
trap cleanup EXIT

task=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$revision" --launch-type FARGATE --count 1 \
  --started-by db-probe.sh \
  --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$sg],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)
[[ "$task" == arn:* ]] || fail "run-task started nothing"
id=${task##*/}
echo "db-probe $DB: task $id started as ${DB}_app, waiting"
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$task" || fail "task $id did not stop within 10 minutes"

results=$(aws logs get-log-events --log-group-name "$LOG_GROUP" --log-stream-name "probe/probe/$id" \
  --query 'events[].message' --output text 2>/dev/null | tr '\t' '\n' | grep '^PROBE ' | mask || true)
[[ "$results" == *"PROBE done"* ]] \
  || fail "the probe did not finish: $(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" --query 'tasks[0].stoppedReason' --output text)"

# What D9 says each check must do.
status=0
for expected in read-own:ALLOWED write-own:ALLOWED no-superpowers:ALLOWED drop-own:REFUSED \
                create-table:REFUSED read-history:REFUSED connect-other:REFUSED; do
  check=${expected%%:*} want=${expected##*:}
  line=$(grep "^PROBE $check " <<<"$results" || echo "PROBE $check MISSING")
  if [[ "$line" == "PROBE $check $want"* ]]; then echo "  ok    ${line#PROBE }"; else echo "  WRONG ${line#PROBE } (want $want)"; status=1; fi
done
exit "$status"
