# Phase 1 Plan — Manual ECS/Fargate, Low-Cost

Goal of this phase: every AWS component built **by hand once**, understood, documented,
and torn down. The learning notes captured here become the Terraform spec in Phase 2.

## Cost Ground Rules

These override convenience everywhere in Phase 1:

- **Nothing runs while I'm not working on it.** Every slice ends with its teardown
  checklist executed. The demo is re-creatable, not standing.
- **No NAT Gateway.** Fargate tasks run in **public subnets with public IPs**
  (security groups locked to my IP where possible). Noted as a deliberate learning-phase simplification to revisit in Phase 2/3.
- **No Multi-AZ, no standby replicas.** Single AZ everywhere.
- **Smallest possible sizes**: Fargate 0.25 vCPU / 0.5 GB (Spot where supported),
  RDS `db.t4g.micro`, ElastiCache `cache.t4g.micro` (or local Redis until strictly needed).
- **On-demand / pay-per-request billing**: DynamoDB on-demand, no provisioned capacity.
- **Deferred until their slice, then session-only**: ALB, WAF, RDS, ElastiCache,
  Route 53 custom domain (optional entirely — $12/yr + $0.50/mo hosted zone).
- **Free or negligible at learning traffic** (safe to leave configured): DynamoDB,
  SQS, SNS, EventBridge, Step Functions, Lambda, S3, ECR (<1 GB), Cognito, CloudWatch
  logs (short retention), IAM.
- **Account guardrails before anything else**: AWS Budget alert at $10/mo,
  billing alarm, short CloudWatch log retention (1–3 days), everything tagged
  `project=ticket-waiting-room` so leftovers are findable.

## Working Rhythm

Each slice = **(a) build/extend service locally against LocalStack → (b) stand up the
AWS piece by hand → (c) verify end-to-end → (d) write down what I learned + exact
console/CLI steps → (e) tear down**. Code keeps advancing between AWS sessions for free.

## Slices

### 0. Account foundations — $0
- [x] Budget alert ($10) + billing alarm
- [x] IAM Identity Center (or IAM user w/ MFA) for daily work — no root usage
- [x] Pick region (eu-central-1 or cheapest nearby), default tags
- **Learn:** account hygiene, IAM basics

### 1. Seat Inventory on real AWS — the centerpiece — ~$0.01/hr while up
- [x] Code: release-hold + confirm-purchase endpoints, seed endpoint/script
- [x] DynamoDB table (on-demand) + TTL on `holdExpiresAt` — see `runbook-seat-inventory.md`
- [x] ECR repo, build & push image (first Docker packaging of a service) — see `runbook-ecr-packaging.md`
- [x] ECS cluster + task definition + Fargate service (public subnet, public IP, SG restricted to my IP), least-privilege task role for DynamoDB — see `runbook-ecs-fargate.md`
- [x] Verify: `curl` hold/release/confirm against public IP; race it with a concurrent script → exactly one 201
      — **30 concurrent holds on one seat → 1×201, 29×409** on real Fargate + DynamoDB (2026-08-22)
- **Teardown:** ECS service → cluster stays (free), table stays (free), ECR stays (free)
- **Learn:** ECR, task defs, task roles vs execution roles, Fargate networking

### 2. Hold expiration — $0 standing
- [x] EventBridge schedule → cleanup Lambda (first Lambda) reclaiming expired holds
      (conditional write: only if still HELD and expired) — see `runbook-hold-cleanup.md`
- [x] Verify TTL + cleanup interplay; document why TTL alone isn't enough
      — abandoned hold reclaimed by the schedule alone in **~53s**, no manual invoke;
      live holds and SOLD-with-stale-TTL seats untouched (2026-08-22)
- **Teardown:** disable the `hold-seat-schedule` schedule; everything else stays at $0
- **Learn:** Lambda packaging (container image + runtime interface client), EventBridge
  Scheduler, the 10s INIT budget, DynamoDB TTL semantics

### 3. Waiting Room service + Redis — Redis is session-only (~$0.017/hr)
- [x] Code: queue join/position/admit API on Redis sorted sets (Testcontainers locally)
      — FIFO comes from an `INCR` ticket number as the sorted-set score plus `ZPOPMIN`
      admission; each operation is one Lua script so stamp+enqueue and pop+admit cannot
      half-apply. **100 concurrent HTTP joins → 100 distinct contiguous places, admission
      order matched the places handed out exactly, 0 duplicates** (2026-08-23)
- [x] ElastiCache `cache.t4g.micro`, single node, same VPC; second Fargate service
      — see `runbook-waiting-room-redis.md`. Cache reachable only from the task's security
      group (no public endpoint exists for ElastiCache at all), pinned to eu-central-1a to
      keep every Redis round trip inside one AZ. No task role: this service makes no AWS
      API calls (2026-08-23)
- [x] Verify FIFO admission under concurrent joins
      — **100 concurrent joins on real Fargate + ElastiCache → 100 distinct contiguous
      places, admission order matched the places handed out exactly, 0 duplicates**;
      identical to the local Testcontainers result, now over a real network (2026-08-23)
- **Teardown:** delete ElastiCache cluster + ECS service every session — the cache bills
  whether or not anything connects to it
- **Learn:** ElastiCache networking/SGs, sorted-set queue pattern

### 4. Checkout saga: Step Functions + RDS + SQS — RDS is session-only (~$0.017/hr)
- [x] Code: checkout-payment service (orders/payments in Postgres, mock payment
      gateway with configurable failure), Flyway migrations
      — no-double-charge rests on two DB constraints, not application code: a unique
      idempotency key, and a **partial unique index** allowing at most one PENDING-or-
      CAPTURED payment per order. Gateway is called *outside* the transaction, between two
      committed ones, so a crash mid-charge leaves a recoverable PENDING row rather than an
      untracked charge. **50 concurrent charges with distinct keys on one order → 1×200,
      49×409, exactly one CAPTURED row, gateway invoked once** (2026-08-23)
- [x] RDS Postgres `db.t4g.micro` single-AZ; Secrets Manager for credentials
      — see `runbook-checkout-rds.md`. RDS-managed master password (`rds!db-…`), fetched by
      the *execution* role before the container starts, so the service still needs no task
      role. **30 concurrent charges with distinct keys on one order → 1×200, 29×409, exactly
      one CAPTURED row**; replay of a settled key returned the original `gatewayRef`;
      decline path left `FAILED`/`DECLINED`. Invariant re-checked in SQL against the real
      instance: 0 orders with >1 live payment, 0 CAPTURED payments whose order isn't PAID
      (2026-08-27)
- [x] Step Functions state machine: hold seats → charge → issue tickets → notify,
      with compensation (release holds) on payment failure — see `runbook-checkout-saga.md`.
      A declined card is HTTP **200**, so compensation hangs off a **Choice** on
      `paymentStatus`, not off `Catch`; `Catch` handles only transport failures. Five
      distinct failure states, because after capture the compensating action is a refund,
      not a seat release (2026-08-27)
- [x] SQS queue decoupling payment-confirmed → ticket issuance — `ticket-issuance-queue`,
      Standard (issuance is idempotent on `orderId`). DLQ deferred to slice 5 with the
      consumer (2026-08-27)
- [x] Verify both paths: happy path and payment-failure → seats released
      — **happy:** `HoldSeat→CreateOrder→ChargePayment→PaymentCaptured→ConfirmPurchase→
      EnqueueTicketIssuance→NotifyPurchaseConfirmed→Succeeded`, seat **SOLD** with TTL
      cleared, issuance message on the queue, order PAID/CAPTURED.
      **compensation:** `…→PaymentCaptured→ReleaseSeat→PaymentFailed`, seat back to
      **AVAILABLE** with `holdId` cleared, **no** message enqueued, order FAILED/DECLINED.
      `ChargePayment` shows *succeeded* in both — proving the Choice routed the failure,
      not a Catch (2026-08-27)
- **Teardown:** stop/delete RDS (keep final snapshot), delete ECS service
- **Learn:** Step Functions, saga compensation, Secrets Manager, SQS

### 5. Ticket Issuance + Notification — $0 standing
- [x] Code: issuance service consumes SQS, persists tickets, QR-generation Lambda → S3
      — the consumer's idempotency is a **unique constraint**, not a check: SQS Standard is
      at-least-once and the saga retries its enqueue, so duplicate delivery is ordinary
      operation. `uq_tickets_order_seat` is what makes it survive. Tickets live in a separate
      `ticketing` database on the same RDS instance (separate bounded context, no FK across
      the two). The pump deletes a message only after its handler returns, so a failure is
      redelivered and eventually redriven — which is also the repair path for the
      non-atomic insert-then-enqueue. QR generation is idempotent *by construction*: a
      deterministic S3 key plus a pure renderer, so a redelivery rewrites identical bytes and
      needs no guard. **20 concurrent deliveries of one purchase → 1 ticket, 19 duplicates,
      all resolving to the same ticket id; 4 queued copies → 1 ticket, queue drained**
      (Testcontainers Postgres + LocalStack, 2026-08-29)
- [ ] AWS: `ticketing` database, DLQs on both queues, S3 bucket (SSE-KMS with the AWS-managed
      key, 7-day lifecycle), `qr-generation` Lambda with `ReportBatchItemFailures`, two ECS
      services + task roles — see `runbook-ticket-issuance.md` and `runbook-qr-generation.md`
- [x] SNS topic fan-out for purchase confirmation; notification service (email via SNS/SES sandbox)
      — the topic gains a second subscriber (an SQS queue) beside the email subscription it
      has had since slice 4, which is the fan-out that justifies SNS being here at all. The
      SQS subscription needs **raw message delivery ON**, or every field arrives null and the
      service reports success. `NotifyPurchaseConfirmed` now publishes JSON via JSONata
      `$string()` instead of prose; a contract test pins those field names to the record.
      A duplicate delivery sends a duplicate email — SES has no idempotency key, and that is
      recorded as a conscious omission rather than hidden behind an in-memory guard
      (2026-08-29)
- [ ] AWS: notifications queue + DLQ, SNS subscription with raw delivery, SES sandbox
      identities — see `runbook-notifications.md`
- [ ] Verify full purchase flow end-to-end
- **Learn:** SQS consumers, SNS fan-out, S3 presigned URLs

### 6. Edge & auth — ALB/WAF session-only (~$0.03/hr combined)
- [ ] ALB in front of the REST services; API Gateway (REST) → ALB
- [ ] API Gateway (WebSocket) + connect/disconnect Lambdas → live queue position
- [ ] Cognito user pool + guest checkout flow
- [ ] WAF on the purchase endpoint: rate limit + bot control (anti-scalping)
- [ ] Optional: CloudFront + S3 static frontend; skip Route 53/ACM custom domain unless I want the vanity URL
- **Learn:** APIGW ↔ ALB integration, WebSocket APIs, Cognito, WAF rules

### 7. Observability + the proof — $0–few $ per load-test session
- [ ] CloudWatch dashboard: queue depth, seats remaining, hold contention, conversion
- [ ] X-Ray tracing across APIGW → ECS → Step Functions → Lambda
- [ ] Load test (k6/Gatling): N users racing the same seats through the full stack;
      capture "0 double-sold seats" numbers + latency percentiles
- **Learn:** custom metrics, X-Ray, load testing — this produces the README's money chart
.
### 8. Wrap-up
- [ ] README: architecture diagram, "why each service exists", concurrency deep-dive, load-test results, cost notes
- [ ] Full teardown sweep (tag-based search for stragglers)
- [ ] Phase 1 retro notes → input for Terraform (Phase 2)

## Explicitly Excluded from Phase 1 (cost or scope)

- NAT Gateway (public subnets instead — revisit in Phase 2)
- Multi-AZ / HA anything
- CodeDeploy blue/green (optional extra, Phase 2+)
- Route 53 custom domain + ACM (optional vanity, not learning-critical)
- CI/CD (GitHub Actions + OIDC lands with Phase 2's Terraform)

## Standing Cost While Idle (everything torn down)

DynamoDB table + ECR image + Lambda/Step Functions/SQS/SNS definitions + CloudWatch ≈ **$0/mo**. 
The only recurring risk is a forgotten session-only resource - hence the teardown checklists and tag sweep.