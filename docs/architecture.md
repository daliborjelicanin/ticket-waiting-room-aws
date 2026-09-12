# Architecture Diagrams

The system-level picture. Every diagram here is drawn from the code as it actually
stands, not from the plan — where a box is planned rather than built it is marked and
named with the slice that will build it (`phase1-plan.md`).

Per-module diagrams, with the internals each service is actually responsible for:

| Module | Diagram | What it shows |
|---|---|---|
| `waiting-room-service` | [ARCHITECTURE.md](../waiting-room-service/ARCHITECTURE.md) | Redis keys, the three Lua scripts, the FIFO proof |
| `seat-inventory-service` | [ARCHITECTURE.md](../seat-inventory-service/ARCHITECTURE.md) | The DynamoDB item, every conditional write, the race |
| `checkout-payment-service` | [ARCHITECTURE.md](../checkout-payment-service/ARCHITECTURE.md) | Order/payment schema, transaction boundaries around the gateway |
| `ticket-issuance-service` | [ARCHITECTURE.md](../ticket-issuance-service/ARCHITECTURE.md) | SQS pump, duplicate-delivery path, QR handoff |
| `notification-service` | [ARCHITECTURE.md](../notification-service/ARCHITECTURE.md) | SNS fan-out, sender split, the duplicate-email tradeoff |
| `hold-cleanup-lambda` | [ARCHITECTURE.md](../hold-cleanup-lambda/ARCHITECTURE.md) | Scan → guarded reclaim, why TTL is not enough |
| `qr-generation-lambda` | [ARCHITECTURE.md](../qr-generation-lambda/ARCHITECTURE.md) | Bare-id message, deterministic key, partial batch failure |
| `queue-socket-lambda` | [ARCHITECTURE.md](../queue-socket-lambda/ARCHITECTURE.md) | The admission notifier, the GSI lookup, why the connect routes have no code |
| `security-starter` | [ARCHITECTURE.md](../security-starter/ARCHITECTURE.md) | The one filter chain every HTTP service runs; what a service declares, what it never has to |
| `common` | [ARCHITECTURE.md](../common/ARCHITECTURE.md) | Who shares which contract, and what breaks when one drifts |

---

## 1. System topology

Solid boxes exist and have run on AWS. Dashed boxes are built in code and runbook but have
not yet run there (Cognito, the purchase entry point — `runbook-cognito-auth.md`), or
belong to a later slice (WAF).

```mermaid
flowchart LR
    Browser["Browser / client"]
    COG["Cognito user pool<br/>ticket-users · scope ticketing/ops"]

    subgraph edge["Edge — slice 6"]
        WAF["WAF<br/>rate limit + bot control"]
        APIGW["API Gateway REST<br/>/{proxy+} → ALB, no authorizer<br/>POST /events/{id}/purchase → saga"]
        WSAPI["API Gateway WebSocket"]
        ALB["ALB<br/>X-Edge-Secret: WAF cannot be bypassed"]
    end

    subgraph fargate["ECS Fargate — one task per bounded context · each an OAuth2 resource server (security-starter)"]
        WR["waiting-room-service"]
        SI["seat-inventory-service"]
        CP["checkout-payment-service"]
        TI["ticket-issuance-service"]
        NS["notification-service"]
    end

    SFN["Step Functions<br/>checkout saga"]

    subgraph lambdas["Lambda — container images, no Spring"]
        HC["hold-cleanup-lambda"]
        QR["qr-generation-lambda"]
        WSN["queue-socket-lambda<br/>admission notifier"]
    end

    EB["EventBridge Scheduler"]

    subgraph data["Data"]
        REDIS[("ElastiCache Redis<br/>queue sorted sets")]
        DDB[("DynamoDB<br/>seat-inventory")]
        PG1[("RDS Postgres<br/>checkout db")]
        PG2[("RDS Postgres<br/>ticketing db")]
        S3[("S3<br/>ticket QR objects")]
        CONN[("DynamoDB<br/>waiting-room-connections")]
    end

    subgraph msg["Messaging"]
        QISS(["ticket-issuance-queue"])
        QQR(["qr-generation-queue"])
        TOPIC{{"purchase-confirmed<br/>SNS topic"}}
        QNOTIF(["notifications-queue"])
        QADM(["queue-admissions"])
    end

    SES["SES"]

    Browser -->|"sign in → access token + ID token"| COG
    Browser -->|"Bearer access token"| WAF --> APIGW --> ALB
    Browser -->|"wss, one socket per tab"| WSAPI
    WSAPI -->|"$connect / $disconnect<br/><b>AWS service integration — no Lambda</b>"| CONN
    ALB -->|"the JWT is verified <b>here</b>, in each service"| WR & SI & CP & TI

    WR --> REDIS
    SI --> DDB
    CP --> PG1
    TI --> PG2

    APIGW -->|"POST …/purchase with the ID token<br/><b>StartExecution — no Lambda</b><br/>sub + email from verified claims → saga input"| SFN
    SFN -->|"http:invoke via /{proxy+}<br/>Bearer M2M token, scope ticketing/ops"| APIGW
    SFN -->|"hold / release / confirm"| SI
    SFN -->|"create order / charge"| CP
    COG -.->|"client_credentials<br/>(EventBridge connection)"| SFN
    SFN -->|"sendMessage"| QISS
    SFN -->|"publish"| TOPIC

    QISS --> TI
    TI -->|"ticket id"| QQR --> QR --> S3
    TI -->|"presigned GET"| S3

    TOPIC --> QNOTIF --> NS --> SES
    TOPIC -.->|"email subscription"| SES

    EB -->|"every minute"| HC --> DDB

    WR -->|"one message per admitted token"| QADM --> WSN
    WSN -->|"which sockets hold this token"| CONN
    WSN -->|"postToConnection"| WSAPI

    classDef planned stroke-dasharray:5 4;
    class WAF,COG planned;
```

**Why each store is where it is** — the split is the argument the project exists to make:

| Store | Holds | Chosen because |
|---|---|---|
| DynamoDB | seat state, holds | one item, extreme contention. A conditional write settles the race with no lock and no read-modify-write window |
| Redis sorted set | queue order | FIFO fairness needs a totally-ordered atomic ticket number, which `INCR` + `ZPOPMIN` give in one round trip |
| Postgres `checkout` | orders, payments | invariants that span rows and involve money — declared as constraints, not application checks |
| Postgres `ticketing` | tickets | separate bounded context, separate Flyway history, deliberately no FK to orders |
| S3 | QR images | large immutable blobs served by presigned URL, never proxied through a 0.25-vCPU task |
| Cognito user pool | identities, one scope | the only issuer. Every service verifies its JWTs itself, so nothing is trusted for *where* it came from — see §6 |

---

## 2. The purchase, end to end

One seat, happy path. Note where the boundaries are: the saga owns the orchestration,
each service owns exactly one store, and everything after the capture is asynchronous.
Every call from the client carries its access token, and every call from the saga carries
a machine-to-machine token; each service verifies the one it receives.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant COG as Cognito
    participant E as API Gateway
    participant WR as waiting-room
    participant R as Redis
    participant SFN as Step Functions
    participant SI as seat-inventory
    participant D as DynamoDB
    participant CP as checkout-payment
    participant PG as Postgres
    participant GW as Payment gateway
    participant Q as SQS
    participant TI as ticket-issuance
    participant SNS as SNS topic
    participant NS as notification

    C->>COG: InitiateAuth
    COG-->>C: access token (sub, scope) + ID token (sub, email)

    C->>WR: POST /events/{e}/queue · Bearer access token
    Note over WR: verify JWT · token_use = access
    WR->>R: join.lua — HSETNX sub→token, INCR, ZADD
    R-->>WR: WAITING, position 42
    WR-->>C: token
    Note over C,WR: client polls GET /events/{e}/queue as itself — operator admits a batch with the ops scope
    WR->>R: admit.lua — ZPOPMIN n
    R-->>WR: admitted tokens + pass expiry

    C->>E: POST /events/{e}/purchase · ID token · {orderId, seatId, …}
    Note over E: Cognito authorizer verifies the ID token.<br/>customerRef ← claims.sub, email ← claims.email.<br/>Execution name = orderId
    E->>SFN: StartExecution
    E-->>C: 202 {orderId}
    Note over SFN: every http:invoke below carries a<br/>Bearer M2M token, scope ticketing/ops
    SFN->>SI: POST .../hold
    SI->>D: UpdateItem if status = AVAILABLE
    D-->>SI: ok
    SI-->>SFN: holdId, expiresAt

    SFN->>CP: POST /orders  (orderId, customerRef, idempotencyKey)
    CP->>PG: insert order PENDING — id is the client's orderId
    CP-->>SFN: orderId

    SFN->>CP: POST /orders/{id}/payment
    CP->>PG: tx 1 — insert payment PENDING
    CP->>GW: charge  (no transaction held)
    GW-->>CP: CAPTURED
    CP->>PG: tx 2 — payment CAPTURED + order PAID
    CP-->>SFN: paymentStatus CAPTURED

    SFN->>SI: POST .../purchase
    SI->>D: UpdateItem if HELD + holdId + not expired
    D-->>SI: SOLD

    SFN->>Q: sendMessage ticket-issuance-queue
    SFN->>SNS: publish purchase-confirmed (customerRef, email)
    C->>CP: GET /orders/{orderId} · Bearer access token
    CP-->>C: PAID — only if the order's customerRef is this sub

    Q-->>TI: TicketIssuanceMessage
    TI->>PG: insert ticket  (UNIQUE order_id, seat_id)
    TI->>Q: sendMessage qr-generation-queue (bare ticket id)
    SNS-->>NS: PurchaseConfirmedEvent
    NS->>NS: render + send email
```

---

## 3. The checkout saga

Straight from `checkout-saga.asl.json`. The shape worth reading is the **right-hand
side**: there are five distinct failure states, because what compensation means changes
the instant money moves.

```mermaid
stateDiagram-v2
    [*] --> HoldSeat

    HoldSeat --> CreateOrder: held
    HoldSeat --> SeatUnavailable: catch

    CreateOrder --> ChargePayment: order created
    CreateOrder --> ReleaseSeat: catch

    ChargePayment --> PaymentCaptured: retry x3, then choice
    ChargePayment --> ReleaseSeat: catch

    PaymentCaptured --> ConfirmPurchase: paymentStatus = CAPTURED
    PaymentCaptured --> ReleaseSeat: declined

    ConfirmPurchase --> EnqueueTicketIssuance: seat SOLD
    ConfirmPurchase --> PaidButNotConfirmed: catch

    EnqueueTicketIssuance --> NotifyPurchaseConfirmed: message on queue
    EnqueueTicketIssuance --> PaidButNotIssued: catch

    NotifyPurchaseConfirmed --> Succeeded
    Succeeded --> [*]

    ReleaseSeat --> PaymentFailed: hold released
    ReleaseSeat --> CompensationFailed: catch

    SeatUnavailable --> [*]
    PaymentFailed --> [*]
    CompensationFailed --> [*]
    PaidButNotConfirmed --> [*]
    PaidButNotIssued --> [*]
```

Three details that are easy to get wrong and are deliberate here:

- **A declined card is HTTP 200.** Compensation therefore hangs off the `PaymentCaptured`
  **Choice**, not off a `Catch`. `Catch` handles transport failures only — a `Catch`-based
  design would leave every decline running happily into ticket issuance.
- **After the capture there is no release path.** `PaidButNotConfirmed` and
  `PaidButNotIssued` both stop rather than compensate, because the compensating action
  for money that already moved is a refund, and releasing the seat would sell it twice.
- **`CompensationFailed` is not a dead end in practice.** The hold's TTL and
  `hold-cleanup-lambda` are the backstop that returns the seat regardless.

---

## 4. Seat lifecycle

The one item every concurrency claim in this project is about. Every arrow is a single
DynamoDB `UpdateItem` guarded by a `ConditionExpression`, so concurrent attempts are
serialized by DynamoDB per item and exactly one wins.

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE: seed<br/>attribute_not_exists(pk)

    AVAILABLE --> HELD: hold<br/>if status = AVAILABLE
    HELD --> AVAILABLE: release<br/>if HELD and holdId matches
    HELD --> AVAILABLE: cleanup lambda<br/>if HELD and holdId matches and expired
    HELD --> SOLD: purchase<br/>if HELD and holdId matches and NOT expired

    SOLD --> [*]

    note right of HELD
        holdExpiresAt set — DynamoDB TTL armed
    end note
    note right of SOLD
        holdExpiresAt REMOVEd, so TTL
        can never delete a sold seat
    end note
```

---

## 5. Asynchronous edges

Everything downstream of the capture, plus the one edge that runs the other way — the
admission push, which starts at the waiting room rather than the saga. Every hop is
at-least-once, and each one names what makes redelivery harmless; the point being that not
one of them is a `findBy...`-then-write check.

```mermaid
flowchart TB
    SFN["Step Functions<br/>saga"]

    SFN -->|"sendMessage"| QI(["ticket-issuance-queue"])
    SFN -->|"publish"| T{{"purchase-confirmed topic"}}

    QI --> TI["ticket-issuance-service<br/>SqsMessagePump"]
    QI -.->|"maxReceiveCount"| DLQI(["issuance DLQ"])

    TI -->|"bare ticket id"| QQ(["qr-generation-queue"])
    QQ --> QRL["qr-generation-lambda"]
    QQ -.->|"maxReceiveCount"| DLQQ(["qr DLQ"])
    QRL --> S3[("S3")]

    T --> QN(["notifications-queue"])
    T -.->|"direct email subscription"| SESD["SES"]
    QN --> NS["notification-service"]
    QN -.->|"maxReceiveCount"| DLQN(["notifications DLQ"])
    NS --> SESD

    WR["waiting-room-service<br/>admit.lua ZPOPMIN"]
    WR -->|"one message per token"| QA(["queue-admissions"])
    QA --> WSN["queue-socket-lambda<br/>notifier"]
    QA -.->|"maxReceiveCount"| DLQA(["admissions DLQ"])
    WSN -->|"postToConnection"| WS["API Gateway WebSocket"]

    I1["idempotent: UNIQUE order_id, seat_id"] -.- TI
    I2["idempotent: deterministic S3 key<br/>+ pure renderer"] -.- QRL
    I3["NOT idempotent — a redelivery<br/>sends a second email (accepted)"] -.- NS
    I4["NOT idempotent — a redelivery<br/>sends a second ADMITTED frame (accepted);<br/>a closed socket 410s and is pruned"] -.- WSN
```

The asymmetry in that third note is the honest one: a duplicate ticket or a duplicate
charge is a defect, a duplicate confirmation email is an annoyance. The fix — a
`notifications_sent` table with a unique key — is deferred rather than faked with an
in-memory guard that would last exactly as long as one task.

The fourth is cheaper still, because an `ADMITTED` frame carries no state transition, only
news of one: a client that receives it twice learns the same thing twice. What makes the
whole edge safe to be best-effort is that it is not the only channel — the admission is
already durable in Redis, and `GET /events/{eventId}/queue` still reports it. The
socket removes the need to poll; it is not what makes the answer true. That is also why the
publish is deliberately *not* allowed to fail an admission: `ZPOPMIN` has already happened,
so a retry driven by a failed announcement would pop a second batch of people.

---

## 6. Identity and authorization

Every HTTP service verifies the caller's JWT itself — signature, issuer, expiry, and that
it is an *access* token — through the one filter chain `security-starter` installs. Nothing
is trusted because of where it came from. What a valid token may then do is decided by two
claims, and only two:

```mermaid
flowchart LR
    subgraph tokens["Who holds what"]
        CT["customer access token<br/>sub = the person<br/>scope = aws.cognito.signin.user.admin"]
        MT["machine-to-machine token<br/>sub = the client id<br/>scope = <b>ticketing/ops</b>"]
        IT["customer ID token<br/>sub + <b>email</b><br/>token_use = id"]
    end

    subgraph fan["Fan door — any valid access token, as its own sub"]
        F1["POST · GET /events/{id}/queue"]
        F2["GET /orders/{id} · POST …/cancel"]
        F3["GET /orders/{id}/tickets · GET /tickets/{id}/qr"]
    end

    subgraph ops["Ops door — scope ticketing/ops, or 403 at the filter"]
        O1["POST /admin/events/{id}/seats"]
        O2["POST /events/{id}/queue/admit"]
        O3["every seat-inventory endpoint<br/>hold · release · confirm"]
        O4["POST /orders · POST /orders/{id}/payment"]
    end

    P["POST /events/{id}/purchase<br/>API Gateway only — no service behind it"]

    CT --> fan
    MT --> ops
    MT --> fan
    IT --> P
    IT -.->|"401 — token_use ≠ access"| fan
    CT -.->|"403"| ops
```

Three consequences worth holding onto:

- **There is no admin role.** Seeding, admitting and the saga's own steps are opened by one
  scope that only the `ticket-ops` client can mint. A customer's token is valid, signed by
  the same pool, and refused before any handler runs — the refusal is the filter's, and
  every one of them is a test through the real chain.
- **"Not yours" is 404, never 403.** A 403 confirms the id is real, which is all anyone
  needs to walk the id space and learn who bought what. For the QR the check runs before
  the presigned URL is minted: the point is the credential, not the status code.
- **The customer cannot lie about who is buying.** `customerRef` and `email` enter the
  saga from the ID token's verified claims at API Gateway; the body may carry them and
  they are not read. From there every row — order, ticket, confirmation — carries a value
  the customer never typed.

**One place per person** belongs here too, because it is an authorization property with a
concurrency proof: `join.lua` claims the customer's token with `HSETNX` in the same round
trip that enqueues it. Two hundred concurrent joins from one `sub` yield one place. Without
it the queue's FIFO guarantee is about tokens, not people.

**The trust boundary, stated.** The ALB is internet-facing (Phase 1 forbids a NAT Gateway)
and `X-Edge-Secret` on its listener rules is no longer what protects identity — the
services are. The secret's remaining job is to keep traffic from bypassing API Gateway, and
therefore WAF. Making that structural (an NLB behind a VPC link) is a Phase 2 cost decision,
not a security one.

---

## 7. Build status by slice

```mermaid
flowchart LR
    S0["0 · account<br/>foundations"] --> S1["1 · seat inventory<br/>DynamoDB + Fargate"]
    S1 --> S2["2 · hold expiration<br/>EventBridge + Lambda"]
    S2 --> S3["3 · waiting room<br/>ElastiCache Redis"]
    S3 --> S4["4 · checkout saga<br/>Step Functions + RDS + SQS"]
    S4 --> S5["5 · issuance + notification<br/>SQS, S3, SNS, SES"]
    S5 --> S6["6 · edge & auth<br/>ALB + APIGW + WebSocket done<br/>Cognito: code proven, AWS pending · WAF pending"]
    S6 --> S7["7 · observability<br/>+ load test"]
    S7 --> S8["8 · wrap-up"]

    classDef done fill:#2f6f4f,stroke:#1d4632,color:#fff;
    classDef partial fill:#7a5c1e,stroke:#4d3a13,color:#fff;
    classDef todo stroke-dasharray:5 4;
    class S0,S1,S2,S3,S4,S5 done;
    class S6 partial;
    class S7,S8 todo;
```
