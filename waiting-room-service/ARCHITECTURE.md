# waiting-room-service

Holds the line in front of the sale. One FIFO queue per event, in Redis sorted sets, with
the fairness guarantee coming from the data structure rather than from request arrival
order at the API layer.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-waiting-room-redis.md`](../docs/runbook-waiting-room-redis.md)

## Components

```mermaid
flowchart TB
    Client["Client"]

    subgraph svc["waiting-room-service — ECS Fargate task"]
        WC["WaitingRoomController<br/>POST /queue · GET /queue/{token} · POST /queue/admit"]
        WS["WaitingRoomService<br/>mints token + admission deadline, supplies the clock"]
        WQ["WaitingRoomQueue<br/>key naming + Lua script dispatch"]
        EH["ApiExceptionHandler<br/>UnknownQueueToken → 404 ProblemDetail"]
    end

    subgraph lua["Lua — src/main/resources/redis"]
        JOIN["join.lua"]
        POS["position.lua"]
        ADM["admit.lua"]
    end

    R[("ElastiCache Redis")]

    Client --> WC --> WS --> WQ
    WC -.-> EH
    WQ --> JOIN & POS & ADM --> R

    N["No task role: this service<br/>makes no AWS API calls at all"] -.- svc
```

The service layer holds **no locking of its own** — it only creates identity and
deadlines. Every ordering and atomicity guarantee lives in the three scripts.

## Redis keys

All three share the `{eventId}` hashtag so a future clustered ElastiCache keeps them on
one slot; a script spanning slots is rejected outright.

```mermaid
flowchart LR
    subgraph keys["Per event"]
        Q["wr:{eventId}:queue<br/><b>zset</b> · token → ticket number<br/>still waiting"]
        S["wr:{eventId}:seq<br/><b>counter</b> · the ticket-number source"]
        A["wr:{eventId}:admitted<br/><b>zset</b> · token → pass expiry<br/>let through"]
    end

    S -->|"INCR supplies the score"| Q
    Q -->|"ZPOPMIN moves the head"| A
    A -->|"lapsed pass reclaimed<br/>by ZREMRANGEBYSCORE"| X["gone — re-joins from the back"]

    T["one shared TTL, refreshed on every join and admit:<br/>a counter that outlived its queue would restart<br/>ticket numbers and let a late joiner outrank everyone"] -.- keys
```

## Token lifecycle

```mermaid
stateDiagram-v2
    [*] --> WAITING: join.lua<br/>INCR + ZADD
    WAITING --> WAITING: re-join with same token<br/>ZSCORE hit — place is kept
    WAITING --> ADMITTED: admit.lua<br/>ZPOPMIN + ZADD admitted
    ADMITTED --> ADMITTED: join / position while pass is live
    ADMITTED --> EXPIRED: pass deadline passes
    EXPIRED --> WAITING: re-join — back of the queue
    EXPIRED --> [*]: slot reclaimed by the next admit
    [*] --> NOT_QUEUED: position.lua on an unknown token
    NOT_QUEUED --> [*]: 404
```

`position.lua` **reports** a lapsed pass as `EXPIRED` but never deletes it — reclaiming is
`admit.lua`'s job, which keeps the polling path free of writes and therefore safe to hit
hard.

## Why one Lua script per operation

Each operation must be a single atomic round trip. The scripts are where the reasoning
lives because that is where the commands are.

```mermaid
sequenceDiagram
    participant A as Client A
    participant B as Client B
    participant R as Redis — single threaded

    Note over A,B: both join in the same millisecond
    A->>R: EVAL join.lua
    activate R
    R->>R: ZSCORE — absent
    R->>R: INCR seq → 41
    R->>R: ZADD queue 41 tokenA
    R-->>A: WAITING, position 41
    deactivate R
    B->>R: EVAL join.lua
    activate R
    R->>R: INCR seq → 42
    R->>R: ZADD queue 42 tokenB
    R-->>B: WAITING, position 42
    deactivate R
    Note over R: distinct, contiguous, totally ordered.<br/>Scoring by wall clock would tie here — and a<br/>sorted set breaks ties by member name, silently<br/>ordering the queue by token UUID.
```

The same argument for `admit.lua`: `ZPOPMIN` alone is already atomic, so two admitters can
never pop the same token. The reason it is a *script* is the **move** — pop and record are
two commands, and a token dropped between them would leave the queue without ever being
admitted.

## Proven

`WaitingRoomFairnessIT`, and the same run against real Fargate + ElastiCache:
**100 concurrent joins → 100 distinct contiguous places, admission order matched the
places handed out exactly, 0 duplicates** (2026-08-23).
