# hold-cleanup-lambda

Scheduled reclamation of expired seat holds. The second writer of the seat table, and the
reason TTL alone is not a correctness mechanism.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-hold-cleanup.md`](../docs/runbook-hold-cleanup.md)

## Shape

```mermaid
flowchart TB
    EB["EventBridge Scheduler<br/>empty payload · <b>0 retry attempts</b>"]

    subgraph fn["hold-cleanup-lambda — container image on eclipse-temurin"]
        H["HoldCleanupHandler<br/>RequestHandler&lt;Map, Summary&gt;<br/>input is <b>ignored</b>"]
        R["ExpiredHoldRepository<br/>findExpiredHolds · reclaim"]
        C["DynamoDbClient<br/>url-connection-client, built once<br/>per execution environment"]
    end

    D[("DynamoDB · seat-inventory")]

    EB --> H --> R --> C --> D
    SCH["common · SeatTableSchema"] -.- R

    N["No Spring: a DI container in a Lambda is cold-start tax.<br/>Apache 5 and Netty HTTP stacks excluded — the single<br/>biggest lever on Lambda's separate 10s INIT budget."] -.- fn
```

**The input is ignored on purpose.** The function's work is defined entirely by what the
table looks like at invocation time, which makes it idempotent and safe to invoke at any
cadence, from any source, including twice concurrently.

## Why TTL is not enough

```mermaid
flowchart TB
    A["hold expires at T"] --> B{"what does DynamoDB TTL do?"}
    B --> C["TTL is <b>lazy</b> — 'typically within 48 hours'.<br/>The expired hold stays readable, and therefore<br/>un-holdable by anyone else, long after it lapsed."]
    B --> D["When TTL does fire on a HELD seat it deletes<br/>the <b>whole item</b>, not the hold — the seat vanishes<br/>from inventory rather than becoming available."]
    C --> E["Reclaiming is a correctness requirement.<br/>TTL is only garbage collection."]
    D --> E

    classDef bad fill:#7a2f2f,stroke:#4d1d1d,color:#fff;
    class C,D bad;
```

## One invocation

```mermaid
sequenceDiagram
    participant EB as EventBridge
    participant H as Handler
    participant R as Repository
    participant D as DynamoDB

    EB->>H: invoke, empty payload
    H->>R: findExpiredHolds(now)
    R->>D: Scan · filter status = HELD AND holdExpiresAt <= now<br/>projection: eventId, seatId, holdId, holdExpiresAt
    D-->>R: candidates — a <b>stale read</b> by the time any write lands

    loop per expired hold
        H->>H: re-read the clock — a large batch can take long enough<br/>that a hold near the boundary is no longer expired
        H->>R: reclaim(hold, now)
        R->>D: UpdateItem, three guards
        alt condition holds
            D-->>R: reclaimed
        else another writer won
            D-->>R: ConditionalCheckFailed — counted, not retried
        end
    end

    H-->>EB: Summary{expiredFound, reclaimed, lostRace}
```

## The three guards on `reclaim`

The scan is stale, so nothing from it is trusted at write time:

```mermaid
flowchart LR
    A["UpdateItem<br/>SET status = AVAILABLE<br/>REMOVE holdId, holdExpiresAt"] --> B["#status = :held"]
    A --> C["#holdId = :holdId"]
    A --> D["#ttl &lt;= :now"]

    B --> B1["never resurrect a seat<br/>that has since been SOLD"]
    C --> C1["a seat that expired and was legitimately<br/>re-held in the intervening milliseconds<br/>keeps the <b>new</b> owner's hold"]
    D --> D1["a hold extended after the scan<br/>is left alone"]
```

Losing this race is normal operation, not an error: a purchase landing in the same instant
is exactly the contention this design expects, and DynamoDB serializes the two conditional
writes so precisely one takes effect. A lost race is counted and left for the next tick —
which is why the schedule is configured with **zero** retry attempts.

## Known scale limit, stated rather than hidden

A `Scan` is the honest choice at this table's size: seats are stored one partition per
event with no index on status, so there is nothing to `Query`. It reads the whole table,
which is fine for a learning-scale event and would not be for a real one. The scale answer
is a **sparse GSI** carrying `holdExpiresAt` only while a seat is held, turning this into a
bounded range `Query`. Deliberately deferred.

## Proven

An abandoned hold was reclaimed by the schedule alone in **~53s**, no manual invoke; live
holds and SOLD-with-stale-TTL seats untouched (2026-08-22). `HoldCleanupIT` covers the
same against DynamoDB-local.
