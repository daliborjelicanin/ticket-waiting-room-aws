# Runbook — ElastiCache (Redis) & the Second Fargate Service

Slice 3's infrastructure: `waiting-room-service` running as a second Fargate task in the
existing cluster, backed by a single-node ElastiCache Redis cluster reachable only from
that task. Written as a forward guide on 2026-08-23; update the Results section with what
actually happened, and add whatever traps bite.

Prerequisite: the image is already in ECR — see `runbook-ecr-packaging.md`.
`<account-id>.dkr.ecr.eu-central-1.amazonaws.com/waiting-room-service:6a55391`,
digest `sha256:1c52fcc2af92b4a16ff77daa16eb17cd03af230a766510d343d8342326da2cc4`.

## What Gets Built

| Property | Value |
|---|---|
| Execution role | `ticket-ecs-execution-role` — **new**, see below |
| Task role | **none** as built — superseded by slice 6, see below |
| Cache subnet group | `ticket-waiting-room-cache-subnets` |
| Cache security group | `waiting-room-cache-sg` — inbound tcp/6379 from `waiting-room-sg` |
| Task security group | `waiting-room-sg` — inbound tcp/8080 from `<my-ip>/32` |
| ElastiCache cluster | `ticket-waiting-room-cache`, Redis OSS 7.1, `cache.t4g.micro` |
| Replicas | **0**, cluster mode disabled, Multi-AZ off, no backups |
| Task definition | `waiting-room-service:1` — 0.25 vCPU / 0.5 GB |
| Env | `REDIS_HOST=<primary endpoint>`, `REDIS_PORT=6379` (+ `REDIS_SSL` if TLS on) |
| Logs | `/ecs/waiting-room-service`, retention **1 day** |
| Subnet / AZ | `<subnet-id>` (eu-central-1a), public IP on |
| VPC | `<vpc-id>` (default) |
| Cluster | `ticket-waiting-room` (already exists, ACTIVE) |

**Cost while running:** ~$0.017/hr cache + ~$0.004/hr Fargate Spot ≈ **$0.021/hr**.
The cache bills whether or not anything connects to it — it is the single thing in this
slice that must be deleted at the end of every session.

## Two Things That Changed Since Slice 1

Verified against the live account on 2026-08-23 before writing this:

**`ecsTaskExecutionRole` was gone — already fixed.** The role inherited from an unrelated
2024 project was taken by the account sweep, which broke `seat-inventory-service:2` as well
as anything new. This is the Phase 2 note ("create a project-owned execution role")
arriving early, so it was built as `ticket-ecs-execution-role` and both services now use
it. **Step 1 below is already done** — it is kept as a record of how the role was made.

`seat-inventory-service:3` was registered as a copy of revision 2 with the new execution
role and the original `seat-inventory-task-role` intact. Use revision **3** from now on;
revision 2 is permanently broken.

**The IP in `seat-inventory-sg` is still current** (`<my-ip>/32`), so the same
value works for the new SG. Re-check it before blaming anything else if curl hangs —
a stale `/32` presents as a hang against a perfectly healthy task.

## Order of Operations

Two hard sequencing constraints, both of which cost a rebuild or a wasted wait if ignored:

1. **The cache must exist before the task definition**, because `REDIS_HOST` is a required
   environment variable and the primary endpoint does not exist until the cluster is
   created. The image carries no working default on purpose.
2. **`waiting-room-sg` must exist before `waiting-room-cache-sg`**, because the cache SG's
   inbound rule references the task SG as its source rather than a CIDR.

Also: ElastiCache creation takes **5–10 minutes**, and deletion takes about the same.
Start it, then build the log group and roles while it provisions.

## Console Steps

### 1. Execution role — IAM ✅ already created

**IAM → Roles → Create role → AWS service → Elastic Container Service → "Elastic
Container Service Task"**. Attach the managed policy **`AmazonECSTaskExecutionRolePolicy`**
(this is the one case where a managed policy is right — it is exactly ECR pull + CloudWatch
Logs write, nothing else). Name it `ticket-ecs-execution-role`, tag
`project=ticket-waiting-room`.

The trust policy adds the confused-deputy guard AWS documents for this role, so the ECS
service principal can only assume it on behalf of *this* account's ECS resources rather
than any caller who names the role:

```json
{
  "Effect": "Allow",
  "Principal": { "Service": "ecs-tasks.amazonaws.com" },
  "Action": "sts:AssumeRole",
  "Condition": {
    "StringEquals": { "aws:SourceAccount": "<account-id>" },
    "ArnLike": { "aws:SourceArn": "arn:aws:ecs:eu-central-1:<account-id>:*" }
  }
}
```

Verified on 2026-08-23 by running `seat-inventory-service:3` as a one-off Spot task: it
reached RUNNING in ~5 s, pulled the image from ECR and created a log stream — both are
execution-role work, so the conditions are correct. Task stopped immediately after.

> If a task ever fails in the execution-role phase after an account or region change, this
> condition is the first thing to check: `aws:SourceArn` is region-pinned to
> `eu-central-1`, so the same role will not work in another region without widening it.

**No task role for this service, as originally built.** `waiting-room-service` talked only
to Redis; its access is gated by security group, not IAM. This was the instructive contrast
with slice 1, where `seat-inventory-task-role` carried three DynamoDB actions.

> **Superseded by slice 6.** The service now publishes admitted tokens to
> `queue-admissions` so the WebSocket notifier can push them, which is an AWS API call and
> therefore needs a task role — `ticket-waiting-room-task-role`, carrying `sqs:SendMessage`
> on that one queue ARN and nothing else. See `runbook-websocket-queue.md`. The contrast
> above survives in a narrower form: one permission for one call, rather than none.

### 2. Log group — CloudWatch

**CloudWatch → Log groups → Create log group**: `/ecs/waiting-room-service`,
retention **1 day**. Create it by hand — a group auto-created by the task-definition
wizard gets *Never expire*, which quietly breaks the cost ground rules.

### 3. Task security group — EC2

**EC2 → Security groups → Create security group**:
- Name `waiting-room-sg`, VPC `<vpc-id>`
- Inbound: **Custom TCP, port 8080, source `<my-ip>/32`**
- Outbound: leave default allow-all (needed for the ECR pull and the cache connection)
- Tag `project=ticket-waiting-room`

### 4. Cache security group — EC2

Create a second SG, `waiting-room-cache-sg`, same VPC:
- Inbound: **Custom TCP, port 6379**, and for **Source** choose **Custom → start typing
  `waiting-room-sg`** and pick the security group, *not* a CIDR block.
- Outbound: default.

Referencing a security group rather than an IP range is the point of this step: the rule
says "whatever ENI carries the task SG may connect", which keeps working when the Fargate
task's IP changes on every deployment — and it does.

### 5. Cache subnet group + cluster — ElastiCache

There are no cache subnet groups in this account yet, so the wizard will create one.

**ElastiCache → Redis OSS caches → Create Redis OSS cache.**

- **Deployment option: "Design your own cache"** — ⚠️ **not Serverless.** Serverless has a
  minimum monthly charge far above the $10 budget; it is the one genuinely expensive
  mistake available in this wizard.
- **Creation method:** Cluster cache
- **Cluster mode: Disabled**
- **Name:** `ticket-waiting-room-cache`
- **Engine version:** `7.1`, **Port** `6379`, default parameter group
- **Node type:** `cache.t4g.micro`
- **Number of replicas: 0** (this also greys out Multi-AZ and auto-failover, which is what
  we want — the console defaults to replicas, so this must be changed explicitly)
- **Subnet group:** Create new — name `ticket-waiting-room-cache-subnets`, VPC
  `<vpc-id>`, and select **`<subnet-id>` (eu-central-1a)**.
  If the console insists on more than one subnet, add the others but then set the
  cluster's **preferred availability zone to eu-central-1a** — see the cross-AZ trap.
- **Security:** security group `waiting-room-cache-sg`. Encryption at rest: leave the
  default AWS-owned key (no KMS charge).
- **Encryption in transit:** see the decision note below.
- **Backup:** **uncheck "Enable automatic backups"** — pointless for an ephemeral queue and
  it bills S3 storage.
- Tag `project=ticket-waiting-room`.

Then wait for status **available** and copy the **Primary endpoint** (hostname only, drop
the `:6379`). For a cluster-mode-disabled cluster this is the *Primary endpoint*, not a
"Configuration endpoint" — the latter belongs to cluster mode and Lettuce is not
configured for it here.

#### Encryption in transit

Off is consistent with the other deliberate phase-1 simplifications (no NAT, public
subnets, single AZ), and the cache is still unreachable except from the task SG.

If you turn it **on**, add `REDIS_SSL=true` to the task definition's environment variables
in step 6. The image already supports it — `application.yml` reads
`spring.data.redis.ssl.enabled: ${REDIS_SSL:false}` — and no truststore work is needed,
because ElastiCache certificates chain to Amazon Root CA which the Temurin base image
already trusts. Note that in-transit encryption is also the prerequisite for Redis AUTH /
RBAC, so leaving it off means the cache has no authentication at all.

### 6. Task definition — ECS

**ECS → Task definitions → Create new task definition**:
- Family `waiting-room-service`
- Launch type **AWS Fargate**, **Linux/X86_64**, **0.25 vCPU / 0.5 GB**
- **Task role:** `ticket-waiting-room-task-role` since slice 6 (none before it).
  Execution role: `ticket-ecs-execution-role`
- Container name `waiting-room-service`, image
  `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/waiting-room-service@sha256:1c52fcc2af92b4a16ff77daa16eb17cd03af230a766510d343d8342326da2cc4`
  (pin by digest, not by tag — it makes "which image is running" answerable in a rollback)
- Port mapping **8080/tcp**
- **Environment variables** — ⚠️ the *Environment variables* section, **not Secrets**:
  - `REDIS_HOST` = the primary endpoint hostname
  - `REDIS_PORT` = `6379`
  - `REDIS_SSL` = `true` *only if* encryption in transit was enabled
- Logging: awslogs → the `/ecs/waiting-room-service` group from step 2, prefix `ecs`
- No health check — this service has no actuator dependency yet.

### 7. Service — ECS

**Clusters → `ticket-waiting-room` → Services → Create**:
- Capacity provider **FARGATE_SPOT**
- Family `waiting-room-service`, latest revision
- Service name `waiting-room-service`, desired tasks **1**
- Networking: VPC `<vpc-id>`, **one subnet only** —
  `<subnet-id>`
- Security group: **`waiting-room-sg`** — remove the `default` SG the console pre-selects
- **Public IP: Turned on** (there is no NAT Gateway; without it the task cannot reach ECR
  and dies with `CannotPullContainerError` before your code exists)
- No load balancer

## Traps To Expect

**Serverless instead of "Design your own cache."** The expensive one. Check the deployment
option before anything else.

**`REDIS_HOST` typed into *Secrets* instead of *Environment variables*.** This bit once
already with `AWS_REGION` in slice 1. ECS then treats the value as an SSM parameter name
and fails in the execution-role phase with a misleading `unable to pull secrets or registry
auth: AccessDeniedException ... ssm:GetParameters`. The error names the execution role and
mentions registry auth; neither is the actual problem.

**A zero-stream log group is still the diagnostic.** If `/ecs/waiting-room-service` has no
streams, the container never ran — the problem is image pull or execution role, not your
code and not the cache security group.

**The cache is not reachable from your laptop. Ever.** ElastiCache has no public endpoint
option at all, so there is no `redis-cli` from home and no `curl` equivalent. Every
verification goes through the Fargate task. This is the real networking lesson of the
slice and the sharpest contrast with slice 1, where the task itself had a public IP.

**TLS mismatch surfaces as a timeout, not a TLS error.** If in-transit encryption is on and
`REDIS_SSL` is unset, Lettuce hangs and then fails on connection, with nothing in the
message pointing at TLS. Check this before suspecting the security group.

**Cross-AZ data transfer.** If the subnet group spans AZs and ElastiCache places the node
in eu-central-1b while the task runs in eu-central-1a, every Redis round trip crosses an AZ
boundary — billed each way, plus latency on an operation the queue does constantly. Pin
both to eu-central-1a.

**The service pins a task-definition revision.** Creating a new revision does not move a
running service. Confirm which revision the *service* points at:

```bash
aws ecs describe-services --cluster ticket-waiting-room --services waiting-room-service \
  --query 'services[0].{TaskDef:taskDefinition,Running:runningCount,Rollout:deployments[0].rolloutState}'
```

## Verify

```bash
export AWS_DEFAULT_REGION=eu-central-1
aws ecs wait services-stable --cluster ticket-waiting-room --services waiting-room-service

TASK=$(aws ecs list-tasks --cluster ticket-waiting-room --service-name waiting-room-service \
  --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster ticket-waiting-room --tasks "$TASK" \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)
IP=$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)
echo "$IP"

aws logs tail /ecs/waiting-room-service --since 5m
```

A clean boot with no Lettuce errors already proves the cache path: the connection is
established lazily, so exercise an endpoint before declaring victory.

### Smoke

```bash
curl -s "http://$IP:8080/events/evt-aws-1/queue" -XPOST -H 'content-type: application/json' -d '{"token":"alice"}'
curl -s "http://$IP:8080/events/evt-aws-1/queue" -XPOST -H 'content-type: application/json' -d '{"token":"bob"}'
curl -s "http://$IP:8080/events/evt-aws-1/queue/bob"
curl -s "http://$IP:8080/events/evt-aws-1/queue/admit" -XPOST -H 'content-type: application/json' -d '{"count":1}'
```

### The FIFO Proof — slice 3's checkbox

100 concurrent joins, then drain and check admission order matches the places handed out.

```bash
E=evt-aws-race
mkdir -p /tmp/wr/j /tmp/wr/a && rm -f /tmp/wr/j/* /tmp/wr/a/*

seq 1 100 | xargs -P 100 -I{} curl -s -o /tmp/wr/j/{}.json \
  -XPOST "http://$IP:8080/events/$E/queue" \
  -H 'content-type: application/json' -d '{"token":"user-{}"}'

for i in 1 2 3 4; do
  curl -s -o /tmp/wr/a/$i.json -XPOST "http://$IP:8080/events/$E/queue/admit" \
    -H 'content-type: application/json' -d '{"count":30}'
done

python3 - <<'PY'
import json, glob
places = [json.load(open(f)) for f in glob.glob("/tmp/wr/j/*.json")]
pos = sorted(p["position"] for p in places)
print("joins:               ", len(places))
print("distinct positions:  ", len(set(pos)))
print("contiguous 1..100:   ", pos == list(range(1, 101)))
expected = [p["token"] for p in sorted(places, key=lambda p: p["position"])]
got = []
for i in range(1, 5):
    got += json.load(open("/tmp/wr/a/%d.json" % i))["tokens"]
print("admitted:            ", len(got))
print("no duplicates:       ", len(set(got)) == len(got))
print("FIFO matches places: ", got == expected)
PY
```

> **Write each response to its own file.** 100 parallel curls sharing one stdout interleave
> mid-object and the JSON will not parse — this cost time during the local run. `-o` per
> request is the fix, not `-w '\n'`.

Expected, matching the local run: 100 distinct contiguous positions, `FIFO matches
places: True`, no duplicates.

### Results, 2026-08-23

Cache provisioned in **~5 min** (`creating` → `available`). Primary endpoint this session:
`ticket-waiting-room-cache.<cache-id>.ng.0001.euc1.cache.amazonaws.com:6379`, encryption in
transit **off**, so `REDIS_SSL` was left unset.

Task booted in **32.9 s** (Java 26.0.2, Spring Boot 4.1.0, Tomcat 11.0.22 on 8080), zero
ERROR/WARN lines, public IP `<aws-public-ip>`. Slightly faster than seat-inventory's 39.0 s
despite the extra Spring Data Redis scan — 60 dependency jars against ~79.

Full lifecycle, laptop → Fargate → ElastiCache:

```
GET  /events/evt-aws-1/queue/nobody        404  "Unknown queue token"
POST /events/evt-aws-1/queue {alice}       201  position 1, queueLength 1
POST /events/evt-aws-1/queue {bob}         201  position 2, queueLength 2
POST /events/evt-aws-1/queue {alice}       201  position 1  ← re-join kept the place
POST /events/evt-aws-1/queue/admit {1}     200  tokens ["alice"]
GET  /events/evt-aws-1/queue/bob           200  position 1  ← moved up after admission
```

**The fairness proof — 100 concurrent joins, then drained in batches of 30:**

```
joins:                100
distinct positions:   100
contiguous 1..100:    True
admitted:             100
no duplicates:        True
FIFO matches places:  True
```

Identical to the local Testcontainers result, now across a real network with real
round-trip latency between the Fargate task and the cache. The claim this proves is the
precise one: every concurrent joiner received a distinct, contiguous place, and admission
order matched the places that were handed out — so the number shown to a user is not a
lie. Nothing here relies on the order requests arrived at the API layer.

**No trap fired.** The two easiest console mistakes were both avoided: the cache SG holds a
real security-group reference rather than a CIDR, and the cache landed in eu-central-1a,
the same AZ as the task, so no Redis round trip crosses an AZ boundary.

One latent trap worth noting for next time: the task definition carries
`awslogs-create-group: true`. It is harmless here because the group was created by hand in
step 2 with 1-day retention — but had step 2 been skipped, ECS would have silently created
the group with *Never expire*, which is exactly the cost-rule breach step 2 exists to
prevent. The safety net hides the mistake rather than surfacing it.

## Teardown

**The cache is what bills. Delete it every session.**

```bash
export AWS_DEFAULT_REGION=eu-central-1

aws ecs delete-service --cluster ticket-waiting-room --service waiting-room-service --force

aws elasticache delete-replication-group --replication-group-id ticket-waiting-room-cache \
  --no-retain-primary-cluster
```

**It is `delete-replication-group`, not `delete-cache-cluster`.** The "Redis OSS cache"
wizard wraps even a single node in a replication group (`ticket-waiting-room-cache` with
member `ticket-waiting-room-cache-001`), and `delete-cache-cluster` refuses to touch a node
that belongs to one. `--no-retain-primary-cluster` is what stops it leaving the node behind
— still billing — after the group is gone.

Deletion is **not instant**: status goes `deleting` for ~4–5 minutes and the node bills
until it disappears. Poll until `describe-replication-groups` returns empty rather than
trusting the API's acceptance of the call:

```bash
aws elasticache describe-replication-groups \
  --query 'ReplicationGroups[].ReplicationGroupId' --output json
```

`--force` on the service matters — without it `DeleteService` wants desired count 0 first,
but `UpdateService --desired-count 0` only accepts an ACTIVE service, so a service already
DRAINING gives you `ServiceNotActiveException` from one call and "cannot be stopped while
it is scaled above 0" from the other.

There is no final-snapshot prompt worth taking for a queue — decline it. Confirm with
`describe-snapshots` afterwards that none was left behind, since a retained snapshot keeps
billing S3 storage long after the cluster is gone.

Confirm nothing is left billing:

```bash
aws elasticache describe-cache-clusters --query 'CacheClusters[].CacheClusterId'
aws ecs describe-clusters --clusters ticket-waiting-room \
  --query 'clusters[0].{running:runningTasksCount,services:activeServicesCount}'
```

**Kept between sessions, all $0:** ECS cluster, task definition, both security groups, the
cache subnet group, the log group, `ticket-ecs-execution-role`, the ECR repository and
image.

## Notes for Phase 2 (Terraform)

- `aws_elasticache_cluster` (cluster mode disabled, `num_cache_nodes = 1`) +
  `aws_elasticache_subnet_group`. The AZ pinning belongs in the config, not in a console
  click, since cross-AZ cost is invisible until the bill.
- The SG-to-SG rule is `aws_vpc_security_group_ingress_rule` with
  `referenced_security_group_id` — express it that way rather than as a CIDR, so the
  dependency between task and cache is visible in the graph.
- `ticket-ecs-execution-role` should be a project-owned `aws_iam_role` shared by both
  services; the deleted `ecsTaskExecutionRole` is exactly the undeclared dependency on a
  hand-made artifact that Phase 1 warned about.
- The task role is a single-statement `aws_iam_role_policy` — `sqs:SendMessage` on the
  `queue-admissions` ARN. Worth an explicit comment that the narrowness is deliberate; a
  reader who knows the service also reaches Redis will otherwise assume permissions are
  missing rather than unnecessary.
- `REDIS_HOST` interpolates from the `aws_elasticache_cluster` resource's
  `cache_nodes[0].address`, which removes the manual sequencing constraint entirely.
- Revisit in-transit encryption + RBAC when the no-NAT/public-subnet simplification is
  revisited; the two belong to the same "learning-phase shortcut" bucket.