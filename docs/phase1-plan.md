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
  SQS, SNS, EventBridge, Step Functions, Lambda, S3, ECR (~1.1 GB, ≈$0.06/mo past the
  500 MB free tier — the only standing charge), Cognito, CloudWatch
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
- [x] AWS: `ticketing` database, DLQs on both queues, S3 bucket (SSE-KMS with the AWS-managed
      key, 7-day lifecycle), `qr-generation` Lambda with `ReportBatchItemFailures`, two ECS
      services + task roles — see `runbook-ticket-issuance.md` and `runbook-qr-generation.md`
      — built, then **rebuilt from a cold account on 2026-09-05**, which is what proved the
      teardown cycle rather than assuming it. Everything in the free tier survived; what is
      minted fresh every cycle is the `rds!db-…` secret ARN, the EventBridge connection UUID,
      the task public IPs and the security-group rule ids. The RDS *endpoint hostname* is
      stable, which is the trap: `DB_URL` still looks right while every `valueFrom` beside it
      is stale. Two queue visibility timeouts were corrected against the runbooks —
      `qr-generation-queue` 60s → **360s** (six times the function timeout, per AWS's event
      source mapping guidance) and `purchase-confirmed-notifications` 30s → **120s** (the SES
      sandbox sends 1/sec, so a batch of 10 needs ≥10s before any retry) (2026-09-05)
- [x] SNS topic fan-out for purchase confirmation; notification service (email via SNS/SES sandbox)
      — the topic gains a second subscriber (an SQS queue) beside the email subscription it
      has had since slice 4, which is the fan-out that justifies SNS being here at all. The
      SQS subscription needs **raw message delivery ON**, or every field arrives null and the
      service reports success. `NotifyPurchaseConfirmed` now publishes JSON via JSONata
      `$string()` instead of prose; a contract test pins those field names to the record.
      A duplicate delivery sends a duplicate email — SES has no idempotency key, and that is
      recorded as a conscious omission rather than hidden behind an in-memory guard
      (2026-08-29)
- [x] AWS: notifications queue + DLQ, SNS subscription with raw delivery, SES sandbox
      identities — see `runbook-notifications.md`
      — fan-out confirmed: both subscribers fired, `AWS/SES` reported `Send: 1` / `Delivery: 1`,
      and raw delivery was proven by every field of `PurchaseConfirmedEvent` arriving populated.
      **Only one mail reaches the inbox, and it is not a bug in this service:** SES cannot pass
      DMARC for a `gmail.com` `From` address (SPF fails, DKIM signs as `amazonses.com` and does
      not align), so Gmail accepts it and files it as spam. Verifying an *address* gates SES
      acceptance; inbox placement needs a *domain* identity with DKIM published in Route 53.
      Two different mechanisms, and the sandbox only ever exercises the first (2026-09-05)
- [x] Verify full purchase flow end-to-end
      — one saga execution `SUCCEEDED` first attempt through the full graph, with every landing
      point checked: seat `SOLD` with `holdExpiresAt` removed, order `PAID` / payment `CAPTURED`,
      one ticket row, queue drained, QR object written, all three DLQs empty. Then the claim
      that matters, against the real deployment rather than LocalStack: **21 duplicate
      deliveries — 20 of them fired as two concurrent batches — produced exactly one ticket**,
      with `issuedAt` unchanged and both consumer threads visibly racing on `SQLState 23505`
      in the same second. QR cold start **1177 ms** against slice 2's 3411 ms.
      Two defects found, neither a misconfiguration: **(1)** every QR encodes
      `GET /tickets/{ticketId}`, which returns 404 — `TicketController` never implemented it,
      so "the QR scans" is unproven and needs its own slice (it is an authorisation question,
      not a rendering one); **(2)** the SES deliverability issue above. See the `Results`
      sections of `runbook-ticket-issuance.md`, `runbook-qr-generation.md` and
      `runbook-notifications.md` (2026-09-05)
- **Learn:** SQS consumers, SNS fan-out, S3 presigned URLs

### 6. Edge & auth — ALB/WAF session-only (ALB ~$0.037/hr, WAF billed monthly)
- [x] ALB in front of the REST services; API Gateway (REST) → ALB — see
      `runbook-alb-apigateway.md`. REST APIs can only VPC-Link to an **NLB**, so the ALB is
      internet-facing and every listener rule requires a secret header that API Gateway
      injects; the default action is a hard 403. Path rules are priority-ordered because
      `/orders/*/tickets` is a subset of `/orders/*`. **30 concurrent holds on one seat
      through APIGW → ALB → Fargate → DynamoDB → 1×201, 29×409**, one holdId in the table
      — identical to slice 1 against a raw task IP, so the edge changed the address and not
      the guarantee (2026-09-05)
- [x] Code: API Gateway (WebSocket) → live queue position, **`$connect`/`$disconnect` as
      DynamoDB service integrations rather than Lambdas**. Both routes do one unconditional
      write against a key API Gateway already holds, so a function there buys only a cold
      start and a concurrency ceiling — and container images cannot use SnapStart, so a
      door-open throttles `$connect` and drops every client back to polling, which is the
      stampede this slice exists to prevent. Built with the handlers first and then removed;
      the note in `runbook-websocket-queue.md` records why. Removing them also dissolved the
      hand-formatted frame, which was cheap only because a per-connection Lambda validated
      its inputs — the notifier is invoked once per *batch*, so it just serialises.
      The socket carries **admissions, not positions**: `$connect` cannot push to its own
      connection (it does not exist until the route returns 200), so `GET /queue/{token}`
      stays the source of truth and the socket only removes the need to poll it. The push is
      decoupled through `queue-admissions` because `ZPOPMIN` does not roll back — an inline
      push whose failure reached the caller would make the retry pop a *second* batch of
      people, so a publish failure is logged and swallowed. Connections are found by a
      **KEYS_ONLY** GSI, whose projected base key *is* the `connectionId`. **50 tabs on one
      token → 50 frames from one admission; a 410 prunes its row without failing the record;
      500 tokens across 50 concurrent SQS batches arrive exactly once; notifier INIT 538 ms,
      37 jars — unchanged by serialising the frame, since `json-utils` was already on the
      classpath** (LocalStack + RIE, 2026-09-06)
- [x] AWS (connection half): `waiting-room-connections` + `by-token` GSI + TTL, the
      `ticket-queue-socket-apigw-role`, and WebSocket API `ticket-queue-socket` with both
      DynamoDB service integrations. **The request template was right first time; the response
      wiring took four attempts and 500s the handshake while committing the write anyway** —
      an integration response must be keyed `/200/` because an AWS service integration selects
      by `${integration.response.statuscode}`, so a `$default` key is created successfully and
      is silently unreachable. **Verified against real AWS: TTL exactly `connectedAt + 86400`
      in epoch seconds; `eventId=evt", "injected":"yes` stored as data with no attribute
      injected; 2 tabs → 2 rows → GSI query returns both; `$disconnect` prunes to 0.** Under
      $0.01, $0 standing (2026-09-06)
- [x] AWS (notifier half): ECR image, `ticket-queue-notifier`, `queue-admissions` + DLQ and
      its event source mapping, and `ticket-waiting-room-task-role` for `waiting-room-service`
      — which until now had none, because until now it made no AWS API calls. **A synthetic
      admission on the queue reaches an open browser socket as
      `{"type":"ADMITTED",...,"admissionExpiresAt":1788698504}`; two tabs on one token both get
      it from a single message; an orphaned row 410s and is pruned with `failures=0` and an
      empty DLQ.** Cold start is **2849 ms on real Lambda against 538 ms on the local RIE — a
      5.3× spread, not the ~2× this project had been assuming**, and 147 MB used of 1024 MB,
      which is the memory-buys-CPU argument stated in numbers. $0 standing (2026-09-06)
- [x] AWS (remaining): `waiting-room-service:2` carrying `ticket-waiting-room-task-role` and
      `ADMISSION_QUEUE_URL`, on a rebuilt image — **`6a55391` predated the publishing code
      entirely, so the old revision would have run green and silently published nothing**.
      **Whole path proven with nothing synthetic: join over REST → socket → `POST .../admit` →
      `{"type":"ADMITTED","eventId":"evt-live-1",...}` at the browser; and 3 tokens admitted in
      one call produced 3 frames, each routed to its own socket** — the `SendMessageBatch` path
      against real SQS. No publish `WARN`, DLQ 0. ElastiCache + task torn down after
      (2026-09-06)
- [x] Cognito user pool + identity-bound queue — every service an OAuth2 resource server
      (`security-starter`), ops scope for the saga and operators, one live queue place per
      `sub` enforced atomically in `join.lua`. Guest checkout dropped: a per-account ticket
      limit is unenforceable without an account — see `runbook-cognito-auth.md`.
      **On AWS: four purchases through the new `POST /events/{id}/purchase` (API Gateway →
      Step Functions, no Lambda) all `SUCCEEDED`, with `customerRef` taken from the ID
      token's verified `sub` and the body's forged value ignored; a retry → 400
      `ExecutionAlreadyExists`; 50 concurrent joins as one identity → 1 token; a customer's
      token on seed / admit / create-order → 403 at the filter with zero lines in the
      service log; another customer's order and QR → 404 identical to an unknown id; an ID
      token at a service → 401 on `token_use`; the saga authenticated with an M2M token via
      an EventBridge OAuth connection.** First token per task **12.8 s** on 0.25 vCPU vs
      629 ms locally, steady state 0.1 s through the whole edge — a warm-up on
      `ApplicationReadyEvent` is the recorded follow-up. Two pre-existing defects found:
      `qr-generation`'s event source mapping left disabled by a teardown, and the issuance
      task role lacking `s3:ListBucket` (HeadObject on a missing key is 403 → the QR
      endpoint 500'd). Both fixed. Stack up 14:28–15:18, ≈ $0.15 (2026-09-12)
- [x] WAF on the purchase endpoint: rate limit + bot control (anti-scalping) — see
      `runbook-waf.md`. Web ACL on the `ticket-edge` `dev` stage, both rules scoped to
      `POST /events/{id}/purchase` so the saga's own `http:invoke` re-entry on `/{proxy+}`
      is never inspected; WAF sits *before* the Cognito authorizer, so the whole proof ran
      with **nothing else up**. **A flood of unauthenticated posts flipped 401 → 429 with
      WAF's own body (~30 s to engage, released ~50 s after the last burst); `curl` on the
      purchase path → 403 as `CategoryHttpLibrary` while the same request with a browser UA
      → 401 from the authorizer; other paths untouched.** One trap: AWS CLI v2 base64-decodes
      `SearchString`, so the first build matched nothing and read back as if it did.
      Session-only — ~$17/mo if forgotten (2026-09-13)
- [ ] Optional: CloudFront + S3 static frontend; skip Route 53/ACM custom domain unless I want the vanity URL
- **Learn:** APIGW ↔ ALB integration, WebSocket APIs, Cognito, WAF rules

### 7. Observability + the proof — $0–few $ per load-test session
- [x] Code: instrument first, no AWS session — `observability-starter` (the fourth starter-
      shaped module: health endpoint, OTLP traces + metrics, X-Ray-shaped trace ids, the
      X-Ray header beside W3C, a span per AWS SDK call, consumer spans continued across SQS
      from `AWSTraceHeader`), one `XxxMetrics` per service with only the meters the dashboard
      will ask for (Boot's JVM/Tomcat series off — every series is a billable metric), and
      the JWT warm-up on `ApplicationReadyEvent` recorded as slice 6's follow-up. **OTLP
      into a collector sidecar rather than CloudWatch/X-Ray SDKs**: Boot 4 dropped the
      CloudWatch registry auto-config, and one vendor-neutral pipeline is what EKS will
      want anyway. **Proven against a real OTel collector in Docker: a request wearing API
      Gateway's `X-Amzn-Trace-Id` is exported as that trace with that parent under this
      service's name over the JDK HTTP sender (OkHttp + Kotlin excluded: 134 → 130 jars);
      the one enabled metric arrives with its tag and an exemplar, no JVM series does.**
      The concurrency ITs now also assert the metrics' view of each race: 50 charges →
      `captured` 1 + `in_flight`/`not_payable` 49; 20 deliveries → `issued` 1,
      `duplicate` 19. 147 tests green (2026-09-13)
- [x] AWS: ADOT collector sidecar in every task definition — see `runbook-observability.md`.
      Own config through SSM (`docs/otel-collector-config.yaml`: the image's default was
      checked and routes right, but with roll-ups on and a generic namespace),
      `TELEMETRY_EXPORT_ENABLED=true`, one `ticket-telemetry-export` policy on five task
      roles (`xray:Put*` + `logs:*` on the EMF group — metrics go as EMF log events, not
      `PutMetricData`), a first task role for checkout, `ssm:GetParameters` on the execution
      role, task memory 1 GB. **Done except for running it (2026-09-14):** images at
      `5e3c555` in ECR; parameter, log groups, policy, checkout role, execution-role grant;
      five revisions registered (`seat-inventory:5`, `waiting-room:4`, `checkout:7`,
      `issuance:5`, `notification:3`); all four target groups on `GET /actuator/health`
      matcher `200`; X-Ray on the `ticket-edge` stage, `checkout-saga` and all three
      Lambdas. **Run 2026-09-14/15 (`runbook-observability.md` Results):** warm-up pays the
      JWKS cost at startup (2.7–13.6 s per service; first authenticated request 3.7–4.3 s
      against slice 6's 12.8 s); every counter in the `TicketWaitingRoom` namespace matched
      the traffic; **30 holds through the edge → 1×201, 29×409, the loss a faulted
      `DynamoDB UpdateItem` subsegment; one purchase = one X-Ray trace of 18 segments, so
      `http:invoke` does forward the header**, and the SQS consumer spans join it. Two
      defects fixed in-session (task role lacked `dynamodb:Query`; EMF swallowed the first
      delta), Step Functions' own segments absent (open), health-check and scheduled-task
      traces to silence in code (2026-09-15)
- [x] CloudWatch dashboard: queue depth, seats remaining, hold contention, conversion
      — `ticket-waiting-room`, 12 widgets, source of truth `docs/dashboard-ticket-waiting-room.json`
      (saved body verified byte-identical). Built with nothing running, against the
      2026-09-15 data; counters read as per-minute deltas, gauges as max/min, the hold
      contention widget carries **DynamoDB's own `ConditionalCheckFailedRequests` beside the
      service's `unavailable` — 29 = 29**, conversion is metric math over captured/admissions.
      Latency is avg + max, not p99: the timer exports Count/Sum, and a percentile needs the
      histogram export queued for the load-test code change. First dashboard in the account,
      $0 (2026-09-15)
- [x] X-Ray tracing across APIGW → ECS → Step Functions → Lambda — one purchase is one
      trace: `ticket-edge/dev` → the saga's four `http:invoke` calls back through the stage
      into seat-inventory and checkout (security chain + `DynamoDB UpdateItem` subsegments)
      → SQS → the issuance consumer span → SNS/SQS → the notification consumer span; the QR
      Lambda is a linked trace of its own, as Lambda does for SQS triggers. **Open:** Step
      Functions itself emitted no `checkout-saga` segment despite tracing on and `xray:*`
      on its role — the header still propagates through it (2026-09-15)
- [x] Load test (k6/Gatling): N users racing the same seats through the full stack;
      capture "0 double-sold seats" numbers + latency percentiles — see `runbook-load-test.md`.
      **Run 2026-09-17, stack up 37 min, ≈ $0.09.** Scenario A, **200 VUs on 10 seats
      through APIGW → ALB → JWT → DynamoDB → 10 won / 190 lost / 0 unexpected**, twice;
      hot run winners p50 0.58 s / p99 1.20 s, losers p50 3.0 s / p99 5.0 s (queueing on one
      0.25 vCPU task, not the write). Scenario B, **50 customers with their own Cognito
      identities on 10 seats through the whole saga → 10 SUCCEEDED / 40 FAILED
      SeatUnavailable**, purchase accepted p50 125 ms, winner end-to-end 202 → PAID
      **p50 3.0 s / p95 4.1 s / p99 4.25 s**. In the stores: **seats SOLD = orders PAID =
      payments CAPTURED = tickets = 10 = 10 = 10 = 10, 0 double-sold seats**, every
      "must be 0" 0; DynamoDB's own `ConditionalCheckFailedRequests` (662) agrees with the
      service's `unavailable` (660 + the two warm-up losses). Three script bugs found and
      fixed in-session (init-context `Date.now()`, cross-scenario `__VU`, a k6 API that does
      not exist); server-side p99 still open (EMF needs `detailed_metrics`). Code half
      (2026-09-15): timers export as ten-bucket histograms so CloudWatch
      has real p99s; health probes and scheduled tasks are no longer observed (they were
      two-thirds of the traces); `load-test/` holds scenario A (seat race, S×201 + rest 409)
      and scenario B (M customers with their own identities race `/purchase` for S seats —
      S sagas succeed, the rest fail at `HoldSeat`), the user/token scripts and the
      invariant check. Both scenarios pass `k6 inspect` in the official image; the session
      itself is next (images to rebuild first — the histogram/predicate change is code)
- **Learn:** custom metrics, X-Ray, load testing — this produces the README's money chart
.
### 8. Wrap-up
- [x] README: architecture diagram, "why each service exists", concurrency deep-dive, load-test results, cost notes
      — the AWS-icon poster (`docs/diagrams/architecture.png`, generated from code) opens
      it; the load-test tables and the invariant block are the headline; every mechanism
      has its local and its AWS proof side by side; cost is stated as standing / per hour /
      per session / phase total; the deliberate simplifications are named with what Phase
      2 does about them (2026-09-17)
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