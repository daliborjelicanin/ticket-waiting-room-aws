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
- [ ] ElastiCache `cache.t4g.micro`, single node, same VPC; second Fargate service
- [ ] Verify FIFO admission under concurrent joins
- **Teardown:** delete ElastiCache cluster + ECS service every session
- **Learn:** ElastiCache networking/SGs, sorted-set queue pattern

### 4. Checkout saga: Step Functions + RDS + SQS — RDS is session-only (~$0.017/hr)
- [ ] Code: checkout-payment service (orders/payments in Postgres, mock payment
      gateway with configurable failure), Flyway migrations
- [ ] RDS Postgres `db.t4g.micro` single-AZ; Secrets Manager for credentials
- [ ] Step Functions state machine: hold seats → charge → issue tickets → notify,
      with compensation (release holds) on payment failure
- [ ] SQS queue decoupling payment-confirmed → ticket issuance
- [ ] Verify both paths: happy path and payment-failure → seats released
- **Teardown:** stop/delete RDS (keep final snapshot), delete ECS service
- **Learn:** Step Functions, saga compensation, Secrets Manager, SQS

### 5. Ticket Issuance + Notification — $0 standing
- [ ] Code: issuance service consumes SQS, persists tickets, QR-generation Lambda → S3
- [ ] SNS topic fan-out for purchase confirmation; notification service (email via SNS/SES sandbox)
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