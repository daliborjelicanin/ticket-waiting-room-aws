# qr-generation-lambda

Renders ticket QR codes onto S3. The clearest example in the project of **idempotency by
construction** rather than by guard.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-qr-generation.md`](../docs/runbook-qr-generation.md)

## Shape

```mermaid
flowchart TB
    TI["ticket-issuance-service<br/>QrGenerationDispatcher"]
    Q(["qr-generation-queue"])

    subgraph fn["qr-generation-lambda — container image on eclipse-temurin"]
        H["QrGenerationHandler<br/>RequestHandler&lt;SQSEvent, SQSBatchResponse&gt;"]
        RN["QrRenderer — <b>pure</b><br/>zxing → 1-bit PNG, 512px, ECC level Q"]
        ST["QrObjectStore<br/>unconditional PutObject"]
        C["S3Client · url-connection-client"]
    end

    S3[("S3 · SSE-KMS, 7-day lifecycle,<br/>all public access blocked")]
    DLQ(["qr DLQ"])

    TI -->|"<b>bare ticket id</b>, not JSON"| Q
    Q -->|"event source mapping<br/><b>ReportBatchItemFailures ON</b>"| H
    H --> RN --> ST --> C --> S3
    Q -.->|"maxReceiveCount"| DLQ

    N["A queue, not lambda:InvokeFunction: rendering is slow,<br/>failable work nothing is waiting on. A synchronous invoke<br/>would put a cold start on the consumer's critical path<br/>and make a rendering failure into an issuance failure."] -.- Q
```

## The message body is a bare ticket id

```mermaid
flowchart LR
    A["message body<br/><code>3f9a...c21</code>"] --> B["TicketQrObjects.key(id)<br/>tickets/{id}.png"]
    A --> C["TicketQrObjects.verificationUrl(base, id)<br/>{base}/tickets/{id} — encoded in the image"]

    N["Everything the function needs is derived from the id,<br/>so there is no JSON on this hop and therefore no JSON<br/>parser on a classpath that pays cold-start time for every<br/>class it loads. Slice 2 measured Lambda's separate 10s INIT<br/>budget being blown by class loading alone.<br/><br/>The cost, stated: the message carries no context for a human<br/>reading the DLQ — which is why the ticket id is echoed in<br/>every log line."] -.- A
```

## Idempotency without a mechanism

```mermaid
flowchart TB
    A["delivery 1: ticket id X"] --> C["key = tickets/X.png"]
    B["delivery 2: same id X"] --> C
    C --> D["QrRenderer — no clock, no randomness,<br/>no metadata in the PNG"]
    D --> E["byte-identical output"]
    E --> F["unconditional PutObject"]
    F --> G["'already there' and 'not there yet'<br/>are the <b>same write</b>"]

    N["No conditional put. No dedupe table. Either would add a<br/>request and a race in order to skip an operation that is<br/>already harmless."] -.- G

    classDef win fill:#2f6f4f,stroke:#1d4632,color:#fff;
    class G win;
```

The S3 key contract lives in `common` as `TicketQrObjects`, shared with the reader in
`ticket-issuance-service`. A divergent key between writer and reader is not a compile
error and not a runtime error — it is a download endpoint reporting PENDING forever while
the object sits happily in the bucket under another name.

## Partial batch failure

```mermaid
flowchart TB
    A["batch of 10 records"] --> B{"per record"}
    B -->|"UUID.fromString ok"| C["render + put → logged"]
    B -->|"malformed body"| D["add to batchItemFailures<br/>— reported, not rethrown"]
    C & D --> E["SQSBatchResponse"]

    F["<b>Throwing instead</b> would fail the whole batch:<br/>SQS redelivers all ten, nine of which succeeded, so one<br/>unparseable id drags its batch-mates towards the DLQ and<br/>re-renders nine images every attempt."] -.- D
    G["This only works if the event source mapping has<br/><b>ReportBatchItemFailures</b> enabled. Without it Lambda<br/>ignores the return value entirely and treats the invocation<br/>as a blanket success — silently dropping the failures."] -.- E

    classDef warn fill:#7a5c1e,stroke:#4d3a13,color:#fff;
    class G warn;
```

The `UUID.fromString` parse is not validation for its own sake: a malformed record must
fail **before** it can write an object under an attacker-chosen key.
