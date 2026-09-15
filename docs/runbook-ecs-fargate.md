# Runbook — ECS Cluster, Task Definition & Fargate Service

Slice 1's compute: `seat-inventory-service` running as a Fargate task in a public subnet,
reachable only from my IP, talking to DynamoDB through a least-privilege task role. Built
by hand in the console on 2026-08-22 and torn down the same session; this file is the
record of what was built, what broke, and the spec Phase 2's Terraform translates.

Prerequisite: the image is already in ECR — see `runbook-ecr-packaging.md`.

## What Was Built

| Property | Value |
|---|---|
| Cluster | `ticket-waiting-room` — Fargate only, Container Insights **disabled** |
| Capacity provider | `FARGATE_SPOT` (weight 1) |
| Task definition | `seat-inventory-service:2` — **superseded, see note below; use `:3`** |
| Size | 256 CPU units (0.25 vCPU) / 512 MB |
| Network mode | `awsvpc`, `X86_64` / `LINUX` |
| Image | `…/seat-inventory-service@sha256:697fc7a48212ce2ec37c61a75a17e4aeab915aff8d33d0de11e67a63a948611b` |
| Task role | `seat-inventory-task-role` |
| Execution role | `ecsTaskExecutionRole` — **deleted since; replaced by `ticket-ecs-execution-role`** |
| Port | 8080/tcp |
| Env | `AWS_REGION=eu-central-1` |
| Logs | `/ecs/seat-inventory-service`, retention **1 day**, prefix `ecs` |
| Subnet | `<subnet-id>` (eu-central-1a), **public IP enabled** |
| Security group | `<sg-id>` — inbound tcp/8080 from my IP `/32` only |
| VPC | `<vpc-id>` (default) |

**Cost while running:** ~$0.014/hr on-demand, ~$0.004/hr on Spot. This is the only
resource in slice 1 that bills — everything else (cluster, roles, SG, log group, table,
ECR image) is $0 and survives teardown.

## Task Role vs Execution Role

The single most useful thing this slice teaches, because the two fail in completely
different ways:

| | Execution role | Task role |
|---|---|---|
| Assumed by | the ECS agent, **before the container exists** | your JVM, at runtime |
| Used for | pulling the image from ECR, creating log streams, resolving `secrets` | every AWS SDK call your code makes |
| Here | `ecsTaskExecutionRole` | `seat-inventory-task-role` |
| Failure looks like | task never starts — `STOPPED`/`TaskFailedToStart`, **no log stream at all** | task runs fine, then throws on the first AWS call |

The credentials your code sees come from `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` on the
task metadata endpoint — the default provider chain picks this up with no configuration.
No credentials are baked into the image, which is why `SeatInventoryConfig` only overrides
the credentials provider when a LocalStack endpoint is set.

**A zero-stream log group is the diagnostic.** If `/ecs/<service>` has no streams, the
container never ran, so the problem is in the execution-role phase — not your code, not
your task role, not the security group.

### Least-Privilege Task Role

Trust policy principal `ecs-tasks.amazonaws.com`. Inline policy, and nothing else:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SeatInventoryTableAccess",
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:Query",
        "dynamodb:UpdateItem"
      ],
      "Resource": "arn:aws:dynamodb:eu-central-1:<account-id>:table/seat-inventory"
    }
  ]
}
```

Those actions are exactly what `SeatRepository` calls — no `Scan`, no `DeleteItem`, no
wildcard on the resource. Note the deliberate absence of `DeleteItem`: expiring holds are
DynamoDB's job via TTL, not the application's. `Query` was added on 2026-09-14 for the
`seats.remaining` gauge's `countAvailable` (slice 7): the role refused it first, the gauge
logged and kept its last reading, and the policy grew by one action — which is how a
least-privilege role is supposed to grow. See `runbook-observability.md`.

## Console Steps (as performed)

Order matters — the log group and roles must exist before the task definition references
them.

1. **IAM → Roles → Create role → AWS service → Elastic Container Service → "Elastic
   Container Service Task"**, no managed policies, name `seat-inventory-task-role`, then
   **Add permissions → Create inline policy → JSON** with the policy above.
2. **CloudWatch → Log groups → Create log group** `/ecs/seat-inventory-service`,
   retention **1 day**. Create it by hand — a group auto-created by the task-definition
   wizard gets *Never expire* retention, which quietly breaks the cost ground rules.
3. **EC2 → Security groups → Create**: `seat-inventory-sg` in the default VPC,
   inbound Custom TCP 8080 from `<my ip>/32`, outbound default allow-all (needed for the
   ECR pull and the DynamoDB call).
4. **ECS → Clusters → Create cluster**: `ticket-waiting-room`, **AWS Fargate only**
   (untick EC2), **Container Insights off** — it bills per metric.
5. **ECS → Task definitions → Create**: family `seat-inventory-service`, Fargate,
   Linux/X86_64, 0.25 vCPU / 0.5 GB, task role + execution role as above; container
   `seat-inventory-service`, the ECR image, port 8080/tcp, **environment variable**
   `AWS_REGION=eu-central-1`, awslogs → the group from step 2. No health check — the
   service has no actuator dependency yet.
6. **Cluster → Services → Create**: capacity provider **FARGATE_SPOT**, family +
   revision, service name `seat-inventory-service`, desired 1; networking = default VPC,
   **one subnet** (single AZ), `seat-inventory-sg`, **public IP Turned on**; no load
   balancer.

Two console defaults to override in step 6: it pre-selects the VPC's `default` security
group (remove it), and it pre-selects all subnets (keep one).

**Do not set `AWS_DYNAMODB_ENDPOINT`.** It must stay empty in AWS, or the client tries to
reach LocalStack.

### CLI Equivalent

```bash
export AWS_DEFAULT_REGION=eu-central-1

aws ecs create-cluster --cluster-name ticket-waiting-room \
  --capacity-providers FARGATE FARGATE_SPOT \
  --tags key=project,value=ticket-waiting-room

aws ecs register-task-definition --cli-input-json file://taskdef.json

aws ecs create-service --cluster ticket-waiting-room \
  --service-name seat-inventory-service \
  --task-definition seat-inventory-service:2 \
  --desired-count 1 \
  --capacity-provider-strategy capacityProvider=FARGATE_SPOT,weight=1 \
  --network-configuration 'awsvpcConfiguration={subnets=[<subnet-id>],securityGroups=[<sg-id>],assignPublicIp=ENABLED}'
```

## Traps Hit (all of them cost real time)

**`AWS_REGION` went into *Secrets* instead of *Environment variables*.** The console puts
the two sections side by side and it is easy to fill in the wrong one. ECS then treats the
value as an SSM parameter name:

```
ResourceInitializationError: unable to pull secrets or registry auth:
  AccessDeniedException: User: .../ecsTaskExecutionRole/... is not authorized to
  perform: ssm:GetParameters on resource: .../parameter/eu-central-1
```

The error names the **execution role** and the phrase "or registry auth" makes it look
like an ECR problem — neither is true, and the tempting fix (granting `ssm:GetParameters`)
would just buy permission to fetch a parameter that should not exist. `secrets[].valueFrom`
takes a Secrets Manager or SSM ARN; a literal value belongs in `environment[].value`.

**Creating a new task definition revision does not move the service.** The service pins an
exact revision and keeps deploying it until updated. Revision 2 sat there correct while the
service went on crash-looping revision 1 with the identical error. Always confirm which
revision the *service* points at, not just which revision exists:

```bash
aws ecs describe-services --cluster ticket-waiting-room --services seat-inventory-service \
  --query 'services[0].{TaskDef:taskDefinition,Running:runningCount,Rollout:deployments[0].rolloutState}'
```

**`rolloutState` lags reality.** It can still read `IN_PROGRESS` after `runningCount` hits
the desired count. `aws ecs wait services-stable` is the reliable signal; it returned
before `rolloutState` flipped to `COMPLETED`.

**zsh does not word-split unquoted variables.** `R="--region eu-central-1"; aws ecs ... $R`
passes the whole string as one argument and fails with `Unknown options: --region
eu-central-1`. Same family as the `$REPO:latest` trap in the ECR runbook. Use
`export AWS_DEFAULT_REGION=eu-central-1`, and use real arrays (`ROLES=(a b c)`;
`"${ROLES[@]}"`) for loops over lists.

**Public IP is not optional here.** There is no NAT Gateway, so `assignPublicIp=DISABLED`
leaves the task unable to reach ECR at all — it dies with `CannotPullContainerError` before
your code exists.

## Verify

```bash
export AWS_DEFAULT_REGION=eu-central-1
aws ecs wait services-stable --cluster ticket-waiting-room --services seat-inventory-service

TASK=$(aws ecs list-tasks --cluster ticket-waiting-room --desired-status RUNNING --query 'taskArns[0]' --output text)
ENI=$(aws ecs describe-tasks --cluster ticket-waiting-room --tasks "$TASK" \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)
IP=$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)

aws logs tail /ecs/seat-inventory-service --since 5m
```

There is no ALB and no static address: the public IP is on the task's ENI and **changes
every deployment**. That indirection — service → task → attachment → ENI → association —
is the whole reason slice 6 puts an ALB in front.

### Results, 2026-08-22

Task booted in **39.0 s** (Java 26.0.2, Spring Boot 4.1.0, Tomcat on 8080), zero
ERROR/WARN lines, public IP `<aws-public-ip>`, TCP connect 24 ms.

Full lifecycle against a fresh event, laptop → Fargate → DynamoDB:

```
POST /admin/events/evt-ecs-1/seats        201  {"created":3,"skipped":0}
POST /events/evt-ecs-1/seats/A-1/hold     201  holdId 8230af92…
POST /events/evt-ecs-1/seats/A-1/hold     409  "Seat unavailable"
DELETE  …/seats/A-3/hold/{holdId}         204
POST /events/evt-ecs-1/seats/A-3/hold     201  ← release genuinely returns the seat
POST …/A-3/purchase  {"holdId":"wrong"}   404  "Hold not found"
POST …/A-3/purchase  {"holdId":"<real>"}  200  {"status":"SOLD"}
```

**The concurrency proof — 30 simultaneous holds on one seat:**

```bash
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "http://$IP:8080/events/evt-ecs-1/seats/A-2/hold" | sort | uniq -c
#       1 201
#      29 409
```

Exactly one winner, on real DynamoDB, through a real Fargate task. Item state read back
directly afterwards:

```
A-1: HELD  holdId=8230af92…  holdExpiresAt=1787407824
A-2: HELD  holdId=c54b1e2d…  holdExpiresAt=1787407830
A-3: SOLD  holdId=ff2403e9…  holdExpiresAt=null
```

`A-3` is the one that matters: sold, winning `holdId` retained as audit trail,
`holdExpiresAt` removed — no sold ticket left scheduled for TTL deletion.

The first `201` is also the first real exercise of the task role. Every earlier failure
died in the execution-role phase, so DynamoDB access was unproven until that request.

**Left behind on purpose:** `A-1` and `A-2` stayed `HELD` with nobody to buy them. When
TTL fires, those rows will *disappear entirely* rather than return to `AVAILABLE` —
inventory silently shrinking by two seats. That is slice 2's cleanup Lambda justifying
itself with live data.

## Teardown

Delete the service; everything else stays at $0.

```bash
aws ecs delete-service --cluster ticket-waiting-room --service seat-inventory-service \
  --force --region eu-central-1
```

`--force` matters. Without it, `DeleteService` requires desired count 0 first — but
`UpdateService --desired-count 0` only accepts an **ACTIVE** service, so if the console has
already put it into `DRAINING` you get `ServiceNotActiveException` from the scale-down and
a "cannot be stopped while it is scaled above 0" from the delete. `--force` collapses both
steps and avoids the window.

Kept between sessions: cluster, task definitions, both IAM roles, security group, log
group, DynamoDB table, ECR repository. All $0.

### Account Sweep Note

While sweeping for stragglers, two Elastic Beanstalk buckets refused deletion with
`AccessDenied … explicit deny in a resource-based policy` despite full account ownership.
Beanstalk stamps its buckets with a policy containing
`Effect: Deny, Principal: "*", Action: s3:DeleteBucket`, and a resource-based deny beats
any IAM allow — including the account root's. `aws s3api delete-bucket-policy` first, then
`delete-bucket`. This is the usual reason an empty bucket you own will not delete.

## Notes for Phase 2 (Terraform)

- `aws_ecs_cluster` + `aws_ecs_cluster_capacity_providers`; `aws_ecs_task_definition` with
  `container_definitions` from `jsonencode()`; `aws_ecs_service` with
  `network_configuration` and `capacity_provider_strategy`.
- **Create a project-owned execution role.** ~~`ecsTaskExecutionRole` here is inherited
  from an unrelated 2024 project~~ — **this happened for real on 2026-08-23**: the account
  sweep deleted `ecsTaskExecutionRole`, which silently broke `seat-inventory-service:2`,
  since a task definition stores the role ARN and nothing revalidates it. Replaced by
  project-owned `ticket-ecs-execution-role` (`AmazonECSTaskExecutionRolePolicy` plus the
  `aws:SourceAccount`/`aws:SourceArn` confused-deputy conditions), and revision **3**
  re-registered against it. The lesson stands for Terraform, now with evidence: an
  undeclared dependency on a hand-made artifact fails at launch time, not at edit time.
- The task role's policy is a natural `aws_iam_policy_document` with the table ARN
  interpolated from the `aws_dynamodb_table` resource, rather than hardcoded.
- My IP in the security group belongs in a variable, not the config — it changes with the
  ISP lease, and a stale `/32` presents as curl hanging with a healthy task.
- Public subnet + public IP is the deliberate no-NAT simplification from the Phase 1 cost
  rules. Phase 2 should revisit: private subnets + VPC endpoints for ECR/DynamoDB/Logs is
  the no-NAT-Gateway way to keep it cheap *and* private.
- Pin the image by **digest**, as done here, rather than a mutable tag — it makes "which
  image is running" answerable during a rollback.