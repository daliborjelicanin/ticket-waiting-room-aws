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

**Phase 1 is active.** The step-by-step development + infrastructure plan (low-cost,
slice-based, teardown-after-every-session) lives in `docs/phase1-plan.md` — consult it
for what to build next and keep its checkboxes up to date as slices complete.

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

- `common` — plain `java-library`, shared event/message DTOs (SQS/SNS/EventBridge
  contracts), exception types, and cross-deployable data contracts such as
  `SeatTableSchema` (the DynamoDB attribute names shared by every writer of the
  seat table). No dependency on any service module; services depend on it, never
  the reverse.
- `waiting-room-service`, `seat-inventory-service`, `checkout-payment-service`,
  `ticket-issuance-service`, `notification-service` — one Spring Boot app per bounded
  context, each an ECS Fargate task per CLAUDE.md's Compute section.
- `hold-cleanup-lambda` — plain `java-library` (no Spring: a DI container in a Lambda
  is cold-start tax). Packaged as a container image on `eclipse-temurin` with the AWS
  runtime interface client as the entrypoint, because no managed Lambda runtime offers
  JDK 26. `./gradlew :hold-cleanup-lambda:build` stages the deployment package into
  `build/lambda/lib` for the Docker build. See `docs/runbook-hold-cleanup.md`.

### Build Commands

- `./gradlew build` — build and test all modules
- `./gradlew test` — run tests only
- `./gradlew :waiting-room-service:bootRun` — run a single service (swap module name)
- `./gradlew projects` — list all modules
- `./gradlew :<module>:dependencies --configuration compileClasspath` — inspect a
  module's dependency tree (useful for checking `common` stays dependency-free)

Not yet added (deliberately deferred until the relevant phase/work needs them):
further Lambda modules (QR generation, WebSocket connect/disconnect), Jib/
`bootBuildImage` config, Terraform, CI workflow files. Docker packaging is
hand-written per module — see `seat-inventory-service/Dockerfile` (Fargate service)
and `hold-cleanup-lambda/Dockerfile` (Lambda container image).

## AWS Services by Category

**Compute**
- ECS Fargate — core services: Waiting Room, Seat Inventory, Checkout/Payment, Ticket Issuance, Notification
- Lambda — WebSocket connect/disconnect handlers, scheduled seat-hold expiration cleanup, QR code generation on ticket issuance
- ECR — image registry for the Fargate services

**Networking / Edge**
- API Gateway (REST) — catalog/checkout endpoints
- API Gateway (WebSocket) — real-time queue position and seat-map updates
- ALB — internal routing for REST services behind API Gateway
- CloudFront — static frontend + event banner images
- Route 53 — custom domain
- ACM — TLS certificates
- WAF — rate limiting and bot protection on the purchase endpoint (anti-scalping)
- VPC — public/private subnets, NAT Gateway, backend isolation

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
- Cognito — auth, including guest checkout
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
  the API layer.
- **Saga pattern via Step Functions** for checkout (hold seats → charge payment →
  issue tickets → notify), with explicit compensation (seat release) on payment
  failure — checkout must never leave seats held-but-unpaid indefinitely or silently
  drop a compensating action.
- **Postgres/RDS only for data that needs real ACID transactions** (orders, payments,
  users) — do not migrate this data to DynamoDB for consistency's sake; the split
  between DynamoDB (high-contention inventory) and Postgres (transactional records)
  is deliberate.
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