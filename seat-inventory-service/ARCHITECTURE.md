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
    end

    D[("DynamoDB<br/>seat-inventory")]
    HC["hold-cleanup-lambda<br/>second writer of this table"]

    SFN --> SHC
    Client --> SSC
    SHC --> SHS --> SR --> D
    SSC --> SSS --> SR
    SHC -.-> EH
    HC --> D

    SCH["common · SeatTableSchema<br/>attribute names shared by both writers"] -.- SR
    SCH -.- HC
```

`SeatTableSchema` lives in `common` precisely because there are two writers. A divergent
attribute name is not a compile error and not a runtime error — DynamoDB accepts any
attribute name. It is a condition that silently never matches.

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
    SOLD --> [*]
```

Three guards worth spelling out:

- **`holdId` on release** — without it a stale caller, whose hold already expired and was
  re-acquired by someone else, would tear up the *current* owner's hold.
- **`holdExpiresAt > now` on confirm** — the expiry check must live in the write itself.
  DynamoDB TTL deletes lazily, possibly much later, so an expired-but-not-yet-deleted hold
  would otherwise still be confirmable.
- **`REMOVE holdExpiresAt` on confirm** — otherwise TTL eventually deletes a SOLD seat,
  and the seat vanishes from inventory rather than staying sold.

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
**30 concurrent holds on one seat → 1×201, 29×409** (2026-08-22).
`SeatStatusSchemaContractTest` pins the enum to `SeatTableSchema`'s string constants so
the two writers cannot drift apart silently.
