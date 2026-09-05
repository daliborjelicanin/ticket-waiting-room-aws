# Runbook — Ticket Issuance (SQS consumer + RDS)

Slice 5's first half: the consumer that finally reads `ticket-issuance-queue`, which the
saga has been writing to since slice 4 with nothing on the other end.

**Status: built and tested locally; not yet stood up on AWS.** The Results section is
empty on purpose — fill it in from the session, the way slices 1–4 did.

## Why This Exists

Slice 4 ends with a message on a queue and a topic with one email subscriber. Nothing
consumes either. This service turns a captured payment into the thing the customer actually
bought.

## The Concurrency Bit

**SQS Standard is at-least-once, and the saga retries `EnqueueTicketIssuance` four times.**
Duplicate delivery is not an edge case to defend against — it is ordinary operation, and it
has to survive two consumer tasks processing two copies of one message at the same instant.

The guarantee is one line of DDL:

```sql
CONSTRAINT uq_tickets_order_seat UNIQUE (order_id, seat_id)
```

`TicketIssuanceService.issue` attempts the insert and catches the violation, rather than
calling `findByOrderIdAndSeatId` first. A read-then-write is two statements with a window
between them, and two consumers racing through that window both see "no ticket yet" and both
insert. The constraint is evaluated at a point where no window exists. Same argument as
`ux_payments_one_live_per_order` in slice 4, and the same payoff: it holds against every
writer, including a second task nobody has deployed yet.

Two consequences worth stating:

- **The insert and the QR enqueue are not atomic.** A crash between them leaves a ticket
  with no image. The redelivery is the repair — nothing deletes the message until the
  handler returns, so the message comes back, the insert loses to the constraint, and the
  QR request is issued from the `AlreadyIssued` branch. That branch is a recovery path,
  not a no-op, which is why `qrDispatcher.requestQr` sits outside the switch.
- **`SqsMessagePump` deletes only after the handler returns.** Deleting on receipt would
  make the queue at-most-once and lose the work on any crash. A thrown handler leaves the
  message for the visibility timeout and, after `maxReceiveCount`, the DLQ.

`TicketIssuanceIT` proves all of it against real Postgres and real SQS (LocalStack),
including 20 concurrent deliveries of one message producing exactly one ticket.

## Why Postgres And Not DynamoDB

A ticket is the transactional record of a paid order, which is the same side of the split as
orders and payments. The rule it needs — "at most one ticket per seat per order" — is a
statement about rows, which is what a unique constraint is for and what a key-value store
would force every writer to re-implement correctly.

It gets its **own database** (`ticketing`) on the same instance rather than a table in
`checkout`: separate bounded context, separate deployable, separate Flyway history. The cost
is stated rather than hidden — there is no foreign key from `tickets.order_id` to
`orders.id`, so the database cannot refuse a ticket for an order that does not exist. The
saga is what guarantees it does: nothing enqueues an issuance message until a payment was
captured against that order.

## AWS Resources To Create

### 1. DLQ and redrive on the issuance queue

The DLQ slice 4 deferred, now that something consumes the queue.

**SQS → Create queue**, Standard, `ticket-issuance-dlq`, retention **14 days** (a DLQ wants
the longest retention available — it exists to be read by a human, days later).

Then on `ticket-issuance-queue` → **Edit → Dead-letter queue → Enabled**, set to
`ticket-issuance-dlq` with **Maximum receives = 3**.

Three, not one: the transient failures here (a database restarting, a redeployed task) clear
on their own, and a message that fails three times over three visibility timeouts is a
message that will not succeed by being tried again.

### 2. QR generation queue

**SQS → Create queue**, Standard, `qr-generation-queue`, **visibility timeout 60s** (Lambda
cold start plus render, with room), plus `qr-generation-dlq` at 14-day retention and
maximum receives 3.

### 3. S3 bucket

**S3 → Create bucket**, `ticket-qr-<account-id>-eu-central-1` (bucket names are globally
unique, hence the account id).

| Setting | Value |
|---|---|
| Block *all* public access | **ON** |
| Encryption | SSE-KMS with the **AWS-managed** `aws/s3` key |
| Lifecycle rule | expire objects after 7 days |
| Tags | `project=ticket-waiting-room` |

The AWS-managed key, not a customer-managed one: a CMK is **$1/month standing** and this
phase's whole discipline is that nothing bills while idle. AWS-managed keys are free; only
the requests are billed, at $0.03 per 10,000.

Public access stays blocked because downloads are served as **presigned URLs**. A signature
scoped to one key for 15 minutes is what grants a customer their own ticket and nothing else.

### 4. `ticketing` database

On the existing RDS instance (see `runbook-checkout-rds.md` for bringing it up):

```sql
CREATE DATABASE ticketing;
```

Flyway creates the schema on first start. The service reuses the RDS-managed master secret
through the **execution** role, exactly as checkout does, so it needs no database credentials
in its task role.

### 5. IAM task role

`ticket-issuance-task-role`, inline policy `TicketIssuance`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ConsumeIssuance", "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:ticket-issuance-queue" },
    { "Sid": "RequestQrGeneration", "Effect": "Allow",
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:qr-generation-queue" },
    { "Sid": "PresignQrDownloads", "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::ticket-qr-<account-id>-eu-central-1/tickets/*" },
    { "Sid": "ReadQrEncryption", "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "*",
      "Condition": { "StringEquals": { "kms:ViaService": "s3.eu-central-1.amazonaws.com" } } }
  ]
}
```

**No `s3:PutObject`.** This service never writes a QR image — the Lambda does — and IAM makes
that structurally impossible rather than merely unintended. Same reasoning as the cleanup
Lambda having no `DeleteItem` in slice 2.

`s3:GetObject` is needed even though nothing here downloads anything: a presigned URL carries
the signer's permissions, so a role that cannot read the object cannot mint a URL that can.

### 6. ECR + task definition

```bash
export JAVA_HOME=/home/boka/.sdkman/candidates/java/26.0.1-tem
export DOCKER_HOST=unix:///var/run/docker.sock

./gradlew :ticket-issuance-service:build
docker build -t ticket-issuance-service:local ticket-issuance-service

aws ecr create-repository --repository-name ticket-issuance-service --region eu-central-1 \
  --tags Key=project,Value=ticket-waiting-room
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/ticket-issuance-service
docker tag ticket-issuance-service:local "${REPO}:latest"
docker push "${REPO}:latest"
```

Task definition environment — these names are read by `application.yml`, so a typo is a
service that starts and then polls nothing:

| Variable | Value |
|---|---|
| `AWS_REGION` | `eu-central-1` |
| `ISSUANCE_QUEUE_URL` | the `ticket-issuance-queue` URL |
| `QR_QUEUE_URL` | the `qr-generation-queue` URL |
| `QR_BUCKET` | `ticket-qr-<account-id>-eu-central-1` |
| `DB_URL` | `jdbc:postgresql://<rds-endpoint>:5432/ticketing` |
| `DB_USERNAME` / `DB_PASSWORD` | from the RDS-managed secret, via the execution role |

`ISSUANCE_CONSUMER_ENABLED` defaults to `true` and only needs setting to run the HTTP surface
without polling.

## Verify

Run a full saga execution (`runbook-checkout-saga.md`, happy path input), then:

```bash
ISS=http://<issuance-task-ip>:8080

aws sqs get-queue-attributes --region eu-central-1 --queue-url <issuance-queue-url> \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
# -> 0 / 0 : consumed and acknowledged

curl -s $ISS/orders/<orderId>/tickets | jq
```

Then the part that is actually the point — replay the same purchase and confirm the ticket
count does not move:

```bash
aws sqs send-message --region eu-central-1 --queue-url <issuance-queue-url> \
  --message-body '<the exact body from the first execution>'

curl -s $ISS/orders/<orderId>/tickets | jq 'length'   # -> still 1
```

And the invariant, in SQL against the real instance:

```sql
SELECT order_id, seat_id, count(*) FROM tickets GROUP BY 1, 2 HAVING count(*) > 1;
-- 0 rows
```

## Results (2026-09-05)

Order `75e15447-1ab2-4db8-8084-9c8272395b45`, ticket `fbd49307-774b-4945-8fbc-526e2cd30e5c`,
seat `S-1` of `evt-c1-0905`.

### First delivery

```
11:22:03.892  INFO [virtual-42] TicketIssuanceConsumer :
  issued ticket fbd49307-… for order 75e15447-… seat S-1
```

Flyway created the schema on first start against the `ticketing` database — `Successfully
applied 1 migration to schema "public", now at version v1` — and the pump came up on the
full queue URL with two consumer threads. QR object landed at 11:22:08, unattended.

### The constraint under a real race — 21 deliveries, one ticket

`TicketIssuanceIT` proves this against LocalStack. This is the same claim against the real
deployment: one replay, then **20 more sent as two concurrent `send-message-batch` calls**,
so the copies arrive together rather than in sequence. A sequential replay does not test a
race, and the claim being made here is about one.

| Check | Result |
|---|---|
| `GET /orders/{orderId}/tickets` | **count = 1** |
| `issuedAt` | `11:22:02.627267Z` — unchanged; the original row was never rewritten |
| `SELECT order_id, seat_id, count(*) … HAVING count(*) > 1` | **0 rows** |
| `SELECT count(*) FROM tickets` | **1** |
| `ticket-issuance-dlq` | **0** |

The race is visible in the log — `virtual-40` and `virtual-42` are the two consumer threads,
interleaved inside the same second:

```
11:44:37.001  virtual-42  duplicate delivery for order 75e15447-… seat S-1 —
                          ticket fbd49307-… already issued
11:44:37.002  virtual-40  HHH000247: ErrorCode: 0, SQLState: 23505
11:44:37.003  virtual-40  ERROR: duplicate key value violates unique constraint
                          "uq_tickets_order_seat"
              Detail: Key (order_id, seat_id)=(75e15447-…, S-1) already exists.
11:44:37.097  virtual-42  HHH000247: ErrorCode: 0, SQLState: 23505
```

Both threads attempted the insert concurrently, both were refused by the constraint, both
reported the ticket already issued, and both deleted their message. **`23505` being raised
twenty times is not a failure — it is the mechanism.** A `findByOrderIdAndSeatId` guard would
have let both threads through the window between the read and the write.

### Nothing reached a DLQ, and that is the subtle part

Twenty-one messages whose inserts all failed, and `ticket-issuance-dlq` stayed at 0. The
handler treats a constraint violation as success and deletes the message — which is the
correct reading of "already issued", and the reason the DLQ stays meaningful: anything that
does land there is a real failure, not a duplicate.

### The `AlreadyIssued` branch is a recovery path — confirmed

Every duplicate re-issued its QR request, and the S3 object was rewritten with byte-identical
content (same key, same 1205 bytes, new timestamp). That is `qrDispatcher.requestQr` sitting
outside the switch, doing exactly what the *Concurrency Bit* section says it is there for.

The honest cost is 21 renders for one ticket — real Lambda invocations, and at scale you would
notice. The alternative is a guard this design deliberately rejects, so it is a chosen trade.

### Caveat

The 30s visibility timeout was never stressed: every handler returned in milliseconds, so the
"a restarting database puts every in-flight message in the DLQ" warning under *DLQ and redrive*
remains reasoned rather than measured.

## Teardown

The queues, DLQs, bucket, ECR image and IAM role are **$0 standing** and stay. The ECS
service goes, and RDS goes with `runbook-checkout-rds.md`'s teardown.

The bucket is the one thing to keep an eye on: objects are billed, so the 7-day lifecycle
rule is doing the teardown work here. Verify it exists rather than assuming it.

## Notes for Phase 2 (Terraform)

- `aws_sqs_queue` with `redrive_policy` on the source queue and `redrive_allow_policy` on the
  DLQ — the second one is easy to omit and is what stops any queue naming that DLQ as its own.
- The `ticketing` database wants `aws_db_instance` plus a provisioner or a migration job;
  Flyway from the service on startup is fine at this scale but is not a deploy strategy.
- The `kms:ViaService` condition on `kms:Decrypt` is not boilerplate — without it the role can
  decrypt with that key through any service.
