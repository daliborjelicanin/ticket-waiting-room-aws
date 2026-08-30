# notification-service

Sends the purchase-confirmation email. The smallest service, and the one place in the
project where at-least-once delivery is visible to a person.

System context: [`docs/architecture.md`](../docs/architecture.md) ·
AWS setup: [`docs/runbook-notifications.md`](../docs/runbook-notifications.md)

## Components and the fan-out that justifies SNS

```mermaid
flowchart TB
    SFN["Step Functions · NotifyPurchaseConfirmed<br/>publishes JSON via JSONata $string()"]
    T{{"purchase-confirmed<br/>SNS topic"}}

    SFN --> T
    T -->|"subscription 1"| QN(["notifications-queue"])
    T -->|"subscription 2"| SESD["SES — plain email subscription"]

    subgraph svc["notification-service — ECS Fargate task"]
        PCC["PurchaseConfirmedConsumer<br/>SmartLifecycle"]
        PUMP["common · SqsMessagePump"]
        PCM["PurchaseConfirmationMailer<br/>renders subject + body"]
        NSI["NotificationSender — interface"]
        LOG["LoggingNotificationSender<br/>local + test default"]
        SES["SesNotificationSender<br/>SES v2"]
    end

    QN --> PCC --> PUMP --> PCM --> NSI
    NSI --> LOG
    NSI --> SES --> SESD
    QN -.->|"maxReceiveCount"| DLQ(["notifications DLQ"])

    N["One event, two independent deliveries,<br/>neither aware of the other. An SQS queue alone<br/>would have forced this service to also become<br/>the thing that emails a human directly."] -.- T
```

The `NotificationSender` split mirrors `PaymentGateway`/`MockPaymentGateway` in
checkout — same reasoning, so neither local runs nor tests need the real provider.

## Two configuration traps, drawn

```mermaid
flowchart LR
    subgraph raw["Raw message delivery"]
        A["SNS subscription<br/><b>raw delivery ON</b>"] --> B["body is the event JSON"] --> C["deserialises correctly"]
        D["<b>raw delivery OFF</b>"] --> E["body wrapped in SNS's envelope"] --> F["PurchaseConfirmedEvent of all nulls<br/>— <b>not</b> a parse error"] --> G["an email addressed to nobody<br/>about seat null, reported as success"]
    end

    classDef bad fill:#7a2f2f,stroke:#4d1d1d,color:#fff;
    class D,E,F,G bad;
```

```mermaid
flowchart LR
    H["customerRef"] -->|"contains @"| I["send there — keeps a real<br/>end-to-end run honest"]
    H -->|"opaque ref, e.g. cust-saga-1"| J["fallbackRecipient —<br/>the verified sandbox identity"]
    K["SES sandbox: sender <i>and</i> every recipient<br/>must be a verified identity, so an unverified<br/>address fails the call rather than bouncing later.<br/>A thrown exception leaves the message on the queue —<br/>which is the better failure."] -.- J
```

## The accepted duplicate

```mermaid
sequenceDiagram
    participant Q as SQS
    participant NS as notification-service
    participant SES as SES
    participant P as A person

    Q->>NS: PurchaseConfirmedEvent
    NS->>SES: sendEmail
    SES-->>P: confirmation
    Note over Q,NS: delete call fails / visibility timeout lapses
    Q->>NS: same event again
    NS->>SES: sendEmail
    SES-->>P: <b>second identical email</b>
```

SES has no idempotency key. This is **not** papered over with an in-memory guard, which
would be a dedupe that lasts exactly as long as one task and lies about the rest. The
honest fix is a `notifications_sent` table with a unique key on `orderId`, written before
the send — deliberately deferred, because it means giving this service a database, and a
duplicate confirmation email is a far cheaper defect than a duplicate ticket or a
duplicate charge.

## What the mail deliberately does not contain

```mermaid
flowchart LR
    A["payment CAPTURED"] --> B["purchase-confirmed published"] --> C["email sent"]
    A --> D["ticket issued"] --> E["QR rendered to S3"]

    C -.->|"links to"| F["GET /orders/{id}/tickets<br/>on ticket-issuance-service"]

    N["The email fires upstream of issuance and rendering:<br/>the ticket may not exist yet, and a presigned URL minted<br/>here would point at an object that is not in the bucket<br/>and would expire before anyone opened it. So the mail links<br/>to the service that actually knows whether the QR is ready —<br/>which is also what real ticketing does: confirmation is<br/>immediate, the ticket follows."] -.- C
```
