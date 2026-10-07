# seat-inventory-service

The centerpiece. One DynamoDB item per seat, and every state transition is a single
conditional write — no application lock, no `SELECT ... FOR UPDATE`, no read-modify-write
window.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-seat-inventory.md`](../docs/runbook-seat-inventory.md)

## Components

```mermaid
flowchart TB
    SFN["Step Functions saga"]
    Client["Client / operator"]

    subgraph svc["seat-inventory-service — ECS Fargate task"]
        SHC["SeatHoldController<br/>POST hold · DELETE hold · POST purchase"]
        SSC["SeatSeedController<br/>POST seats — re-runnable seeding"]
        SHS["SeatHoldService<br/>mints holdId + expiry, supplies the clock"]
        SSS["SeatSeedService"]
        SR["SeatRepository<br/><b>every conditional write lives here</b>"]
        EH["ApiExceptionHandler<br/>SeatUnavailable → 409 · HoldNotFound → 404"]
        MET["SeatInventoryMetrics<br/>outcome counters · seats.remaining gauge"]
    end

    D[("DynamoDB<br/>seat-inventory")]
    HC["hold-cleanup-lambda<br/>second writer of this table"]

    SFN --> SHC
    Client --> SSC
    SHC --> SHS --> SR --> D
    SSC --> SSS --> SR
    SHC -.-> EH
    SHS -.->|"held / unavailable<br/>released / confirmed / replayed / not_found"| MET
    MET -->|"COUNT query per known event, every 30s"| SR
    HC --> D

    SCH["common · SeatTableSchema<br/>attribute names shared by both writers"] -.- SR
    SCH -.- HC
```

`SeatTableSchema` lives in `common` precisely because there are two writers. A divergent
attribute name is not a compile error and not a runtime error — DynamoDB accepts any
attribute name. It is a condition that silently never matches.

## What the dashboard reads

`SeatHoldService` counts each transition by its outcome — the repository knows it lost a
conditional write, the service knows what that meant — into `seat.hold.attempts`,
`seat.hold.releases` and `seat.purchase.confirmations`, each tagged `outcome` (a
confirmation can also be `replayed`, a retried confirm for a hold that already won). Under the
load test `held` : `unavailable` on one seat is the headline ratio. `seats.remaining{event}`
is a gauge refreshed on a schedule from `SeatRepository.countAvailable` — a `Select.COUNT`
query with a server-side status filter, paged because DynamoDB pages on bytes read rather
than rows matched — for the events this task has seen since it started; it is never on the
request path, where a query per hold would cost more than the hold. How the numbers leave
the task is `observability-starter`'s business.

## The item

```mermaid
flowchart LR
    subgraph item["One seat"]
        direction TB
        PK["<b>pk</b> = EVENT#{eventId}"]
        SK["<b>sk</b> = SEAT#{seatId}"]
        ST["status = AVAILABLE | HELD | SOLD"]
        HI["holdId — absent when AVAILABLE,<br/>kept on SOLD as the audit trail<br/>of the winning hold"]
        TTL["holdExpiresAt — epoch <b>seconds</b>, Number.<br/>Present only while HELD.<br/>A String, or milliseconds, is accepted<br/>on write and then ignored by TTL forever."]
    end
```

## Transitions

Every arrow is one `UpdateItem`. The guard is written on the arrow, because the guard *is*
the concurrency control.

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE

    note left of AVAILABLE
        createAvailableSeat
        PutItem if attribute_not_exists(pk)
        already-exists returns false, not an error,
        so seeding is safely re-runnable
    end note

    AVAILABLE --> HELD: <b>holdSeat</b><br/>if status = AVAILABLE<br/>SET status, holdId, holdExpiresAt
    HELD --> AVAILABLE: <b>releaseHold</b><br/>if status = HELD AND holdId = mine<br/>REMOVE holdId, holdExpiresAt
    HELD --> AVAILABLE: <b>lambda reclaim</b><br/>if HELD AND holdId = scanned AND holdExpiresAt &lt;= now
    HELD --> SOLD: <b>confirmPurchase</b><br/>if HELD AND holdId = mine AND holdExpiresAt &gt; now<br/>REMOVE holdExpiresAt
    SOLD --> SOLD: <b>confirmPurchase replayed</b><br/>condition fails · ALL_OLD = SOLD, holdId = mine<br/>200, nothing written
    SOLD --> [*]
```

Four guards worth spelling out:

- **`holdId` on release** — without it a stale caller, whose hold already expired and was
  re-acquired by someone else, would tear up the *current* owner's hold.
- **`holdExpiresAt > now` on confirm** — the expiry check must live in the write itself.
  DynamoDB TTL deletes lazily, possibly much later, so an expired-but-not-yet-deleted hold
  would otherwise still be confirmable.
- **`REMOVE holdExpiresAt` on confirm** — otherwise TTL eventually deletes a SOLD seat,
  and the seat vanishes from inventory rather than staying sold.
- **Confirm is idempotent on the winning hold.** The saga retries the confirm, and before
  2026-10-07 a retry whose predecessor had committed but lost its response found the seat
  SOLD and got a 404. The saga now *refunds* on a 404, so that answer would have handed
  the buyer both the seat and their money. The write asks for
  `ReturnValuesOnConditionCheckFailure = ALL_OLD`. A failed condition then returns the item
  as it stood, in the same round trip, and SOLD under the caller's own `holdId` is answered
  200 without writing. SOLD is terminal and a hold id is never reused, so the answer
  cannot go stale. Every other failed condition stays a 404, and that is what makes the
  404 safe to refund on.

## The race

```mermaid
sequenceDiagram
    participant A as Request A
    participant B as Request B
    participant C as ... 30 concurrent
    participant D as DynamoDB — serializes per item

    par all racing the same seat
        A->>D: UpdateItem if status = AVAILABLE
    and
        B->>D: UpdateItem if status = AVAILABLE
    and
        C->>D: UpdateItem if status = AVAILABLE
    end

    D-->>A: 200 — status now HELD
    D-->>B: ConditionalCheckFailedException
    D-->>C: ConditionalCheckFailedException
    Note over A,D: SeatUnavailableException → HTTP 409.<br/>Losing is ordinary operation, not an error condition.
```

## Proven

`SeatHoldConcurrencyIT` locally, and on real Fargate + DynamoDB:
**30 concurrent holds on one seat → 1×201, 29×409** (2026-08-22). Idempotent confirm
(2026-10-07, LocalStack; not yet on AWS): **100 concurrent confirms of one hold → one
write, 99 replays, 0 refusals**. A confirm under a different hold on a SOLD seat, and one
after the hold was released, are both still refused.
`SeatStatusSchemaContractTest` pins the enum to `SeatTableSchema`'s string constants so
the two writers cannot drift apart silently.
