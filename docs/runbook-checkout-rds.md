# Runbook — Checkout/Payment on RDS Postgres + Secrets Manager

Slice 4's AWS half: stand up `db.t4g.micro` Postgres, let RDS mint the credentials into
Secrets Manager, and run `checkout-payment-service` on Fargate against it. Written as CLI
rather than console — everything here is one `create-db-instance` call plus plumbing, and
the flags *are* the learning content.

**RDS is the most expensive thing in Phase 1. It bills whether or not anything connects,
storage bills even while the instance is stopped, and a stopped instance restarts itself
after 7 days.** Teardown is delete, never stop.

## What Gets Built

| Resource | Name | Cost while up | Torn down after |
|---|---|---|---|
| Security group (task) | `checkout-payment-sg` | $0 | yes |
| Security group (db) | `checkout-db-sg` | $0 | yes |
| Log group | `/ecs/checkout-payment-service` | ~$0 (1-day retention) | no |
| RDS instance | `ticket-checkout-db` | **~$0.022/hr** | **yes — delete** |
| Secrets Manager secret | `rds!db-…` (RDS-managed) | $0.40/mo prorated | with the instance |
| Task definition | `checkout-payment-service:1` | $0 | no |
| Fargate Spot task | one-off | ~$0.004/hr | yes |

`db.t4g.micro` is ~$0.019/hr in eu-central-1; 20 GB gp2 adds ~$0.0032/hr prorated. Both
fall under the 12-month RDS free tier if the account still has it.

## Order Of Operations

Three real sequencing constraints, not preferences:

1. **Task SG before DB SG.** The DB SG's ingress rule references the task SG by id, so the
   task SG must exist first.
2. **RDS before the task definition.** `DB_URL` needs the endpoint and the `secrets` block
   needs the managed secret's ARN; neither exists until the instance is `available`.
3. **Log group before the first task.** ECS does not create it — `awslogs` fails the task
   at startup with a `ResourceNotFoundException` that looks like a permissions problem.

## Console Walkthrough

The CLI version of every step is further below; this is the click path. Exact labels drift
between console revisions — the *values* are what matter, and the traps called out here are
places where the console's default is wrong for this project.

### C1. Task security group

**VPC → Security groups → Create security group**

| Field | Value |
|---|---|
| Security group name | `checkout-payment-sg` |
| Description | `checkout-payment Fargate task` |
| VPC | `<vpc-id>` (the default VPC) |
| Inbound rule 1 | Type **Custom TCP**, Port range **8080**, Source **My IP** |
| Tags | `project` = `ticket-waiting-room` |

Leave the outbound rule alone — the task needs egress to ECR, Secrets Manager and RDS.
Create this one first: the database group references it by id and cannot be built first.

### C2. Database security group

**VPC → Security groups → Create security group**

| Field | Value |
|---|---|
| Name | `checkout-db-sg` |
| Description | `checkout Postgres` |
| VPC | same default VPC |
| Inbound rule 1 | **PostgreSQL** (auto-fills TCP/5432), Source **Custom** → type `sg-` and pick **checkout-payment-sg** |
| Inbound rule 2 | **PostgreSQL**, Source **My IP** |
| Tags | `project` = `ticket-waiting-room` |

Rule 1 is the one worth understanding: it names a *security group*, not a CIDR, so it keeps
working when the task restarts on a different IP. Rule 2 exists only so `psql` from the
laptop can check the constraints directly, and it is the line Phase 2 deletes.

### C3. Log group

**CloudWatch → Log groups → Create log group**

- Log group name: `/ecs/checkout-payment-service`
- Retention setting: **1 day**

ECS will not create this for you when the task definition names it explicitly, and its
absence stops the task before the container starts.

### C4. RDS instance

**RDS → Databases → Create database.** Budget 6–10 minutes for creation.

**Choose `Standard create`, not `Easy create`.** Easy create hides availability zone,
security group, public access, backup retention and the initial database name — five of the
things this slice has to set. There is no way back to them without modifying the instance
afterwards.

| Section | Field | Value |
|---|---|---|
| Engine options | Engine type | **PostgreSQL** |
| | Engine version | **16.15** |
| Templates | | **Free tier** if offered, else **Dev/Test** |
| Availability | | **Single DB instance** |
| Settings | DB instance identifier | `ticket-checkout-db` |
| | Master username | `ticketadmin` |
| | Credentials management | **Managed in AWS Secrets Manager** |
| | Encryption key | default `aws/secretsmanager` |
| Instance config | Class | Burstable → **db.t4g.micro** |
| Storage | Type / Size | **gp2**, **20 GiB** |
| | Storage autoscaling | **unchecked** |
| Connectivity | Compute resource | **Don't connect to an EC2 compute resource** |
| | VPC / subnet group | default VPC, default subnet group |
| | Public access | **Yes** |
| | VPC security group | remove `default`, choose **checkout-db-sg** |
| | Availability Zone | **eu-central-1a** |
| | Port | 5432 |
| Authentication | | Password authentication |
| Monitoring | Performance Insights | **off** |
| | Enhanced monitoring | **off** |
| Additional config | **Initial database name** | **`checkout`** |
| | Automated backups | **unchecked** |
| | Auto minor version upgrade | **unchecked** |
| | Deletion protection | **unchecked** |
| Tags | | `project` = `ticket-waiting-room` |

Six console defaults that are wrong here:

1. **Engine version defaults to the newest major (18.x).** `CheckoutConcurrencyIT` runs
   against `postgres:16-alpine`. Nothing in this slice behaves differently on 18, but
   "tests pass on a different major than production" is a habit worth not forming.
2. **"Initial database name" sits inside the collapsed *Additional configuration* panel,
   and is empty by default.** Leave it empty and RDS creates an instance with no database
   in it — Flyway then fails with `FATAL: database "checkout" does not exist`, which reads
   like a connectivity problem and is not one. This is the single most commonly missed
   field on the page.
   **Recovering from it** (hit on 2026-08-27): the initial database name is create-time
   only — `modify-db-instance` cannot add it. But the Postgres `postgres` database always
   exists, so connect to that and issue `CREATE DATABASE checkout OWNER ticketadmin;`
   rather than deleting and rebuilding the instance. If public access was also left off,
   that has to be turned on first (Modify → Public access → Apply immediately, ~3 min, no
   reboot), because nothing else in the VPC can run psql for you.
3. **Availability Zone defaults to "No preference"**, which can land the database in 1b or
   1c while the task runs in 1a — quietly adding cross-AZ transfer charges to every query.
4. **Storage autoscaling is checked by default**, so a runaway can grow the bill past what
   you provisioned.
5. **The Production template turns on Multi-AZ and deletion protection.** Multi-AZ doubles
   the instance cost; deletion protection makes teardown fail at the point you are trying
   to stop paying.
6. **The security group picker pre-selects `default`.** Remove it — otherwise the database
   is reachable from anything in the VPC that happens to use the default group.

"Managed in AWS Secrets Manager" is the console's name for `--manage-master-user-password`:
RDS generates the password, stores it, and never displays it. Nothing gets typed or pasted,
so there is no password to leak into a shell history or a screenshot. The secret is named
`rds!db-…`, which is exactly what `ticket-ecs-execution-role`'s `ReadDatabaseSecrets`
policy is scoped to.

### C5. Collect the endpoint and the secret ARN

**RDS → Databases → `ticket-checkout-db` → Connectivity & security**

- **Endpoint** — copy it. This becomes `DB_URL`.
- Under *Manage master credentials*, click the secret link → Secrets Manager opens →
  copy the **Secret ARN**.

Sanity check the ARN contains `secret:rds!db-`. If it does not, the execution role's policy
will not match it and the task will fail to start.

To test the connection before involving ECS, reveal the password with
**Secrets Manager → the secret → Retrieve secret value**, then from a terminal:

```bash
docker run --rm -e PGPASSWORD='<password>' postgres:16-alpine \
  psql -h <endpoint> -U ticketadmin -d checkout -c 'select version();'
```

Do this before step C6. It separates "the database is wrong" from "the task definition is
wrong", which otherwise present identically as a task that dies with no useful log line.

### C6. Task definition

**ECS → Task definitions → Create new task definition**

| Section | Field | Value |
|---|---|---|
| Task definition | Family | `checkout-payment-service` |
| Infrastructure | Launch type | **AWS Fargate** |
| | OS / Architecture | **Linux/X86_64** |
| | CPU / Memory | **.25 vCPU** / **.5 GB** |
| | Task role | **None** |
| | Task execution role | **ticket-ecs-execution-role** |
| Container 1 | Name | `checkout-payment-service` |
| | Image URI | `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/checkout-payment-service@sha256:da24762ce3d8ad39e09c3d30293f7f48d4c2e8322c65fed0c02b00916d5e9666` |
| | Essential container | Yes |
| | Port mappings | **8080**, TCP |

Then, in the container's **Environment variables** block, three rows — note the middle
column, which is a dropdown per row:

| Key | Value type | Value |
|---|---|---|
| `DB_URL` | Value | `jdbc:postgresql://<endpoint>:5432/checkout` |
| `DB_USERNAME` | **ValueFrom** | `<secret-arn>:username::` |
| `DB_PASSWORD` | **ValueFrom** | `<secret-arn>:password::` |

And under **Logging**, keep *Use log collection* → Amazon CloudWatch, with log group
`/ecs/checkout-payment-service`, region `eu-central-1`, stream prefix `ecs`.

Four things to get right:

- **Task role is None, on purpose.** The application makes no AWS API calls. The secret is
  fetched by the *execution* role before the container starts and handed over as an
  ordinary environment variable, so there is nothing for a task role to grant. Setting one
  "to be safe" grants permissions the running code can use and does not need.
- **`ValueFrom`, not `Value`, for the two credentials.** With `Value` the ARN is passed to
  the app as a literal string and Hibernate tries to log in as the text
  `arn:aws:secretsmanager:...`.
- **The trailing `:username::` / `:password::` selects one field out of the secret's
  JSON**, and both colons are required — the full grammar is
  `arn:json-key:version-stage:version-id`, and the empty tail means "current version".
  Omit the suffix and the container receives the whole JSON blob as its password, failing
  as an authentication error rather than a syntax error.
- **Pin the image by digest**, not `:latest`. During a rollback, "which image is actually
  running" has to be answerable.

### C7. Run the task

**ECS → Clusters → `ticket-waiting-room` → Tasks → Run new task**

| Field | Value |
|---|---|
| Compute options | **Capacity provider strategy** → `FARGATE_SPOT` |
| Application type | **Task** (not Service) |
| Family / Revision | `checkout-payment-service` / LATEST |
| Desired tasks | 1 |
| VPC | default |
| Subnets | **remove every subnet except the eu-central-1a one** (`<subnet-id>`) |
| Security group | existing → **checkout-payment-sg** |
| Public IP | **Turned on** |

**Public IP must be on.** There is no NAT Gateway in this phase, so without a public
address the task cannot reach ECR and dies before your code exists — reported as an image
pull timeout.

A one-off *Task* rather than a *Service*: nothing here needs restarting on failure, and
stopping a task is a cleaner teardown than deleting a service.

Then open the task → **Logs** tab. Wait for `Successfully applied 1 migration` followed by
`Started CheckoutPaymentServiceApplication`. The **Configuration** tab has the public IP.

### C8. Teardown, in this order

1. **ECS → Clusters → ticket-waiting-room → Tasks →** select the task → **Stop**.
2. **RDS → Databases →** select `ticket-checkout-db` → **Actions → Delete**.
   - **Create final snapshot: unchecked**
   - **Retain automated backups: unchecked**
   - type `delete me` in the confirmation box
3. **Secrets Manager → Secrets** — confirm the `rds!db-…` secret is gone or scheduled for
   deletion. RDS removes it with the instance.
4. **VPC → Security groups** — delete **checkout-db-sg** first, then
   **checkout-payment-sg**.

The order is forced twice over: RDS holds the database group while it exists, and the
database group's inbound rule references the task group. Deleting out of order fails with
`DependencyViolation`.

Leave the log group and the task definition — both free. The task definition's `DB_URL`
now names an endpoint that no longer exists; next session's instance gets a new one, so
create a new revision rather than reusing this one.

## Setup

```bash
export AWS_REGION=eu-central-1
ACCOUNT=<account-id>
VPC=<vpc-id>
SUBNET_A=<subnet-id>            # eu-central-1a
MYIP=$(curl -s https://checkip.amazonaws.com)/32
TAG='Key=project,Value=ticket-waiting-room'
```

`SUBNET_A` is eu-central-1a deliberately: the RDS instance is pinned to the same AZ, so
every JDBC round trip stays inside one AZ and no cross-AZ data transfer is billed.

## 1. Security Groups

```bash
TASK_SG=$(aws ec2 create-security-group \
  --group-name checkout-payment-sg --description "checkout-payment Fargate task" \
  --vpc-id $VPC --tag-specifications "ResourceType=security-group,Tags=[{$TAG}]" \
  --query GroupId --output text)

aws ec2 authorize-security-group-ingress --group-id $TASK_SG \
  --protocol tcp --port 8080 --cidr $MYIP

DB_SG=$(aws ec2 create-security-group \
  --group-name checkout-db-sg --description "checkout Postgres" \
  --vpc-id $VPC --tag-specifications "ResourceType=security-group,Tags=[{$TAG}]" \
  --query GroupId --output text)

# The service reaches the database because of *what it is*, not where it happens to run.
aws ec2 authorize-security-group-ingress --group-id $DB_SG \
  --protocol tcp --port 5432 --source-group $TASK_SG

# Second rule only so psql from this laptop can verify the constraints directly.
aws ec2 authorize-security-group-ingress --group-id $DB_SG \
  --protocol tcp --port 5432 --cidr $MYIP

echo "TASK_SG=$TASK_SG DB_SG=$DB_SG"
```

The SG-to-SG rule is the point worth keeping: no CIDR is involved, so the rule survives the
task landing on a different IP every time it starts. The `$MYIP` rule is a learning-phase
concession — see *Publicly Accessible* below.

## 2. Log Group

```bash
aws logs create-log-group --log-group-name /ecs/checkout-payment-service
aws logs put-retention-policy --log-group-name /ecs/checkout-payment-service --retention-in-days 1
```

## 3. RDS Instance

```bash
aws rds create-db-instance \
  --db-instance-identifier ticket-checkout-db \
  --db-instance-class db.t4g.micro \
  --engine postgres --engine-version 16.15 \
  --db-name checkout \
  --master-username ticketadmin \
  --manage-master-user-password \
  --allocated-storage 20 --storage-type gp2 \
  --no-multi-az --availability-zone eu-central-1a \
  --db-subnet-group-name default-<vpc-id> \
  --vpc-security-group-ids $DB_SG \
  --publicly-accessible \
  --backup-retention-period 0 \
  --no-deletion-protection \
  --no-auto-minor-version-upgrade \
  --tags $TAG

aws rds wait db-instance-available --db-instance-identifier ticket-checkout-db   # ~6-10 min
```

Flag by flag, the ones that matter:

- **`--engine-version 16.15`** — the newest 16.x. `CheckoutConcurrencyIT` runs against
  `postgres:16-alpine`, and the console's default is 18.x. The partial unique index this
  slice depends on behaves the same on both, but "tests pass on a different major than
  production" is a habit worth not forming.
- **`--manage-master-user-password`** — RDS generates the password, stores it in Secrets
  Manager as `rds!db-…`, and never shows it to you. Nothing types or pastes a password, so
  there is nothing to leak into shell history. This is what `ticket-ecs-execution-role`'s
  `ReadDatabaseSecrets` policy is scoped to (`secret:rds!*`).
- **`--db-name checkout`** — without it RDS creates *no* database and Flyway fails on
  connect. The instance and the database are not the same object.
- **`--availability-zone eu-central-1a`** — pins to the task's AZ.
- **`--backup-retention-period 0`** — disables automated backups. Nothing here is worth
  recovering (Flyway rebuilds the schema in seconds) and it makes deletion fast.
- **`--no-deletion-protection`** — the console default is *on*, and it makes teardown fail.
  Costly for a resource whose whole risk profile is "forgot to delete it".
- **`--db-subnet-group-name default-vpc-…`** — the default VPC's group already spans all
  three AZs. A DB subnet group must cover ≥2 AZs even for a single-AZ instance; the
  instance still lands in the one AZ named above. Reusing the default is one less free
  object to forget in the tag sweep.

### Publicly Accessible

`--publicly-accessible` puts a public IP on the database. That is only defensible because
the SG allows exactly two sources — this laptop's IP and the task SG — and because the
instance is deleted at the end of the session. It buys the ability to run `psql` against
the constraints directly, which is most of the verification value in this slice. Phase 2
makes it private and reaches it through the task instead.

## 4. Capture The Outputs

```bash
DB_HOST=$(aws rds describe-db-instances --db-instance-identifier ticket-checkout-db \
  --query 'DBInstances[0].Endpoint.Address' --output text)
SECRET_ARN=$(aws rds describe-db-instances --db-instance-identifier ticket-checkout-db \
  --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
echo "$DB_HOST"; echo "$SECRET_ARN"
```

Confirm the connection before involving ECS — this separates "the database is wrong" from
"the task definition is wrong", which otherwise present identically as a task that dies:

```bash
DB_PW=$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" \
        --query SecretString --output text | jq -r .password)

docker run --rm -e PGPASSWORD="$DB_PW" postgres:16-alpine \
  psql -h "$DB_HOST" -U ticketadmin -d checkout -c 'select version();'
```

Using the `postgres:16-alpine` image avoids installing a client, and guarantees the client
major matches the server.

## 5. Task Definition

```bash
IMAGE="$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com/checkout-payment-service@sha256:da24762ce3d8ad39e09c3d30293f7f48d4c2e8322c65fed0c02b00916d5e9666"

cat > /tmp/checkout-taskdef.json <<JSON
{
  "family": "checkout-payment-service",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "runtimePlatform": { "cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX" },
  "executionRoleArn": "arn:aws:iam::$ACCOUNT:role/ticket-ecs-execution-role",
  "containerDefinitions": [{
    "name": "checkout-payment-service",
    "image": "$IMAGE",
    "essential": true,
    "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
    "environment": [
      { "name": "DB_URL", "value": "jdbc:postgresql://$DB_HOST:5432/checkout" }
    ],
    "secrets": [
      { "name": "DB_USERNAME", "valueFrom": "${SECRET_ARN}:username::" },
      { "name": "DB_PASSWORD", "valueFrom": "${SECRET_ARN}:password::" }
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/checkout-payment-service",
        "awslogs-region": "$AWS_REGION",
        "awslogs-stream-prefix": "ecs"
      }
    }
  }]
}
JSON

aws ecs register-task-definition --cli-input-json file:///tmp/checkout-taskdef.json \
  --query 'taskDefinition.taskDefinitionArn' --output text
```

Notes:

- **No task role.** The application makes no AWS API calls. The secret is fetched by the
  *execution* role before the container starts and handed over as a plain environment
  variable, so there is nothing for a task role to grant.
- **`:username::` / `:password::`** — the trailing `:key::` selects one field out of the
  secret's JSON. Both colons are required: the full grammar is
  `secret-arn:json-key:version-stage:version-id`, and the empty trailing segments mean
  "current version". Drop them and the container receives the entire JSON blob as the
  password, which fails as an authentication error rather than a syntax error.

> **zsh trap, worse than the ECR one:** write `${SECRET_ARN}:username::`, never
> `$SECRET_ARN:username::`. Unbraced, zsh reads `:u` as its *uppercase* modifier, so the
> value becomes `ARN:AWS:SECRETSMANAGER:...:RDS!DB-ABC-XYZsername::` — the whole ARN
> upper-cased and the `u` eaten. `:password::` is unaffected, so only `DB_USERNAME` breaks,
> and it surfaces as `ResourceNotFoundException: Secrets Manager can't find the specified
> secret` — an error that points at IAM, not at your quoting. Same failure family as
> `$REPO:latest` in `runbook-ecr-packaging.md`; heredocs do not protect you, because the
> delimiter here is unquoted so the shell still expands.
- **`DB_URL` is `environment`, not `secrets`** — an RDS-managed secret contains the
  credentials, not the endpoint. The host cannot come from it.
- **Digest, not tag.** Pinning `@sha256:` makes "which image is running" answerable during
  a rollback; `latest` does not.

## 6. Run It

A one-off Spot task rather than a service — nothing here needs restarting on failure, and
`stop-task` is a cleaner teardown than deleting a service.

```bash
TASK_ARN=$(aws ecs run-task --cluster ticket-waiting-room \
  --capacity-provider-strategy capacityProvider=FARGATE_SPOT,weight=1 \
  --task-definition checkout-payment-service \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_A],securityGroups=[$TASK_SG],assignPublicIp=ENABLED}" \
  --query 'tasks[0].taskArn' --output text)

aws ecs wait tasks-running --cluster ticket-waiting-room --tasks $TASK_ARN

ENI=$(aws ecs describe-tasks --cluster ticket-waiting-room --tasks $TASK_ARN \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)
PUB=$(aws ec2 describe-network-interfaces --network-interface-ids $ENI \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)
echo "http://$PUB:8080"
```

`assignPublicIp=ENABLED` is mandatory: there is no NAT Gateway in this phase, so without a
public IP the task cannot reach ECR and dies before your code exists.

Watch Flyway apply the migration:

```bash
aws logs tail /ecs/checkout-payment-service --since 5m --follow
```

Expect `Successfully applied 1 migration` then `Started CheckoutPaymentServiceApplication`.

## Verify

```bash
API="http://$PUB:8080"

# happy path
OID=$(curl -s -X POST $API/orders -H 'content-type: application/json' -d '{
  "eventId":"evt-1","customerRef":"cust-1","amountCents":12000,
  "idempotencyKey":"order-aws-1",
  "seats":[{"seatId":"A-1","holdId":"hold-1"}]}' | jq -r .id)

curl -s -X POST $API/orders/$OID/payment -H 'content-type: application/json' \
  -d '{"idempotencyKey":"pay-aws-1","paymentToken":"tok_ok"}' | jq
# -> PAID / CAPTURED, some gatewayRef

# replay: same key must return the *same* answer, not an error
curl -s -X POST $API/orders/$OID/payment -H 'content-type: application/json' \
  -d '{"idempotencyKey":"pay-aws-1","paymentToken":"tok_ok"}' | jq
# -> identical gatewayRef
```

### The race

30 concurrent charges with *distinct* idempotency keys against one order. Exactly one may
win; the partial unique index is what makes that true, not any application check.

```bash
OID=$(curl -s -X POST $API/orders -H 'content-type: application/json' -d '{
  "eventId":"evt-1","customerRef":"cust-2","amountCents":9900,
  "idempotencyKey":"order-aws-race",
  "seats":[{"seatId":"B-7","holdId":"hold-2"}]}' | jq -r .id)

mkdir -p /tmp/race && rm -f /tmp/race/*
for i in $(seq 1 30); do
  curl -s -o /tmp/race/$i.json -w '%{http_code}\n' \
    -X POST $API/orders/$OID/payment -H 'content-type: application/json' \
    -d "{\"idempotencyKey\":\"race-$i\",\"paymentToken\":\"tok_ok\"}" &
done > /tmp/race/codes.txt
wait
sort /tmp/race/codes.txt | uniq -c        # expect 1x200, 29x409
```

> **Trap:** write each response to its own file with `-o`. Thirty parallel `curl`s sharing
> stdout interleave their JSON mid-object and the output is unparseable — a lesson already
> paid for in the waiting-room slice.

### The invariant, checked at the database

The tests assert this locally; assert it against the real instance too, because the claim
is about Postgres, not about the service:

```bash
docker run --rm -e PGPASSWORD="$DB_PW" postgres:16-alpine \
  psql -h "$DB_HOST" -U ticketadmin -d checkout -Atc "
    select (select count(*) from (select order_id from payments
              where status in ('PENDING','CAPTURED')
              group by order_id having count(*) > 1) x) as orders_with_multiple_live_payments,
           (select count(*) from payments p join orders o on o.id = p.order_id
              where p.status = 'CAPTURED' and o.status <> 'PAID') as captured_without_paid_order;"
# -> 0|0
```

## Results (2026-08-27)

Startup on 0.25 vCPU: **68.6 s** to `Started CheckoutPaymentServiceApplication` — against
2.5 s for the same image locally. Flyway applied V1 and `ddl-auto: validate` passed against
the schema it had just built.

| Check | Result |
|---|---|
| Happy path | `201` order → `200` `PAID`/`CAPTURED` |
| Replay of a settled idempotency key | same `gatewayRef`, not an error |
| Decline (`tok_decline`) | `FAILED`/`DECLINED`, reason `card_declined` |
| 30 concurrent charges, distinct keys, one order | **1×200, 29×409** |
| `orders_with_multiple_live_payments` | **0** |
| `captured_without_paid_order` | **0** |

Three orders produced exactly three payment rows: the 29 losers were rejected *before*
inserting, which is the partial unique index doing the work rather than application code.

## Two Failures Worth Keeping

Both cost a task launch each, and both produced errors that pointed somewhere other than
the cause.

### The task carried the database's security group

First launch used `checkout-db-sg` — the group that *receives* 5432 — instead of
`checkout-payment-sg`, the group the database's rule *allows*. Two similarly-named groups
in one picker, one letter apart in the console list.

It surfaced as `SocketTimeoutException: Connect timed out`, not a refusal, because a
security group drops unmatched packets silently rather than sending RST. **Timeout means
security group; refusal means nothing is listening.** That distinction is the fastest
triage step available and it is worth reaching for first.

The launched SG is not in `describe-tasks`, and the ENI is deleted when the task stops, so
after the fact the only record is CloudTrail:

```bash
aws cloudtrail lookup-events --lookup-attributes AttributeKey=EventName,AttributeValue=RunTask \
  --max-results 3 --query 'Events[].CloudTrailEvent' --output json \
  | python3 -c "import sys,json;[print(json.loads(e)['requestParameters']['networkConfiguration']) for e in json.load(sys.stdin)]"
```

### `Value` where `ValueFrom` was meant

Second launch failed with:

```
FATAL: password authentication failed for user
  "arn:aws:secretsmanager:eu-central-1:<account-id>:secret:rds!db-"
```

The console's environment-variable rows default the type dropdown to **Value**, so both
credentials were registered as literal strings in `environment` and `secrets` came back
`null`. The application dutifully tried to log in as an ARN.

Two things make this fast to spot next time:

- **The username is truncated at exactly 63 characters** — Postgres's `NAMEDATALEN - 1`.
  An error quoting a *cut-off* ARN is the signature of a literal ARN being used as an
  identifier, not of a permissions problem.
- **`describe-task-definition` settles it in one call.** `secrets: null` with ARNs sitting
  in `environment` is the whole diagnosis:

```bash
aws ecs describe-task-definition --task-definition checkout-payment-service \
  --query 'taskDefinition.containerDefinitions[0].{env:environment,secrets:secrets}'
```

Note what this failure proves about the ordering: the app reached Postgres and got a clean
`28P01` rejection, so the network path was already correct at that point. An auth error is
*good news* relative to a timeout — it means everything below the credential is working.

### Proving the network path independently

When it was still unclear whether the problem was DNS, routing or security groups, a
throwaway task in the *same subnet and security group* answered it in one launch —
`postgres:16-alpine` with an `entryPoint` of `sh -c`:

```
nslookup <rds-endpoint>          -> 172.31.25.212   (private, as it should be in-VPC)
nc -zv -w 5 <rds-endpoint> 5432  -> open
nc -zv -w 5 172.31.25.212 5432   -> open
nc -zv -w 5 <aws-public-ip> 5432  -> timed out
```

Worth understanding rather than just noting: from inside the VPC the endpoint resolves to
the **private** address even though the instance is publicly accessible, which is why the
SG-to-SG rule is what matters. Reaching the *public* address from inside times out —
that path leaves through the internet gateway and arrives with the task's public IP as the
source, which no rule permits. If in-VPC DNS ever returned the public address, this is
exactly how it would fail.

## Teardown

Order matters — the DB SG cannot be deleted while RDS holds it, and the task SG cannot be
deleted while the DB SG's rule references it.

```bash
aws ecs stop-task --cluster ticket-waiting-room --task $TASK_ARN --reason "session over"

aws rds delete-db-instance --db-instance-identifier ticket-checkout-db \
  --skip-final-snapshot --delete-automated-backups
aws rds wait db-instance-deleted --db-instance-identifier ticket-checkout-db

aws ec2 delete-security-group --group-id $DB_SG
aws ec2 delete-security-group --group-id $TASK_SG

# confirm nothing survived
aws rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier'
aws secretsmanager list-secrets --query 'SecretList[].Name'
```

**`--skip-final-snapshot` on purpose.** The plan's teardown line says "keep final
snapshot", but a final snapshot outlives the instance and bills storage indefinitely — for
a database whose entire content is regenerated by Flyway and a seed script in under a
minute. The snapshot is worth taking when there is data worth keeping; there is not.

**Stopping is not teardown.** A stopped RDS instance still bills its 20 GB of storage, and
RDS automatically restarts it after 7 days — the classic way to discover a $15 line item a
fortnight later.

The RDS-managed secret is deleted along with the instance. The `list-secrets` check above
confirms it; if it lingers, `aws secretsmanager delete-secret --secret-id "$SECRET_ARN"
--force-delete-without-recovery`.

The log group and the task definition stay — both free, and the task definition is a
useful record of the wiring. Note the task definition's `DB_URL` now points at an endpoint
that no longer exists: next session's instance gets a *new* endpoint, so register a new
revision rather than reusing `:1`.

## Phase 2 Notes

- Private subnets, `--no-publicly-accessible`, and reach the DB only from the task SG.
- IAM database authentication instead of a password secret — removes the credential
  entirely rather than protecting it better.
- The `$MYIP` ingress rule is inherently un-Terraformable (it changes with the network);
  in Phase 2 it becomes a bastion or SSM session, not a CIDR.
