# ticket-issuance-service

Turns a captured payment into a ticket, then asks for its QR code. Tickets are created by
the SQS consumer and **never** by a request — there is deliberately no POST on the
controller, because an endpoint that issues a ticket would be a second path to the same
row that bypasses the saga entirely.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-ticket-issuance.md`](../docs/runbook-ticket-issuance.md)

## Components

```mermaid
flowchart TB
    QI(["ticket-issuance-queue"])
    Client["Client"]

    subgraph svc["ticket-issuance-service — ECS Fargate task"]
        TIC["TicketIssuanceConsumer<br/><b>SmartLifecycle</b> — starts after the datasource<br/>and web layer, stops before they go down"]
        PUMP["common · SqsMessagePump<br/>virtual-thread consumers, long poll"]
        TIS["TicketIssuanceService<br/><b>not transactional</b> — spans the SQS send"]
        TW["TicketWriter<br/>the transactional unit.<br/>A separate bean because @Transactional is a<br/>proxy and a self-call goes straight past it."]
        TC["TicketController<br/>GET /orders/{id}/tickets · GET /tickets/{id}/qr<br/><b>read-only</b> · scoped to the JWT's sub"]
        QD["QrGenerationDispatcher"]
        QS["TicketQrStore<br/>HeadObject + presign"]
    end

    PGDB[("RDS Postgres · ticketing db")]
    QQ(["qr-generation-queue"])
    S3[("S3 · QR objects")]

    QI --> TIC --> PUMP --> TIS
    TIS --> TW --> PGDB
    TIS --> QD --> QQ
    Client --> TC --> TIS
    TC --> QS --> S3
```

## Idempotency is one line of SQL

```sql
CONSTRAINT uq_tickets_order_seat UNIQUE (order_id, seat_id)
```

SQS Standard is at-least-once and the saga retries `EnqueueTicketIssuance` up to four
times, so duplicate delivery is **ordinary operation**, not an edge case. A
`findByOrderIdAndSeatId` guard would be two statements with a race between them, and would
only protect against writers that remember to call it.

```mermaid
sequenceDiagram
    participant Q as SQS
    participant C1 as Consumer 1
    participant C2 as Consumer 2
    participant DB as Postgres

    Note over Q: same purchase delivered twice
    par
        Q->>C1: message
        C1->>DB: saveAndFlush ticket
    and
        Q->>C2: duplicate
        C2->>DB: saveAndFlush ticket
    end

    DB-->>C1: inserted → IssueOutcome.Issued
    DB-->>C2: uq_tickets_order_seat violation
    C2->>DB: findIssued — REQUIRES_NEW,<br/>the failed transaction is rollback-only
    DB-->>C2: the same ticket → IssueOutcome.AlreadyIssued

    C1->>Q: request QR, then delete message
    C2->>Q: request QR, then delete message
    Note over C1,C2: both dispatch the QR request — see below
```

## The insert-then-enqueue gap, and why redelivery is the repair

`issue()` is not transactional: it spans an SQS send, the same mistake `CheckoutService`
avoids around the payment gateway. That leaves an obvious gap — the insert commits, the
send fails, and a ticket exists with no QR code.

```mermaid
flowchart TB
    A["insert ticket — commits"] --> B{"SQS send"}
    B -->|"ok"| C["handler returns → message deleted"]
    B -->|"fails"| D["handler throws → <b>message not deleted</b>"]
    D --> E["visibility timeout lapses,<br/>SQS redelivers"]
    E --> F["insert loses to uq_tickets_order_seat"]
    F --> G["AlreadyIssued branch"]
    G --> H["QR request dispatched from here"]
    H --> C

    N["This is why the dispatch sits <i>outside</i> the switch<br/>rather than in the Issued arm: the duplicate path<br/>is not a no-op, it is the recovery path."] -.- G

    classDef repair fill:#7a5c1e,stroke:#4d3a13,color:#fff;
    class D,E,F,G,H repair;
```

## SqsMessagePump delivery semantics

Handwritten rather than an annotation, because these semantics are the point of the slice.

```mermaid
stateDiagram-v2
    [*] --> Receiving: long poll, up to 20s
    Receiving --> Handling: batch of ≤10
    Receiving --> Backoff: SdkException on receive
    Backoff --> Receiving: 2s

    Handling --> Deleting: handler returned
    Handling --> LeftOnQueue: handler threw

    Deleting --> Receiving: acknowledged
    Deleting --> LeftOnQueue: delete call failed —<br/>work is done, only the ack was lost

    LeftOnQueue --> Receiving: visibility timeout lapses,<br/>redelivered
    LeftOnQueue --> DLQ: maxReceiveCount exceeded
    DLQ --> [*]
```

Three properties, in the order they matter:

1. **Delete only after the handler returns.** Deleting on receipt makes the queue an
   at-most-once channel and a crash mid-handler loses the work outright.
2. **A failed handler leaves the message alone.** No delete, no explicit nack — the queue
   owns the retry budget and the redrive policy.
3. **Redelivery is expected.** The pump adds two sources of duplication beyond SQS's own:
   a successful handler whose delete failed, and a shutdown mid-batch. Every handler wired
   to this class must be idempotent; that is a requirement of the transport.

## QR download

```mermaid
sequenceDiagram
    participant C as Client
    participant TC as TicketController
    participant S3 as S3
    participant QRL as qr-generation-lambda

    Note over QRL,S3: writing the object, asynchronously
    C->>TC: GET /tickets/{id}/qr + Bearer access token
    Note over TC: security-starter's chain verifies the JWT first —<br/>signature, issuer, expiry, token_use=access —<br/>an anonymous request is 401 before this point.<br/>This service declares no rules of its own.
    alt not this customer's ticket
        TC-->>C: 404 — identical to an unknown id,<br/>and no signature is ever minted
    end
    TC->>S3: HeadObject
    alt object exists
        S3-->>TC: 200
        TC->>S3: presign GET, 15 min
        TC-->>C: 200 READY + url
    else not rendered yet
        S3-->>TC: NoSuchKey
        TC-->>C: 200 <b>PENDING</b>
    end
```

Three decisions in that diagram:

- **Ownership is settled before S3 is touched.** A presigned URL is a bearer token for the
  object, so the check guards the credential rather than the response: a caller who does
  not own the ticket never causes a signature to exist. It answers 404 rather than 403 for
  the same reason the listing returns an empty array — a 403 confirms the id is real, which
  is all anyone needs to walk the id space and learn who bought what.
- **`HeadObject`, not a flag on the ticket row.** Issuance and rendering are separate
  deployables writing to separate stores; a column here claiming the image exists would be
  a guess about someone else's state, and the presigned URL it produced would 404 in the
  customer's browser.
- **200 PENDING, not 404 or 202.** The ticket exists and is valid; only its rendering is
  in flight. A 404 would tell the client the ticket is wrong, and a 202 would imply this
  request started the work.

The bucket blocks all public access. A presigned URL scoped to one key for a few minutes
is what grants a customer their own ticket and nothing else — streaming the image through
a 0.25-vCPU Fargate task would buy nothing.

`GET /tickets/{ticketId}` — the URL the QR image itself encodes — is **not implemented**,
and that is now a deliberate deferral rather than the oversight slice 5 recorded. It is a
gate-scan endpoint: presented *to* a venue, not read *by* the buyer. What it needs is
exactly-once redemption, a conditional write in the same family as the seat hold, plus a
staff population with its own scope and its own devices. That is its own slice, and half of it
folded into the auth slice would have wasted the good one.

## Proven

`TicketIssuanceIT` (Testcontainers Postgres + LocalStack):
**20 concurrent deliveries of one purchase → 1 ticket, 19 duplicates all resolving to the
same ticket id; 4 queued copies → 1 ticket, queue drained** (2026-08-29).
`TicketIssuanceContractTest` pins `TicketIssuanceMessage`'s field names to
`docs/checkout-saga.asl.json`, which no compiler checks.
