# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Goal

A Ticketmaster-style waiting room / high-demand ticket sale platform, built as an AWS
architecture portfolio piece. The core purpose is to demonstrate **real concurrency
handling** — atomic seat locking, distributed queueing, saga-based checkout — rather
than basic CRUD. Proving concurrency correctness under load is the main thing this
project needs to demonstrate; everything else is secondary.

## Architecture Phases

1. **Manual ECS + Fargate** — broad AWS service coverage, built by hand for learning depth
2. **Terraform** — same architecture rebuilt as infrastructure-as-code
3. **EKS** — migrate/re-platform onto Kubernetes

Check which phase is currently active before assuming a build tool, deployment target,
or infra format — the same logical architecture is intentionally rebuilt across phases.

**Phase 1 is complete; Phase 2 starts from `docs/phase1-retro.md`** — the stack split,
the decisions to make before the first module, and the defaults to pin. Phase 1's
step-by-step plan (low-cost, slice-based, teardown-after-every-session) lives in
`docs/phase1-plan.md`, with what each session found; keep its checkboxes up to date.

**Diagrams.** `docs/architecture.md` holds the system-level picture — topology, the
end-to-end purchase sequence, the saga state machine, the seat lifecycle, and the async
edges — and each module has an `ARCHITECTURE.md` beside its code with that module's
internals. They are Mermaid, so they render in IntelliJ and on GitHub and diff as text.
Keep them true to the code: a diagram that describes the plan rather than what is built
is worse than none, so update the module's `ARCHITECTURE.md` in the same change that
alters its behaviour, and mark not-yet-built boxes as such. One picture is not Mermaid:
`docs/diagrams/architecture.png`, the system topology with official AWS icons for the
README, generated from `docs/diagrams/architecture.py` (the `diagrams` library, run from
the `gtramontina/diagrams` image — nothing to install). It mirrors `docs/architecture.md`
§1 and is regenerated whenever §1 changes; the Mermaid stays the source of truth because
it diffs as text.

## Tech Stack

- Language: Java (JDK 26 / `openjdk-26`)
- Framework: Spring Boot microservices (Spring Boot 4.1.0 / Spring Framework 7)
- IDE config: `.idea/` is gitignored — IntelliJ regenerates it on Gradle import
- Build tool: Gradle multi-module monorepo (Kotlin DSL), wrapper pinned to Gradle 9.6.1
  (required: JDK 26 toolchain compilation needs Gradle >= 9.4.0)
- Java toolchain auto-provisioned per build via the `foojay-resolver-convention` plugin
  in `settings.gradle.kts` — a local JDK is only needed to launch Gradle itself
- Dependency versions centralized in `gradle/libs.versions.toml` (version catalog)
- ~25 AWS services total — every service is chosen because it solves an actual
  problem in the system, never added purely for coverage

### Modules

- `common` — `java-library`, shared event/message DTOs (SQS/SNS/EventBridge
  contracts), exception types, and cross-deployable data contracts such as
  `SeatTableSchema` (the DynamoDB attribute names shared by every writer of the
  seat table) and `TicketQrObjects` (the S3 key shared by the writer and reader of
  a ticket QR). It also owns `SqsMessagePump`, the long-poll consumer loop, because
  two deployables consume queues and both must have identical at-least-once
  semantics. `aws-sdk-sqs` is therefore a **`compileOnly`** dependency here and is
  declared again by the services that use the pump — exporting it put the SDK's
  Apache 5 and Netty HTTP stacks back into both Lambda images, which had spent
  slice 2 removing them. Services depend on `common`, never the reverse.
- `security-starter` — `java-library`, a Spring Boot starter in the Boot sense: one
  auto-configuration that makes a service an OAuth2 resource server for the Cognito user
  pool (JWT verified in the service itself — signature, issuer, expiry, and `token_use =
  access` so an ID token is never accepted as an API credential). A service adds the
  dependency and declares only its own path rules as `EndpointAuthorization` beans;
  everything not declared needs a valid token and nothing more. Kept out of `common`
  because the Lambdas share `common` and must never see Spring Security. Registers itself
  for `@WebMvcTest` slices the way Boot's own starters do.
- `observability-starter` — `java-library`, the observability counterpart of
  `security-starter`: actuator's health endpoint (the one anonymous path, for the ALB),
  Boot's OpenTelemetry starter for OTLP traces and metrics, and what X-Ray needs that Boot
  does not know — X-Ray-shaped trace ids (a local `IdGenerator`; the contrib artifact
  drags OkHttp, Jackson 2 and Caffeine along), the `X-Amzn-Trace-Id` propagator beside
  W3C, an AWS SDK v2 tracing interceptor offered as one `ClientOverrideConfiguration`
  bean, and `SqsMessageTracing` for the consumer side of a queue. Telemetry leaves over
  **OTLP to a collector sidecar**, never through a CloudWatch or X-Ray SDK in the service:
  Boot 4 dropped its CloudWatch registry auto-config, and one vendor-neutral pipeline is
  what the EKS phase wants. `TELEMETRY_EXPORT_ENABLED` switches both exporters; off is the
  local default. Each service keeps its own `XxxMetrics` with only the meters the dashboard
  asks for — Boot's JVM/Tomcat series are disabled because every series is a billable
  CloudWatch metric. Kept out of `common` for the same reason `security-starter` is.
- `waiting-room-service`, `seat-inventory-service`, `checkout-payment-service`,
  `ticket-issuance-service`, `notification-service` — one Spring Boot app per bounded
  context, each an ECS Fargate task per CLAUDE.md's Compute section.
  `waiting-room-service` keeps its queue logic in Lua scripts under
  `src/main/resources/redis` (`join`/`position`/`admit`) rather than in Java: each
  operation must be one atomic round trip, so the reasoning lives next to the commands.
  `checkout-payment-service` owns the Postgres schema in Flyway migrations under
  `src/main/resources/db/migration`, with `ddl-auto: validate` so entity drift fails at
  startup instead of silently altering a live table. `ticket-issuance-service` does the
  same for a *separate* `ticketing` database on the same RDS instance — separate bounded
  context, separate Flyway history, and deliberately no foreign key across the two.
  `notification-service` sends through a `NotificationSender` interface with SES and
  logging implementations, the same split as `PaymentGateway`/`MockPaymentGateway`;
  `waiting-room-service` uses the same split again for `AdmissionPublisher` (SQS vs
  logging), which is what lets it run with no AWS configured at all.
- `hold-cleanup-lambda`, `qr-generation-lambda`, `queue-socket-lambda` — plain `java`
  modules (no Spring: a DI container in a Lambda is cold-start tax). Each is packaged as a
  container image on `eclipse-temurin` with the AWS runtime interface client as the
  entrypoint, because no managed Lambda runtime offers JDK 26, and each excludes the AWS
  SDK's Apache 5 and Netty HTTP stacks in favour of `url-connection-client` — the single
  biggest lever on Lambda's separate 10s INIT budget. `./gradlew :<module>:build` stages
  the deployment package into `build/lambda/lib` for the Docker build. Neither
  `qr-generation-lambda` nor `queue-socket-lambda` *parses* JSON (a bare ticket id in one, a
  bare queue token plus SQS message attributes in the other) for the same reason.
  `queue-socket-lambda` holds only the SQS-driven admission notifier: `$connect` and
  `$disconnect` are **API Gateway service integrations straight onto DynamoDB**, because each
  is one unconditional write against a key API Gateway already holds, and a function there
  buys nothing but a cold start and a concurrency ceiling on the one path a door-open hits
  hardest. Put a Lambda in front of a single-item write only when something has to *decide*.
  The notifier is invoked once per batch rather than once per socket, which is what lets it
  afford the SDK's own `JsonWriter` for its outbound frame instead of formatting a string by
  hand. See `docs/runbook-hold-cleanup.md`, `docs/runbook-qr-generation.md` and
  `docs/runbook-websocket-queue.md`.

### Load test

`load-test/` holds the slice-7 proof: two k6 scenarios (`k6/seat-race.js`,
`k6/purchase-race.js`) that race real buyers for the same seats through the edge, and the
scripts that create Cognito test users, mint their tokens, and check the data stores
afterwards (`scripts/invariants.sh` — the "0 double-sold seats" line). k6 runs from the
`grafana/k6` image; see `load-test/README.md`. Tokens and results are gitignored.

### Build Commands

- `./gradlew build` — build and test all modules
- `./gradlew test` — run tests only
- `./gradlew :waiting-room-service:bootRun` — run a single service (swap module name)
- `./gradlew projects` — list all modules
- `./scripts/teardown-sweep.sh` (`ALL_REGIONS=1` for every region) — read-only account
  sweep after a session; exits 1 if anything that bills by the hour is still up, and lists
  project resources the tag search cannot see. See `docs/runbook-teardown-sweep.md`
- `./gradlew :<module>:dependencies --configuration compileClasspath` — inspect a
  module's dependency tree (useful for checking a Lambda module's classpath has not
  quietly regrown the HTTP stacks its cold start cannot afford)

Not yet added (deliberately deferred until the relevant phase/work needs them):
Jib/`bootBuildImage` config, Terraform, CI workflow files. Docker packaging is
hand-written per module — see `seat-inventory-service/Dockerfile` (Fargate service) and
`hold-cleanup-lambda/Dockerfile` (Lambda container image).

## AWS Services by Category

**Compute**
- ECS Fargate — core services: Waiting Room, Seat Inventory, Checkout/Payment, Ticket Issuance, Notification
- Lambda — WebSocket admission notifier off SQS, scheduled seat-hold expiration cleanup, QR code generation on ticket issuance. `$connect`/`$disconnect` are deliberately *not* Lambdas — see the `queue-socket-lambda` module note.
- ECR — image registry for the Fargate services

**Networking / Edge**
- API Gateway (REST) — catalog/checkout endpoints
- API Gateway (WebSocket) — real-time queue position and seat-map updates
- ALB — internal routing for REST services behind API Gateway
- CloudFront — static frontend + event banner images. *Not built:* Phase 1 shipped no
  frontend, so CloudFront has not yet earned its place
- Route 53 — custom domain. *Not built; optional.* The one concrete reason to add it is a
  domain identity with DKIM, so SES confirmation mail stops landing in spam
- ACM — TLS certificates. *Not built:* arrives with the domain
- WAF — rate limiting and bot protection on the purchase endpoint (anti-scalping)
- VPC — public/private subnets, NAT Gateway, backend isolation. Phase 1 used the default
  VPC's public subnets with no NAT; Phase 2 starts here (`docs/phase1-retro.md` §6)

**Data**
- DynamoDB — seat inventory and holds: single-table design, conditional writes for atomic seat locking, TTL for auto-expiring holds. 
- **This is the centerpiece of the project** and proves concurrency understanding.
- ElastiCache (Redis) — waiting-room queue ordering (sorted sets) and/or secondary distributed lock
- RDS (Postgres) — orders, payments, users — requires real ACID transactions
- S3 — event images, generated ticket QR codes, receipts

**Messaging / Events**
- SQS — decouples payment confirmation from ticket issuance
- SNS — fan-out for purchase confirmation (email/SMS)
- EventBridge — scheduled expired-hold cleanup, order lifecycle events
- Step Functions — orchestrates the checkout saga: hold seats → charge payment → issue tickets → notify, with compensation (release seats) on payment failure

**Security**
- Cognito — user pool for buyers; a resource server + M2M app client for the saga and
  operators. Registration is required to join the queue: a per-account ticket limit is
  unenforceable without an account, and guest checkout hands a scalper unlimited identities
- IAM — distinct least-privilege role per service
- Secrets Manager — payment gateway keys, DB credentials
- KMS — encryption at rest for RDS/S3

**Observability**
- CloudWatch — logs, alarms, custom dashboard for queue depth, seats remaining, conversion rate
- X-Ray — tracing across the saga (Step Functions → Lambda → ECS)

**CI/CD**
- GitHub Actions + OIDC — no static AWS keys
- CodeDeploy (optional) — blue/green ECS deployments

## Key Design Decisions to Preserve

- **Concurrency is the point.** Every AWS service must earn its place by solving a
  concrete concurrency or reliability problem — do not add services just to inflate
  the count, and do not suggest simplifications that remove the concurrency mechanisms
  below in the name of "simpler CRUD."
- **Atomic seat locking via DynamoDB conditional writes**, not application-level locks
  or `SELECT ... FOR UPDATE` — seat holds must be race-free under concurrent requests
  for the same seat.
- **TTL-based hold expiration** in DynamoDB, backed by an EventBridge-scheduled cleanup
  Lambda — holds must self-expire without relying solely on client behavior.
- **Redis sorted sets for queue ordering** — the waiting room's fairness guarantee
  (first-in-first-out admission) depends on this, not on request arrival order at
  the API layer. The score must stay an `INCR` ticket number: scoring by wall clock
  ties for joins in the same millisecond, and a sorted set breaks ties by member name,
  which silently orders the queue by token UUID. Admission is `ZPOPMIN` from the head.
- **Saga pattern via Step Functions** for checkout (hold seats → charge payment →
  issue tickets → notify), with explicit compensation (seat release) on payment
  failure — checkout must never leave seats held-but-unpaid indefinitely or silently
  drop a compensating action. After capture the compensation is a full **refund**, taken
  only on a *definite* refusal of the confirm (a 404), never on a timeout. That 404 is
  trustworthy only because the seat confirm is idempotent on the winning hold
  (`ReturnValuesOnConditionCheckFailure = ALL_OLD`): do not make the confirm
  non-idempotent again, or a lost response turns into a refund of a sold seat. One live
  refund per payment is the partial unique index `ux_refunds_one_live_per_payment`, not a
  service-layer check.
- **Postgres/RDS only for data that needs real ACID transactions** (orders, payments,
  users) — do not migrate this data to DynamoDB for consistency's sake; the split
  between DynamoDB (high-contention inventory) and Postgres (transactional records)
  is deliberate. The invariants that justify it are declared as *constraints*, not
  application checks: a unique idempotency key on orders and on payments, and the partial
  unique index `ux_payments_one_live_per_order` permitting at most one PENDING-or-CAPTURED
  payment per order. Do not replace these with service-layer guards — they have to hold
  against every writer, including one that does not exist yet.
- **Never call a payment gateway inside a database transaction.** The attempt row is
  committed first, the gateway is called with nothing held, and the outcome is written in a
  second transaction. Holding a connection across a provider's latency turns their outage
  into connection-pool exhaustion here; writing only on success would leave a charge that
  moved money with no row to prove it.
- **Idempotency is checked before state.** A replayed charge must return its original
  answer even once the order is terminal — checking payability first turns every retry of
  a successful charge into an error, which is the exact case the key exists to handle.
- **Ticket issuance is idempotent on a database constraint**, not on a
  read-then-write check. SQS Standard is at-least-once and the saga retries its enqueue,
  so duplicate delivery is ordinary operation rather than an edge case; `UNIQUE
  (order_id, seat_id)` on `tickets` is what makes twenty concurrent deliveries produce
  one ticket. Do not replace it with a `findByOrderIdAndSeatId` guard.
- **A consumer deletes an SQS message only after its handler returns.** Deleting on
  receipt turns the queue into an at-most-once channel; a thrown handler leaves the
  message for the visibility timeout and, eventually, the DLQ. Every handler is therefore
  required to be idempotent.
- **QR generation is idempotent by construction, not by guard** — the S3 key is derived
  from the ticket id and the renderer is pure, so a redelivered message rewrites identical
  bytes. Do not add a conditional put or a dedupe table to "fix" a duplicate render.
- **WAF in front of the purchase endpoint** specifically for anti-scalping/bot
  protection — this is a functional requirement of the domain, not generic hardening.

## Coding Conventions

- Target JDK 26 language features where they improve clarity (records, pattern
  matching, virtual threads) — this is a from-scratch project, so there is no legacy
  Java style to match.
- One Spring Boot service per bounded context (Waiting Room, Seat Inventory,
  Checkout/Payment, Ticket Issuance, Notification) — do not collapse services back
  into a monolith for convenience.
- Favor explicit, testable concurrency logic (conditional writes, optimistic locking,
  idempotency keys) over incidental thread-safety — the whole point of this codebase
  is that this logic is visible and correct, not hidden behind a framework default.
- Use Lombok `@RequiredArgsConstructor` instead of a handwritten constructor
  whenever the constructor would only assign `final` fields (typical Spring
  constructor injection). Write an explicit constructor only when it must do
  more than field assignment. Do not use other Lombok features on records —
  records already provide accessors/equals/hashCode/toString.
- Always import classes and use the simple name in code — never inline
  fully-qualified names (e.g. `ResourceNotFoundException`, not
  `software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException`).
  The only exception is disambiguating two same-named classes used in one file.
- Record declaration format depends on where the record lives:
  - **Top-level records** (own file) use the multi-line format: one component per
    line (indented), closing paren on its own line, and an explicit body even when
    empty:
    ```java
    public record RecordExample(
            String component1,
            String component2
    ) {
    }
    ```
  - **Nested records** (declared inside another class — request/response DTOs in a
    controller, result types on a service) are written as a single line, with the empty body as `{ }`:
    ```java
    public record RecordExample(String component1, String component2, String component3) { }
    ```
    Nested records are usually small, and one line each keeps the enclosing class's
    real logic readable instead of pushing it apart with boilerplate. A nested record
    that needs a real body (compact constructor, extra methods) uses the multi-line
    form instead.
- No build system exists yet — when adding one, update this file's Tech Stack section
  with real build/test/lint commands rather than leaving them as placeholders.

### Java / Spring Boot Best Practices

Always follow current best practices and idioms for the stack (Java 26,
Spring Boot 4 / Spring Framework 7) — prefer the modern approach over legacy
patterns carried forward from older versions. Concretely:

- **Java**: records for DTOs/value objects, sealed interfaces for closed
  hierarchies, pattern matching (`switch`/`instanceof`) over casts, `Optional`
  only as a return type, immutability by default (`List.of`, unmodifiable
  collections), virtual threads for blocking I/O instead of reactive rewrites
  or custom thread pools.
- **Spring**: constructor injection only (no field `@Autowired`), configuration
  via `@ConfigurationProperties` records instead of scattered `@Value`,
  `RestClient` over `RestTemplate`/`WebClient` for synchronous HTTP, Spring's
  `ProblemDetail` (RFC 9457) for error responses, explicit transaction
  boundaries at the service layer, no business logic in controllers.
- **Testing**: JUnit 5 + AssertJ; Testcontainers for real DynamoDB-local/
  Redis/Postgres/LocalStack integration tests rather than mocking AWS SDK
  clients; slice tests (`@WebMvcTest`, `@DataJpaTest`) over full-context
  `@SpringBootTest` where a slice suffices; concurrency claims must be backed
  by tests that actually race threads, not single-threaded happy paths.
- **AWS SDK**: AWS SDK for Java v2 only, with the `DynamoDbEnhancedClient`
  where it helps, and SDK clients defined as Spring beans so they are
  injectable and fakeable in tests.
- When Spring Boot or a library deprecates an API, use the replacement — do
  not introduce deprecated APIs into new code.