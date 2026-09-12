# queue-socket-lambda

The push half of the waiting room: an SQS-driven notifier that turns an admission into a
frame on every socket holding the admitted token.

`$connect` and `$disconnect` are **not here**. They are API Gateway service integrations
writing DynamoDB directly, so this module is the read side of a registry it does not write.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-websocket-queue.md`](../docs/runbook-websocket-queue.md)

## Shape

```mermaid
flowchart TB
    B["Browser"]
    WS["API Gateway WebSocket<br/>ticket-queue-socket"]
    WR["waiting-room-service<br/>admit.lua ZPOPMIN"]
    Q(["queue-admissions"])

    subgraph fn["queue-socket-lambda — one function"]
        NH["AdmissionNotifierHandler<br/>RequestHandler&lt;SQSEvent, SQSBatchResponse&gt;"]
        CR["ConnectionRegistry<br/>Query by-token · DeleteItem on 410"]
        CP["ApiGatewayConnectionPusher<br/>postToConnection"]
    end

    T[("DynamoDB · waiting-room-connections<br/>HASH connectionId · GSI by-token · TTL")]
    DLQ(["admissions DLQ"])

    B -->|"wss ?eventId=&token="| WS
    WS -->|"$connect → PutItem<br/><b>mapping template, no function</b>"| T
    WS -->|"$disconnect → DeleteItem<br/><b>mapping template, no function</b>"| T
    WR -->|"one message per token"| Q
    Q -->|"event source mapping<br/><b>ReportBatchItemFailures ON</b>"| NH --> CR --> T
    NH --> CP -->|"@connections"| WS
    WS -->|"ADMITTED frame"| B
    Q -.->|"maxReceiveCount"| DLQ

    N["No Spring: a DI container in a Lambda is cold-start tax. Apache 5 and Netty<br/>HTTP stacks excluded — the single biggest lever on Lambda's separate 10s INIT budget."] -.- fn

    classDef nofn fill:#2f6f4f,stroke:#1d4632,color:#fff;
    class T nofn;
```

## Why the connection routes have no code

Each route does one unconditional write against a key API Gateway already holds. Nothing is
computed, nothing is decided, the result is ignored — a mapping template's job description,
not a function's.

The cost of getting this wrong is not the invoice, which is about $0.15 per 50k-user event.
It is that fifty thousand sockets opening in ten seconds needs ~1,000 concurrent executions
of a JVM container image with a ~500 ms cold start, **container images cannot use SnapStart**,
and a throttled `$connect` drops the client back to polling — the stampede this whole slice
exists to prevent. The design would have been weakest at the only moment it mattered.

```mermaid
flowchart LR
    subgraph before["was"]
        C1["$connect → Lambda → PutItem<br/>1 invocation per socket"]
        C2["hand-formatted frame<br/>+ 403 validation gate"]
        C1 -.->|"cold start per connection<br/>is what made a JSON<br/>parser look expensive"| C2
    end

    subgraph after["is"]
        D1["$connect → DynamoDB<br/>no function"]
        D2["serialised frame<br/>no gate needed"]
    end

    before ==>|"removing the function<br/>dissolved both halves"| after
```

The two decisions were propping each other up: formatting by hand was cheap only because a
per-connection Lambda validated its inputs, and validating was justified only by the
formatting. The notifier is invoked once per **batch of ten** admissions rather than once per
socket, so its INIT is amortised and it can simply serialise — see below.

What it cost: the write path left the test suite. That is defensible precisely because
nothing on it decides anything, and it would not be defensible for a path that did.

## The registry, and the KEYS_ONLY trick

`$disconnect` is given a `connectionId` and nothing else, so that has to be the base key.
The notifier has the opposite problem: it holds a token and needs the sockets.

```mermaid
flowchart TB
    subgraph tbl["waiting-room-connections"]
        BK["<b>base table</b> — HASH connectionId<br/>eventId · token · connectedAt · expiresAt"]
        IX["<b>GSI by-token</b> — HASH eventId, RANGE token<br/>projection <b>KEYS_ONLY</b>"]
    end

    D["$disconnect integration: DeleteItem by connectionId"] --> BK
    NN["notifier: Query (eventId, token)"] --> IX
    IX -->|"the projected base key <b>is</b> the connectionId"| NN

    N["KEYS_ONLY is not frugality for its own sake: the only attribute the<br/>lookup wants is the base key, which every index projects for free.<br/>An INCLUDE or ALL projection would pay write amplification on every<br/>connect to store attributes nobody reads — and a PutItem into a table<br/>with one GSI already costs two write units rather than one."] -.- IX
```

Two tabs on one token are two rows, and both get the frame. That fan-out is what
`QueueSocketIT.everyTabOpenedAtOnceStillGetsTheFrame` races: fifty sockets registered at
once, one admission, fifty frames.

**Nothing in this repo writes a row.** `ConnectionTableSchema` names the attributes for the
reader, but the authoritative writer is VTL in the runbook, so a rename is no longer a
compile error. `QueueSocketIT.theRowShapeTheMappingTemplateWritesIsTheOneTheLookupReads`
replaces that compiler: it seeds a row with the attribute names written out as *literals*, so
the test disagrees with the reader the moment either side drifts.

## Why the push is allowed to be best-effort

```mermaid
flowchart TB
    A["admit.lua ZPOPMIN — <b>durable</b>"] --> B["Redis: token in the admitted zset"]
    A --> C["queue-admissions → notifier → frame"]
    B --> D["GET /events/{id}/queue<br/><b>the source of truth</b>"]
    C --> E["the socket — an <b>optimisation</b> over polling"]

    N["Everything questionable about this edge is survivable because of that split:<br/>the GSI read is eventually consistent, so a token admitted milliseconds after<br/>connecting can be missed; a redelivered message sends a second frame; a socket<br/>that closed without a $disconnect answers 410. None of it can make the answer<br/>wrong — only make a client fall back to the poll it already knows how to do."] -.- D

    classDef win fill:#2f6f4f,stroke:#1d4632,color:#fff;
    class D win;
```

A `GoneException` is therefore treated as **news rather than failure**: the row is pruned
and the record still succeeds. Retrying it would only rediscover the same dead socket, and
counting it as a failure would eventually dead-letter a message that had nothing wrong
with it.

## No JSON inbound, real JSON outbound

The SQS body is the bare token and the other two values ride as message attributes, which
the SDK types on both ends — the same argument as `qr-generation-lambda`'s bare ticket id,
and for the same reason: a parser on this classpath is cold-start time spent on three values.

Outbound the frame is **serialised**:

```
{"type":"ADMITTED","eventId":"evt-1","token":"<uuid>","admissionExpiresAt":1757088000}
```

This is where the removed 403 gate would otherwise be missed. The query string now reaches
DynamoDB through a mapping template that escapes for JSON but does not *validate*, so an
event id containing a quote is a row this function will read; formatting the frame by hand
would emit that quote raw and hand the client broken or forged structure.

Serialising costs nothing to reach for. `JsonWriter` lives in `software.amazon.awssdk:json-utils`,
which the DynamoDB and API Gateway clients already pull in and already load to parse their own
responses — the staged package is **37 jars either way**. The INIT budget that made
hand-formatting look thrifty was never going to be charged twice.
`QueueSocketIT.aTokenThatWouldHaveBrokenAHandBuiltFrameIsEscapedInstead` pushes exactly the
injection the old gate existed to refuse and asserts the frame parses back with the quote
intact as data.

This is confirmed against real AWS rather than assumed: connecting with
`eventId=evt", "injected":"yes` stores the quote as **data** in the row, creating no extra
attribute — so a hostile event id genuinely does reach this function, and formatting the frame
by hand would genuinely have emitted it as structure. See the runbook's Results.

## Nothing here touches Redis

Deliberate, and it is what keeps this function out of the VPC — no ENI attachment on cold
start, no NAT. The consequence is that connecting cannot answer "what is my position", and in
any case a socket does not exist until `$connect` returns 200, so there would be nothing to
post to. The client takes its opening snapshot from the REST endpoint it already calls, and
the socket carries only the transition.
