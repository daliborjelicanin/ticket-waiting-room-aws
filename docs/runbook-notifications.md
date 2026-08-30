# Runbook — Notifications (SNS fan-out → SQS → SES)

Slice 5's third piece: the `purchase-confirmed` topic slice 4 created finally has a second
subscriber, and something that formats an email rather than dumping a sentence into an inbox.

**Status: built and tested locally; not yet stood up on AWS.** Results section left empty
for the session.

## Why SNS At All

The saga could have written straight to an SQS queue. It publishes to a topic instead, and
this slice is where that pays: **one event, two independent subscribers** — the plain email
subscription that has been there since slice 4, and now a queue feeding this service. Neither
knows about the other, and adding a third (an SMS endpoint, an analytics sink) is a
subscription, not a change to the saga.

That is the actual justification for SNS being in this project. A single consumer would not
have earned it.

## Raw Message Delivery Is Not Optional

By default SNS wraps every payload in its own envelope — `Type`, `MessageId`, `TopicArn`,
and the real payload as a *string* inside `Message`. A consumer expecting the published JSON
then deserialises the envelope instead, and every field it wants comes back `null`.

That does not fail. It produces an email addressed to nobody about seat `null`, and reports
success.

So the SQS subscription must have **Raw message delivery = ON**. Set it *per subscription*:
turn it on for the queue and leave it **off** for the email subscription, which needs the
envelope's `Subject` to render as anything readable.

## The Saga Definition Changed

`NotifyPurchaseConfirmed` used to publish prose:

```
'Order ' & $orderId & ' confirmed: seat ' & $req.seatId & ...
```

A formatter cannot use that. It now publishes JSON, built with JSONata's `$string()`:

```
$string({ 'orderId': $orderId, 'eventId': $req.eventId, 'seatId': $req.seatId,
          'customerRef': $req.customerRef, 'gatewayRef': $payment.gatewayRef,
          'amountCents': $req.amountCents })
```

`Subject` is unchanged, so the human email subscription still reads sensibly. Only the
`Message` field differs — the state's name, retries and transitions are untouched, so slice
4's verified graph still holds. Redeploy the definition with the `sed` one-liner in
`runbook-checkout-saga.md`'s teardown section.

`TicketIssuanceContractTest` pins the published field names to `PurchaseConfirmedEvent`, so a
rename on either side is a build failure rather than an email about nothing.

## A Redelivery Sends A Second Email

SQS is at-least-once and SES has no idempotency key, so this is the one place in the project
where at-least-once is visible to a person.

It is deliberately not papered over. An in-memory guard would be a dedupe that lasts exactly
as long as one task and lies about every other case, which is worse than the duplicate it
prevents. The honest fix is a `notifications_sent` table with a unique key on `orderId`,
written before the send — the same shape as the tickets constraint. Deferred, because it
means giving this service a database, and a duplicate confirmation email is a far cheaper
defect than a duplicate ticket or a duplicate charge.

Worth stating plainly in the README's write-up: knowing which of your at-least-once paths
you have made idempotent, and which you have consciously not, is the point.

## AWS Resources To Create

### 1. Queue subscribed to the topic

**SQS → Create queue**, Standard, `purchase-confirmed-notifications`, visibility timeout 30s,
plus `purchase-confirmed-notifications-dlq` (14-day retention) with maximum receives 3.

An SES outage should retry, not discard; three failures across three visibility timeouts is
a message that needs a human.

The queue needs a policy letting SNS write to it — the console offers this when you subscribe
from the SQS side, but written by hand it is:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "sns.amazonaws.com" },
    "Action": "sqs:SendMessage",
    "Resource": "arn:aws:sqs:eu-central-1:<account-id>:purchase-confirmed-notifications",
    "Condition": { "ArnEquals": {
      "aws:SourceArn": "arn:aws:sns:eu-central-1:<account-id>:purchase-confirmed" } }
  }]
}
```

The `aws:SourceArn` condition is confused-deputy protection, not boilerplate: without it any
topic in any account can push messages into this queue.

### 2. Subscription

**SNS → `purchase-confirmed` → Create subscription**, protocol **Amazon SQS**, endpoint the
queue ARN, and **Raw message delivery = ON**.

### 3. SES identities

**SES → Identities → Create identity**, verified by email link:

- the sender address (`NOTIFICATION_FROM`)
- the recipient address (`NOTIFICATION_TO`)

**The sandbox requires both.** Sending to an unverified address fails the API call rather
than bouncing later — which is the better failure, because a thrown handler leaves the
message on the queue instead of losing it.

Production access would remove the recipient restriction and is not worth requesting for a
learning project.

### 4. IAM task role

`notification-task-role`, inline policy `Notify`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ConsumeNotifications", "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:purchase-confirmed-notifications" },
    { "Sid": "SendMail", "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": "arn:aws:ses:eu-central-1:<account-id>:identity/*",
      "Condition": { "StringEquals": {
        "ses:FromAddress": "no-reply@example.test" } } }
  ]
}
```

Replace the `ses:FromAddress` value with the verified sender. The condition is what stops
this role sending as any identity the account happens to own.

### 5. ECR + task definition

```bash
export JAVA_HOME=/home/boka/.sdkman/candidates/java/26.0.1-tem
export DOCKER_HOST=unix:///var/run/docker.sock

./gradlew :notification-service:build
docker build -t notification-service:local notification-service

aws ecr create-repository --repository-name notification-service --region eu-central-1 \
  --tags Key=project,Value=ticket-waiting-room
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/notification-service
docker tag notification-service:local "${REPO}:latest"
docker push "${REPO}:latest"
```

Environment:

| Variable | Value |
|---|---|
| `AWS_REGION` | `eu-central-1` |
| `NOTIFICATION_QUEUE_URL` | the `purchase-confirmed-notifications` URL |
| `NOTIFICATION_SENDER` | `ses` — **defaults to `logging`**, which sends nothing |
| `NOTIFICATION_FROM` | the verified sender identity |
| `NOTIFICATION_TO` | the verified recipient, used when `customerRef` is not an address |
| `TICKET_BASE_URL` | public base URL of `ticket-issuance-service` |

`NOTIFICATION_SENDER` defaulting to `logging` is the trap to remember: forget it and the
service runs, consumes the queue, deletes the messages, logs each email in full — and mails
nobody. It is the right default for local runs and the wrong one in the cloud.

## Verify

Run a full saga execution and expect **two** deliveries of one event:

1. The plain SNS email subscription — the old `Subject` line with JSON in the body.
2. A formatted mail from this service, naming the event, seat, order, amount and a link to
   `/orders/{orderId}/tickets`.

Two arriving is the fan-out working. One arriving means the subscription is missing, or the
service is on the `logging` sender.

```bash
aws sqs get-queue-attributes --region eu-central-1 \
  --queue-url <notifications-queue-url> \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
# -> 0 / 0
```

Then check the DLQ is empty — a message parked there means the payload did not match
`PurchaseConfirmedEvent`, which is almost always raw message delivery being off.

## Results

_To be filled in from the AWS session._

## Teardown

Queue, DLQ, subscription, SES identities, IAM role and ECR image are all **$0 standing**. The
ECS service goes.

The email subscription on the topic stays confirmed between sessions — worth knowing, because
a saga execution run for some other reason will still mail you.

## Notes for Phase 2 (Terraform)

- `aws_sns_topic_subscription` with `raw_message_delivery = true` on the SQS subscription
  only. It defaults to `false`, and the failure mode is silent.
- `aws_sqs_queue_policy` with the `aws:SourceArn` condition written explicitly — the console
  adds it when you subscribe from the SQS side, so it is easy not to notice it exists.
- The `notifications_sent` idempotency table, if this stops being a deliberate omission.
