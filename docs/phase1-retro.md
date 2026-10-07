# Phase 1 Retro — Input for Terraform (Phase 2)

Phase 1 built every piece by hand, once, and wrote down what that cost in time, money and
mistakes. This is the cross-cutting layer that Phase 2 needs before writing its first
module. The per-resource detail (argument names, the right `aws_*` resource for each
console screen) stays in each runbook's *Notes for Phase 2* section, which this document
points to rather than repeats.

Written 2026-10-05, after the final sweep (`runbook-teardown-sweep.md`) confirmed the
account bills nothing by the hour.

## What Phase 1 Settled — Carry Over Unchanged

These were decided with evidence and are inputs to Terraform, not open questions:

- The concurrency mechanisms and their proofs: conditional writes on the seat table, the
  `INCR`-scored sorted set, the payment constraints, the ticket unique constraint, and
  delete-after-handler on every queue. Proven locally and then on AWS, ending in the load
  test: **seats SOLD = orders PAID = payments CAPTURED = tickets = 10, 0 double-sold**.
- `$connect`/`$disconnect` are DynamoDB service integrations, not Lambdas
  (`runbook-websocket-queue.md`, *Settled*).
- The saga routes a decline through a **Choice**, not `Catch`. After capture, compensation
  is a refund and never a release.
- Lambdas are container images on `eclipse-temurin` with the RIC, with the HTTP stacks
  stripped out. No managed runtime offers JDK 26.
- Telemetry goes over OTLP to a collector sidecar (`runbook-observability.md`). In EKS this
  becomes a DaemonSet with no service change.
- RDS is torn down with `--skip-final-snapshot`. Flyway and the seed rebuild it in a minute,
  and a snapshot bills indefinitely.

## 1. Two Stacks, Because Phase 1 Already Had Two Lifecycles

Every runbook's teardown sorted its resources into "stays at $0" and "goes every session".
That split is the Terraform layout: two root configurations, with the second reading the
first's outputs.

| `foundation`, applied once, $0 standing | `session`, `apply` at the start, `destroy` at the end |
|---|---|
| VPC, subnets, route tables, security groups and their SG-to-SG rules | ALB, listener, rules, **target groups** |
| DynamoDB `seat-inventory`, `waiting-room-connections` (+ GSI, TTL) | RDS instance (its managed secret is created and deleted with it) |
| ECR repositories **with a lifecycle policy** (keep the last 1–2) | ElastiCache cluster + subnet group |
| S3 QR bucket (SSE-KMS, 7-day lifecycle) | ECS services **and their task definitions** |
| SQS queues + DLQs + redrive, SNS topic + both subscriptions | EventBridge connection ($0.40/mo, so it is session-only) |
| SES identities; Cognito pool, domain, resource server, clients | `checkout-saga` state machine |
| IAM roles and policies, every one | WAF web ACL + association |
| The three Lambdas + both event source mappings | REST API **stage** (`dev`): stage variables, X-Ray, WAF target |
| WebSocket API + integrations + stage | `hold-seat-schedule` (free, but it should only run during a session) |
| REST API `ticket-edge` resources, methods, deployment | NAT gateway, if §5 chooses one |
| SSM collector config, log groups, dashboard, telemetry policy, budgets | |

**The rule that decides edge cases: anything that references a session value belongs to the
session stack.** Phase 1 kept the target groups because recreating five listener rules by
hand was tedious. In Terraform that reason disappears, and an orphaned target group is just
drift. The state machine is free, but its definition embeds the connection ARN, so
it moves with the connection, and the five-occurrence `sed` from `runbook-checkout-saga.md`
becomes a `templatefile()` reference. Task definitions are free but embed the RDS secret ARN
and the Redis host, so they move too. The REST API's invoke URL is
`{api-id}.execute-api…/dev`. Destroying and recreating the stage leaves that URL
unchanged, so `TICKET_VERIFY_BASE_URL` and the load-test scripts never see the split.

**Per-proof toggles inside `session`.** Every Phase 1 session brought up only what its proof
needed, and WAF was proven with *nothing else* up. Gate the expensive pieces with variables
(`enable_cache`, `enable_db`, `enable_alb`, `enable_waf`, `services = [...]`), so the
`session` apply covers each runbook's *What To Bring Up First* table instead of the whole
stack.

## 2. Every Value A Rebuild Minted Becomes A Reference

The real cost of rebuilding by hand was carrying fresh identifiers into the places that
held the old ones. Slice 5's cold-account rebuild (2026-09-05) found them by breaking. Each
row below is a manual step that Terraform's graph removes:

| Value | Hand-carried into | Terraform source |
|---|---|---|
| RDS master secret ARN (`rds!db-…`) | checkout + issuance task definitions' `valueFrom` | `aws_db_instance.master_user_secret[0].secret_arn` |
| EventBridge connection ARN | state machine (×7), saga role policy | `aws_cloudwatch_event_connection.arn` via `templatefile()` |
| ALB DNS name | `ticket-edge` stage variable `albDns` | `aws_lb.dns_name` |
| Redis endpoint | `REDIS_HOST` | `primary_endpoint_address` (replication group) or `cache_nodes[0].address` |
| Cognito issuer | `COGNITO_ISSUER_URI` in four task definitions | the pool's `endpoint` attribute |
| M2M client secret | EventBridge connection OAuth parameters, typed by a human | `aws_cognito_user_pool_client.client_secret`, never seen |
| WebSocket API id | `WEBSOCKET_ENDPOINT`, the notifier role's `execute-api` ARN | `api_endpoint` / `execution_arn` |
| Stage invoke URL | `TICKET_VERIFY_BASE_URL` | `aws_api_gateway_stage.invoke_url` |
| Queue URLs | `ADMISSION_QUEUE_URL` and friends | `aws_sqs_queue.url` |
| Image | every task definition, the three functions | a digest **variable**, see §6 |
| My IP | the `/32` on the DB and task SGs | a variable, never a literal (it changes with the ISP lease) |

**The trap to design out:** the RDS *endpoint hostname* survives a rebuild while the secret
ARN does not, so a stale task definition looks right. Also a stale image runs green: the
waiting room's `6a55391` predated the publishing code entirely, and would have run healthy
while publishing nothing. Neither defect exists once both values are references.

**Elasticache: choose the resource deliberately.** The console's "Redis OSS cache" wizard
created a *replication group* of one, which is why `delete-cache-cluster` refused it and
`--no-retain-primary-cluster` was needed. `runbook-waiting-room-redis.md`'s note says
`aws_elasticache_cluster`. Either works, but pick one knowing that the hand-built resource
was the other.

## 3. Defaults That Failed Silently — Pin Every One Explicitly

Each of these was right, or wrong, without any error. Terraform modules should set every one
explicitly, even where it equals the default, with a comment naming the Phase 1 symptom:

| Setting | What Phase 1 saw | Where |
|---|---|---|
| `raw_message_delivery = true` on the SNS→SQS subscription | every field null, service reports success | `runbook-notifications.md` |
| `function_response_types = ["ReportBatchItemFailures"]` | partial-batch handling silently off | both event source mappings |
| ESM `enabled = true` | a teardown left `qr-generation`'s mapping disabled; found a slice later | `runbook-cognito-auth.md` |
| `ttl { enabled = true }`, value in **epoch seconds** | TTL disabled by default; a 13-digit value is accepted and never expires | both tables |
| `integration_response_key = "/200/"` on the WebSocket integrations | handshake 500s **with the write committed**; `$default` key is accepted and unreachable | `runbook-websocket-queue.md` |
| `redrive_allow_policy` on each DLQ | nothing broke; anything could name the DLQ | `runbook-ticket-issuance.md` |
| `visibility_timeout_seconds` derived, not typed | `qr-generation-queue` 60 → 360 s (6× function timeout); notifications 30 → 120 s (SES sandbox 1/s) | a `locals` formula |
| Lambda log groups declared, `retention_in_days` set | the implicit group never expires | all three functions |
| IAM: `${table_arn}/index/*` beside the table ARN | `dynamodb:Query` on the GSI denied | `runbook-websocket-queue.md`, observability defect |
| IAM: `s3:ListBucket` beside `s3:GetObject` | HeadObject on a missing key is 403, so the QR endpoint 500'd instead of 404 | `runbook-cognito-auth.md` |
| `aws_api_gateway_deployment` `triggers` hash | method changes not deployed | `runbook-alb-apigateway.md` |
| Tracing on: `xray_tracing_enabled` (stage), `tracing_configuration` (SFN), `tracing_config` (Lambdas) | each is a separate switch | `runbook-observability.md` |
| Target group health check `/actuator/health`, matcher `200` | `200-499` was needed to tolerate the 401 before actuator existed | `runbook-observability.md` |
| `wait_for_steady_state = true` on services | `services-stable` returns before the target is healthy | `runbook-alb-apigateway.md` |
| Task definition `environment` vs `secrets` in `jsonencode()` | `AWS_REGION` landed in *Secrets*; `Value` where `ValueFrom` was meant | `runbook-ecs-fargate.md`, `runbook-checkout-rds.md` |
| Cache AZ (`preferred_availability_zones`) | cross-AZ traffic is invisible until the bill | `runbook-waiting-room-redis.md` |

WAF's `SearchString` trap (the CLI base64-decodes it, so the first rule matched nothing) is a
CLI artifact. The provider takes the plain string, but check in the plan that it reads as
`/purchase` and not as decoded bytes.

## 4. Nothing Hand-Made, Nothing Implied

- **Declare every role.** On 2026-08-23 an account sweep deleted `ecsTaskExecutionRole`,
  inherited from an unrelated 2024 project, and broke `seat-inventory-service:2`, because
  a task definition stores a role ARN that nothing revalidates. Today's sweep still found the
  console's own leftovers: `qr-generation-role-r4yviiqj` (Lambda's default, replaced the same
  day), `rds-monitoring-role` (never used), and an auto-named
  `Amazon_EventBridge_Scheduler_LAMBDA_…` that is still in use.
- **Write the conditions the console adds for you.** `aws:SourceAccount`/`aws:SourceArn` on
  the scheduler and execution-role trust policies, `aws:SourceArn` on the SQS queue policy
  for SNS, and `kms:ViaService` on the S3 key grant are confused-deputy protection. The
  console adds them silently, so they are easy to drop when writing HCL from memory.
- **Tighten the managed policies Phase 1 leaned on.** `AWSLambdaBasicExecutionRole` is
  `logs:*` on `*`. Scope it to the function's own log group.
- **One roles-per-identity rule, stated in the module:** a function's role is its identity
  when calling things. A scheduler's or API Gateway's role is *that service's* identity when
  calling into ours. The ESM is the exception that polls with the function's role. This
  distinction recurred in five slices, so it deserves a comment where the roles are defined.

## 5. Tags — `default_tags`, And Check The Check

The final sweep's tag search returned 59 ARNs and **missed 15 project resources**: the
`qr-generation` function, the `seat-inventory-service` repository, six log groups, six
security groups and the schedule. Provider-level `default_tags { project, phase, stack }`
makes the tag a property of the code rather than of whoever filled in the console. Also
**activate `project` as a cost-allocation tag**, so the bill can be split by stack, which
Phase 1 never did. Keep running `scripts/teardown-sweep.sh` after `destroy`: its TAGS tier
should then print nothing, and anything it prints was not created by Terraform.

## 6. Decisions To Make Before Writing Modules

**1. Network — the first module, and Phase 1's largest simplification.** Prices from the
Price List API, eu-central-1, 2026-10-05:

| Option | Hourly | Forgotten for a month | Notes |
|---|---|---|---|
| Status quo: public subnets, task IPs | $0.005 per public IPv4 (5 tasks + ALB ≥ 2) | — (session-only) | September's VPC line was **$0.14**, which is all IPv4. "No NAT" was never free networking |
| Private + one NAT gateway, single AZ | $0.052 + $0.052/GB | ≈ $38 | Add the **free gateway endpoints** for S3 (ECR layers come from S3) and DynamoDB so the hot paths skip NAT |
| Private + interface endpoints | $0.012 per endpoint per AZ | ≈ $8 each | Needs ~8–10 (ecr.api, ecr.dkr, logs, sqs, sns, secretsmanager, ssm, xray, email, …): **more than NAT** at this scale. The Cognito JWKS fetch path through PrivateLink is unverified |
| Private + NAT instance (e.g. fck-nat on `t4g.nano`) | ≈ $0.004 + IPv4 | ≈ $3 | Cheapest private option, but an EC2 instance to patch, which earns its place on cost alone |

Recommendation: **NAT gateway in the `session` stack plus the two gateway endpoints in
`foundation`.** It adds about 40% to a session's ≈ $0.10–0.15/h and nothing when idle. RDS
then goes `publicly_accessible = false`, and the `$MYIP` rule (inherently
un-Terraformable) becomes ECS Exec / SSM into a task for `psql`. Remember that the ALB and
the RDS subnet group each need **two AZs** of subnets even for a single-AZ deployment.

**2. The ALB behind WAF.** Today the ALB is internet-facing and trusts `X-Edge-Secret`.
`ticket-edge` has to stay a **REST** API. HTTP APIs have JWT authorizers and a Step
Functions integration too, but WAF cannot attach to them, and WAF is the requirement.
Phase 1's constraint was that a REST VPC link reaches only an NLB, which means NLB → ALB at
+$0.027/h. **Check whether that still holds:** AWS announced REST API private integration
with ALBs (VPC link v2) in late 2025. If it is available in eu-central-1, a private ALB
becomes a $0 change and `X-Edge-Secret` disappears instead of moving into Secrets Manager.
If not, an SG restricted to API Gateway's published ranges is the $0 fallback. Either way,
if the header survives, keep it in **SSM SecureString** (free), not Secrets Manager
($0.40/mo standing), and remember it lands in Terraform state.

**3. The hand-built resources: destroy, don't import.** Phase 2's proof is the same one
slice 5 gave the runbooks: rebuilt from a cold account, now from code. Importing would carry
the untagged resources, console defaults and auto-named roles straight into state. The
names collide otherwise (`seat-inventory`, the queues, the repositories). Destroy the
Phase 1 foundation once, after confirming nothing is wanted (the ECR images are rebuilt by
CI, the Cognito users by `create-test-users.sh`), then apply.

**4. State backend.** An S3 bucket with versioning and **`use_lockfile = true`** (S3-native
locking), with no DynamoDB lock table. The bucket itself is the one hand-made
bootstrap resource. Create it from a tiny `bootstrap/` config with local state, and say so.

**5. Images are not Terraform's job.** GitHub Actions (Phase 2's OIDC piece) builds, pushes,
and passes the **digest** in as a variable. Phase 1 already pinned by digest. Never use a
mutable tag, because "which image is running" must be answerable during a rollback.

**6. Migrations.** Flyway on service startup was fine for one task per service and is not
a deploy strategy (`runbook-ticket-issuance.md`). Decide between a one-off ECS task run by
CI before the service update, and keeping startup migration with the two databases
documented as single-writer.

**7. The WebSocket fan-out cap.** If it lands as a Lambda authorizer on `$connect`, the
module gains an authorizer and a second function. Settle it before writing that module
(`runbook-websocket-queue.md`, *The fan-out is uncapped*).

**8. Leave a seam for Phase 3 now.** An EKS control plane is **$0.10/h** (Price List API,
eu-central-1, 2026-10-07): about $73 for a month forgotten, and 10–15 minutes to create.
That strains the create-and-destroy-every-session pattern without breaking it. Two
consequences for the Phase 2 layout. Keep the ECS services and their task definitions in
modules that do not also own the ALB, queues or roles, so EKS can replace exactly those
modules and nothing else. And decide early whether Phase 3's cluster lives in `session` (a
slow apply every time) or in a third stack that is applied only for EKS sessions.

## 7. Carried Into Phase 2 As Open Work

- **`GET /tickets/{ticketId}` does not exist**: every QR encodes a URL that 404s. It is an
  authorisation question (who may verify a ticket), not a rendering one.
- **Step Functions emits no X-Ray segment** despite tracing on and `xray:*` on its role. The
  header still propagates through it.
- **The refund path is built but not yet run on AWS** (2026-10-07). A definite 404 from
  the confirm now refunds and releases, and the confirm was made idempotent first so a lost
  response can never be read as that 404. The deploy order matters (seat-inventory first)
  and the verification forces a hold to expire mid-payment: `runbook-checkout-saga.md`,
  *The Refund Path*. `PaidButNotConfirmed` remains, deliberately, for the case a timeout
  leaves unknowable.
- **No CloudWatch alarms exist at all.** The sweep found zero. Three DLQs, the swallowed
  admission-publish failure and the four Fail states a human has to finish
  (`RefundFailed`, `PaidButNotConfirmed`, `PaidButNotIssued`, `CompensationFailed`) are
  all watched by nobody. DLQ depth
  > 0 is the first alarm to write, as code.
- **Server-side p99**: `detailed_metrics: true` in the collector config. Timers already export
  histograms.
- **SES inbox placement** needs a domain identity with DKIM in Route 53. Address identities
  only gate acceptance. This is the one concrete reason for the optional custom domain.
- **Load test next points**: two tasks per service behind the ALB (losers' p99 was one
  0.25 vCPU task's queue), and a run with Bot Control enforced.

## 8. Where The Plan And The Account Disagreed

- Slice 4's teardown line says "keep final snapshot". The runbook decided
  `--skip-final-snapshot`, for the reason in *What Phase 1 Settled*.
- Slice 0's "billing alarm" is the two **Budgets** ($10 and a $1 zero-spend). No CloudWatch
  billing alarm exists. The budgets are enough, but should be `aws_budgets_budget` in code.
- Standing cost. The plan and README say ECR ≈ 1.1 GB / $0.07. The load-test rebuild left a
  second image per service, so it is 2.14 GB / ≈ $0.16 until the `5e3c555` tag is deleted.
  The EventBridge connection was also a standing $0.40/mo until sessions began deleting it.
- **Checking the bill is billed**: September's $0.06 Cost Explorer line is API requests at
  $0.01 each. The sweep script makes none.

## 9. Process Lessons

- **Rebuilding from cold found what review did not.** Slice 5's rebuild surfaced every
  minted-value problem in §2. The CI equivalent is `apply → smoke → destroy` against the
  session stack, which turns "re-creatable, not standing" into a test.
- **Local proof made AWS sessions short, but local numbers did not predict Lambda INIT**:
  538 ms on the RIE against 2849 ms on real Lambda, a 5.3× spread. Budget INIT from real
  measurements.
- **Every AWS session found at least one defect that was neither code nor configuration
  typo**: a missing IAM action, a disabled mapping, a stale image. Those are the defects
  Terraform plus a smoke test catch, which is the case for Phase 2 in one line.
- **zsh quoting** (`$VAR:h`, no word-splitting, globbed `/*/*` paths) cost time in four
  runbooks. Scripts are `bash` with a shebang, and the Terraform-era commands live in files,
  not in prose to paste.
