# Runbook — QR Generation (SQS → Lambda → S3)

Slice 5's second Lambda, and the second container-image function in this project. Everything
structural about the packaging is inherited from `runbook-hold-cleanup.md` — read that one
first; this records only what differs.

**Status: built and tested locally; not yet stood up on AWS.** Results section left empty
for the session.

## Why A Lambda And Not Part Of The Service

Rendering is slow, failable work that nothing is waiting on. Behind a queue it gets retries,
a dead-letter queue and back-pressure for free, and a rendering failure never becomes an
issuance failure. Doing it inline in `ticket-issuance-service` would put image encoding on
the consumer's critical path and couple the ticket's existence to a PNG.

## The Message Body Is A Bare Ticket Id

Not JSON. The S3 key and the encoded verification URL are both **derived** from the ticket
id via `TicketQrObjects`, so there is nothing else to carry — which means this function
needs no JSON parser on its classpath.

That is not minimalism for its own sake. Slice 2 measured Lambda's separate **10-second INIT
budget** being blown by class loading alone, and fixed it by dropping two HTTP stacks. Every
dependency kept off this classpath is cold-start time not spent. The cost is honest: a
message in the DLQ carries no context for a human reading it, which is why the ticket id is
echoed in every log line the handler writes.

## Idempotency Needs No Mechanism

The key is `tickets/{ticketId}.png` — deterministic — and `QrRenderer` is pure: no clock, no
randomness, no metadata in the PNG. A redelivered message therefore rewrites byte-identical
content to the same key.

So there is **no conditional put and no HeadObject** before writing. Both would add a request
and a race in order to skip an operation that is already harmless. `QrGenerationIT` pins this
by rendering twice and comparing bytes.

## Partial Batch Failures

The handler returns `SQSBatchResponse` with per-record `batchItemFailures` rather than
throwing. Throwing fails the *whole* batch: SQS redelivers all ten messages, nine of which
succeeded, so one unparseable id would re-render nine images on every attempt and drag its
batch-mates to the DLQ with it.

**This only works if the event source mapping has `ReportBatchItemFailures` enabled.**
Without it Lambda ignores the return value entirely and reports the whole invocation as a
success — which silently *drops* the failures instead of retrying them. That is strictly
worse than not implementing partial failures at all, and it is a checkbox.

## Build & Deploy

```bash
export JAVA_HOME=/home/boka/.sdkman/candidates/java/26.0.1-tem
export DOCKER_HOST=unix:///var/run/docker.sock

./gradlew :qr-generation-lambda:build          # runs stageLambdaLib into build/lambda/lib
docker build -t qr-generation:local qr-generation-lambda

aws ecr create-repository --repository-name qr-generation --region eu-central-1 \
  --tags Key=project,Value=ticket-waiting-room
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/qr-generation
docker tag qr-generation:local "${REPO}:latest"
docker push "${REPO}:latest"

aws lambda update-function-code --function-name qr-generation --region eu-central-1 \
  --image-uri "${REPO}:latest"
aws lambda wait function-updated-v2 --function-name qr-generation --region eu-central-1
```

**`update-function-code` is not optional.** Lambda *copies* the image at deploy time — a new
push to `:latest` does not reach a running function, unlike ECS which pulls on restart.

## Function Configuration

| Field | Value |
|---|---|
| Name | `qr-generation` |
| Package type | Container image, `x86_64` |
| Memory / timeout | 1024 MB / 60 s |
| Env | `QR_BUCKET=ticket-qr-<account-id>-eu-central-1` |
| Env | `TICKET_VERIFY_BASE_URL=https://<issuance front door>` |
| Execution role | `ticket-qr-generation-lambda-role` |
| Log group | `/aws/lambda/qr-generation`, **3-day** retention |
| Tags | `project=ticket-waiting-room` |

**Set the execution role explicitly.** Lambda's create form collapses *Change default
execution role* and quietly attaches a generated logs-only role — slice 2 lost time to
exactly this, surfacing two steps later as an `AccessDenied` that reads like a policy bug
rather than "wrong role".

`TICKET_VERIFY_BASE_URL` is baked into every image rendered, so a QR generated against a
session-scoped front door encodes a URL that dies with it. Fine for a demo; worth knowing
before wondering why an old ticket's QR scans to nothing.

### Execution role

`ticket-qr-generation-lambda-role` — `AWSLambdaBasicExecutionRole` (without it there are
**no logs at all**: no group, no stream, no error) plus:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ConsumeQrJobs", "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:qr-generation-queue" },
    { "Sid": "WriteQrImages", "Effect": "Allow",
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::ticket-qr-<account-id>-eu-central-1/tickets/*" },
    { "Sid": "EncryptQrImages", "Effect": "Allow",
      "Action": ["kms:GenerateDataKey", "kms:Encrypt"],
      "Resource": "*",
      "Condition": { "StringEquals": { "kms:ViaService": "s3.eu-central-1.amazonaws.com" } } }
  ]
}
```

**No `s3:GetObject` and no `s3:DeleteObject`.** This function writes images and nothing else;
IAM makes reading or deleting a customer's ticket structurally impossible rather than merely
unintended. Same discipline as the cleanup Lambda having no `DeleteItem` in slice 2.

The `kms:GenerateDataKey` grant is easy to forget with SSE-KMS and fails at the *put*, not at
startup — an error that names KMS while the obvious suspect is the bucket policy.

### Event source mapping

**Lambda → Configuration → Triggers → Add trigger → SQS**

| Field | Value |
|---|---|
| Queue | `qr-generation-queue` |
| Batch size | 10 |
| Batch window | 0 |
| **Report batch item failures** | **ON** — see above |
| Maximum concurrency | leave empty — setting it disables Lambda's scale-down-to-two poller optimisation, so the one field that reads like a cost control raises the idle cost |

The queue's **visibility timeout must be 360s, not 60s** — an earlier version of this runbook
said 60. AWS is explicit for event source mappings: set the source queue's visibility timeout
to at least *six times* the function timeout, and this function's timeout is 60s.

Sixty passes validation, because Lambda only rejects a function timeout *greater* than the
visibility timeout, so nothing tells you it is wrong. What it costs is headroom: a throttled
or slow invoke lets the message reappear while the first is still rendering. The duplicate
render is harmless — the key is deterministic and the renderer pure — but every redelivery
increments the receive count, and enough of them push a message that *succeeded* into the DLQ.

For the same reason `maxReceiveCount` here is **5**, not the 3 used elsewhere: a throttled
invoke consumes a receive without the message ever being processed.

## Test The Image Before Pushing

Same RIE approach as `runbook-hold-cleanup.md`, with an SQS-shaped payload:

```bash
umask 077
aws configure export-credentials --format env-no-export > /tmp/aws.env
printf 'AWS_REGION=eu-central-1\nQR_BUCKET=ticket-qr-<account-id>-eu-central-1\nTICKET_VERIFY_BASE_URL=https://example.test\n' >> /tmp/aws.env

docker run -d --name qr-rie -p 9101:8080 -v ~/.aws-lambda-rie:/aws-lambda --env-file /tmp/aws.env \
  --entrypoint /aws-lambda/aws-lambda-rie qr-generation:local \
  java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -cp '/var/task/lib/*' \
  com.amazonaws.services.lambda.runtime.api.client.AWSLambda \
  'com.ticketwaitingroom.qrgeneration.QrGenerationHandler::handleRequest'

curl -s -XPOST localhost:9101/2015-03-31/functions/function/invocations \
  -d '{"Records":[{"messageId":"m1","body":"11111111-1111-4111-8111-111111111111"}]}'

docker rm -f qr-rie && rm -f /tmp/aws.env
```

**The RIE does not enforce the INIT budget**, so this cannot reveal a cold start that is too
slow. Check `INIT_REPORT` in CloudWatch after the first real invoke — that is the only place
the number appears.

## Verify

```bash
aws s3 ls s3://ticket-qr-<account-id>-eu-central-1/tickets/
aws s3 cp s3://ticket-qr-<account-id>-eu-central-1/tickets/<ticketId>.png /tmp/qr.png
```

**Decode the payload rather than only looking at the image.** Confirming that an object was
written is a different test from confirming what it encodes, and only the first was ever run
before 2026-09-05 — which is how a QR pointing at a non-existent endpoint survived a full
Phase A sign-off. There is no decoder on the dev box; borrow one:

```bash
docker run --rm -v /tmp:/d debian:stable-slim sh -c \
  'apt-get update -qq && apt-get install -y -qq zbar-tools && zbarimg -q --raw /d/qr.png'
# -> https://<issuance-api>.execute-api.eu-central-1.amazonaws.com/tickets/<ticketId>
```

Then curl whatever comes back. It must not 404 — see Results.

Then via the service, which is the path a customer takes:

```bash
curl -s https://<issuance-api>.execute-api.eu-central-1.amazonaws.com/tickets/<ticketId>/qr | jq
# -> {"status":"READY","url":"https://...X-Amz-Signature=...","expiresAt":"..."}
```

Open that URL in a browser. It must render inline rather than download — that is the
`Content-Type: image/png` on the put, without which S3 serves `application/octet-stream`.

## Results (2026-09-05)

Run against the rebuilt stack, ticket `fbd49307-774b-4945-8fbc-526e2cd30e5c`.

### Cold start — comfortably inside budget

Four cold starts, not one: the idempotency burst in `runbook-ticket-issuance.md` scaled the
function out to four execution environments, which gave a spread instead of a single sample.

| Init Duration | |
|---|---|
| 2566.40 ms | first, cold host |
| 2458.16 ms | |
| 1457.44 ms | |
| **1177.70 ms** | best |

Against slice 2's **3411 ms** and the 10-second INIT budget: the best is a **65% improvement**,
the worst still 25%. The spread is the container image cache warming on the host — the first
environment pays for the layers, later ones do not. Worth knowing before quoting a single
number: measure a cold start on a *cold host*, or you will quote the optimistic half.

Steady-state, all four invokes: `Duration ~1400–1570 ms`, `Max Memory Used 153 MB` of 1024 MB.

The memory headroom is deliberate and should not be trimmed — Lambda scales CPU with memory,
so the 1024 MB is buying INIT speed, not heap. Dropping it to fit the 153 MB actually used
would make the cold start worse, which is the one number under real pressure here.

### The object is correct

| Check | Result |
|---|---|
| Presigned URL | HTTP 200, 15-minute expiry (`X-Amz-Expires=900`) |
| `Content-Type` | `image/png` — renders inline, not a download |
| Encryption | `aws:kms`, Bucket Key enabled, AWS-managed `aws/s3` key |
| Image | valid PNG, 512×512, 1-bit grayscale, 1205 bytes |

### Idempotency held, unprompted

21 duplicate issuance deliveries re-enqueued QR generation 21 times. The object's timestamp
moved; its key, size and bytes did not. That is the "no mechanism needed" claim above being
exercised for real rather than argued: a deterministic key plus a pure renderer means a
redelivery rewrites identical bytes.

The honest cost is 21 renders for one ticket. Harmless, but they are real invocations, and
the alternative — a conditional put or a dedupe table — is what this design deliberately
rejects. A chosen trade, not an oversight.

### Defect found: the QR encodes a URL that 404s

Decoding the PNG gives:

```
https://<http-api-id>.execute-api.eu-central-1.amazonaws.com/tickets/<ticketId>
```

`TicketController` exposes `GET /orders/{orderId}/tickets` and `GET /tickets/{ticketId}/qr`.
**There is no `GET /tickets/{ticketId}`.** Every QR code generated so far points at a route
that does not exist.

Nothing is misconfigured: `TICKET_VERIFY_BASE_URL` is correct, and pointing it at the API
Gateway hostname was the right call — the hostname is stable, so these codes keep resolving
to the same place after every teardown, which was the whole reason for creating that API
early. The renderer is correct. **The handler was never written.**

Building it is its own slice. A ticket QR exists so a gate attendant can check validity, so
it needs a decision about what a scan should show and who may see it — an authorisation
question, not a rendering one. Until then, treat "the QR scans" as unproven.

## Teardown

Everything here is **$0 standing**: the function, the role, the ECR image, the queues. Only
the S3 objects are billed, and the bucket's 7-day lifecycle rule handles them.

## Notes for Phase 2 (Terraform)

- `aws_lambda_event_source_mapping` with `function_response_types = ["ReportBatchItemFailures"]`
  — the console checkbox has a name that does not obviously map to it, and omitting it
  silently disables the partial-failure handling this function is written around.
- `TICKET_VERIFY_BASE_URL` becomes the stable ALB/API Gateway hostname, at which point QR
  codes stop encoding a URL that expires with the session.
- Two roles again, and again the distinction is the lesson: this function's role is its
  identity when calling S3; the event source mapping uses the *same* role to poll SQS, unlike
  slice 2 where EventBridge Scheduler brought its own.
