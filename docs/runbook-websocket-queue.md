# Runbook — Live Queue Position (API Gateway WebSocket → DynamoDB, + SQS notifier)

Slice 6's second item, and the third container-image Lambda in this project. Everything
structural about the packaging is inherited from `runbook-hold-cleanup.md` — read that one
first; this records only what differs, plus the WebSocket API itself, which is new.

**`$connect` and `$disconnect` are not Lambdas.** Each does one unconditional DynamoDB
write against a key API Gateway already holds, so they are **AWS service integrations**
straight onto DynamoDB — no function, no image, no cold start on the one path a door-open
hits fifty thousand times in ten seconds. Only the notifier is a Lambda, because it is the
only part of this slice that has to decide anything. *Why This Exists* below carries the
argument; the short version is that a function whose whole body is a `PutItem` is a
concurrency ceiling bought for nothing.

Prerequisites: `runbook-waiting-room-redis.md` (the service, its cluster and its task
definition), `runbook-ecr-packaging.md`, `runbook-alb-apigateway.md` (the REST edge, which
stays exactly as it is — a WebSocket API is a separate API with its own id).

**Status: stood up and verified end-to-end on AWS (2026-09-06)**, with nothing synthetic
anywhere in the path — join the queue over REST, open a socket, `POST .../admit`, and the frame
arrives. ElastiCache and the Fargate task were torn down after verification; everything else is
$0 standing and left up.

## Why This Exists

A waiting client currently learns its place by polling `GET /events/{id}/queue/{token}`.
That scales in the wrong direction: the load is proportional to the number of people being
kept *out*, so 50k waiting users on a 2-second poll is 25k req/s of `ZRANK` through
ALB → Fargate → Redis, at the exact moment the system is most contended. The queue exists to
protect the backend, and polling hands the load straight back to it.

The second reason is sharper. Admission is **server-initiated** — `admit.lua` does `ZPOPMIN`
and the token has no idea — and the pass expires (`admission-duration`, `PT10M`). Poll
interval is therefore subtracted directly from a limited-duration admission window, and the
person who tabbed away comes back to a pass that already reads `EXPIRED`.

With a socket, an idle waiting client costs one connection and zero compute, and admission
latency is whatever SQS and one `postToConnection` take.

## What The Socket Does Not Do

It does not carry the opening position, and it does not count down.

`$connect` **cannot** push to its own connection: the connection does not exist until
`$connect` returns 200, so there is nothing to post to. The alternatives were to enqueue a
snapshot message — which would make the notifier read Redis, which means VPC-attaching a
Lambda to reach ElastiCache, paying ENI cold starts and NAT for it — or to let the client
take its snapshot from the REST endpoint it already calls. The second, every time.

So the division is: **REST answers "where am I", the socket announces "you're in".** That
also fixes what the socket is allowed to be — an optimisation over polling, never the source
of truth — which is what makes every best-effort choice below acceptable rather than sloppy.

## Why A Queue Between The Admit And The Push

`admit.lua`'s `ZPOPMIN` is final; it does not roll back. An inline `postToConnection` from
`waiting-room-service` would put a third party's latency on the admit path, and — worse — a
failure would be reported to the caller, whose natural retry pops a **second batch of
people** who were never told either.

`queue-admissions` moves the push into its own failure domain with its own retries and its
own DLQ. `WaitingRoomService.admitNext` publishes after the pop and swallows a publish
failure with a `WARN`, because the only honest answer to "the admission happened but the
announcement did not" is to let the client's poll find it.

One message **per token**, not per admit call. A per-call message would make the notifier's
unit of retry the whole batch, so one unreachable socket would re-notify 499 other people on
every attempt.

## The Message Carries No JSON

Body is the bare token; `eventId` and `admissionExpiresAt` ride as SQS **message
attributes**, which the SDK types on both ends. Same argument as `runbook-qr-generation.md`'s
bare ticket id — every dependency kept off a Lambda classpath is cold-start time not spent,
and message attributes cost none. The contract is named once in
`common/queue/QueueAdmissionMessage.java` so the sender and the notifier cannot drift.

The outbound frame is real JSON, serialised:

```json
{"type":"ADMITTED","eventId":"evt-1","token":"<uuid>","admissionExpiresAt":1757088000}
```

An earlier draft of this slice concatenated that string by hand and made it safe with a
`$connect` Lambda that answered **403** to any `eventId` outside `[A-Za-z0-9_-]{1,64}` or any
token that was not a UUID. Both halves are gone together, and they had to be: with
`$connect` reduced to a mapping template there is nowhere left to run a regex, so a raw quote
in a query string would have reached the notifier and come out the other side as frame
structure rather than frame content.

Serialising costs nothing here, which is what makes it the obvious answer rather than a
tradeoff. `JsonWriter` lives in `software.amazon.awssdk:json-utils`, which the DynamoDB and
API Gateway clients already pull in and already load to parse their own responses — the
staged package is **37 jars either way**. The INIT budget that justified hand-formatting was
never going to be charged twice.
`QueueSocketIT.aTokenThatWouldHaveBrokenAHandBuiltFrameIsEscapedInstead` is the regression
test: it pushes the injection the old 403 gate existed to refuse, and asserts the frame parses
back with the quote intact as *data*.

## The Connection Registry

API Gateway remembers nothing between `$connect` and the moment a server wants to push: it
hands out a `connectionId` and expects you to keep the mapping. Without the table there is no
push at all.

**DynamoDB → Create table**

| Field | Value |
|---|---|
| Name | `waiting-room-connections` |
| Partition key | `connectionId` (String) — the only thing `$disconnect` is given |
| Sort key | none |
| Capacity | On-demand |
| TTL attribute | `expiresAt` |
| Tags | `project=ticket-waiting-room` |

**Global secondary index** — `by-token`, HASH `eventId`, RANGE `token`, projection
**KEYS_ONLY**.

`KEYS_ONLY` is the whole trick, not frugality: every index projects the base table's key, and
the base key here *is* the `connectionId` the notifier is looking for. An `INCLUDE` or `ALL`
projection would pay write amplification on every connect to store attributes nobody reads.

Two consequences to accept up front:

- **The GSI read is eventually consistent.** DynamoDB offers no strongly consistent read of a
  global secondary index, so a token admitted within milliseconds of connecting can come back
  empty and get no frame. Accepted: that client polls, exactly as it would have anyway.
- **TTL is the backstop, not the mechanism.** `$disconnect` normally prunes a row, but API
  Gateway does not guarantee it fires. 24 hours, matching `waiting-room.key-ttl` — a row
  cannot usefully outlive the Redis keys that give the token meaning.

## The Queue

**SQS → Create queue**, Standard.

| Field | Value |
|---|---|
| Name | `queue-admissions` |
| Visibility timeout | **180 s** — six times the 30 s function timeout, per the event-source-mapping rule in `runbook-qr-generation.md` |
| DLQ | `queue-admissions-dlq`, `maxReceiveCount` **5** |
| Tags | `project=ticket-waiting-room` |

Five rather than three for the same reason as the QR queue: a throttled invoke consumes a
receive without the message ever being processed.

## The WebSocket API

**API Gateway → Create API → WebSocket API → Build**

| Field | Value |
|---|---|
| Name | `ticket-queue-socket` |
| Route selection expression | `$request.body.action` |
| Routes | `$connect`, `$disconnect` — **no `$default`** |
| Integrations | **AWS service** → DynamoDB (see below), not Lambda |
| Stage | `dev`, auto-deploy on |

**No `$default` route, deliberately.** This socket is server→client only; a client frame has
nowhere useful to go, and adding a route to receive one is a third function to write, deploy
and secure for no behaviour. Clients that send anything get API Gateway's own error, which is
the correct answer.

Two addresses come out of this and they are **not** the same one:

- clients connect to `wss://{api-id}.execute-api.eu-central-1.amazonaws.com/dev`
- the notifier posts to `https://{api-id}.execute-api.eu-central-1.amazonaws.com/dev`

The second is what goes in `WEBSOCKET_ENDPOINT`. It must be set as an
`endpointOverride` on the management client — the SDK's default endpoint for
`apigatewaymanagementapi` is the control plane, and posting a frame there fails in a way that
reads like a permissions problem.

### `$connect` and `$disconnect` — DynamoDB, with no function in between

Both routes do one unconditional write against a key API Gateway is already holding. Nothing
is computed, nothing is decided, and the result is ignored — which is the job description of
a mapping template, not of a function.

The reason to care is the connect storm, not the invoice. Fifty thousand sockets opening in
the first ten seconds of a door-open needs on the order of a thousand concurrent executions
of a JVM container image with a ~500 ms cold start, and **container images cannot use
SnapStart** (zip packages on managed runtimes only). Past the account burst limit `$connect`
throttles, handshakes fail, and every one of those clients falls back to polling — which is
the exact stampede this slice exists to prevent. A direct integration has no cold start and
no concurrency ceiling to hit. The Lambda saving is about **$0.15 per 50k-user event**; the
saving that matters is never having to buy provisioned concurrency at ~$15/hour to make the
function shape survive its own busiest second.

**For each route → Integration request → Integration type: AWS Service.**

| Field | `$connect` | `$disconnect` |
|---|---|---|
| AWS Region | `eu-central-1` | `eu-central-1` |
| AWS Service | DynamoDB | DynamoDB |
| HTTP method | `POST` | `POST` |
| Action | `PutItem` | `DeleteItem` |
| Execution role | `ticket-queue-socket-apigw-role` | same |
| Template selection expression | `\$default` | `\$default` |

`$connect` request template, content type `application/json`:

```vtl
#set($now = $context.requestTimeEpoch / 1000)
#set($expiresAt = $now + 86400)
{
  "TableName": "waiting-room-connections",
  "Item": {
    "connectionId": { "S": "$context.connectionId" },
    "eventId":      { "S": "$util.escapeJavaScript($input.params('eventId'))" },
    "token":        { "S": "$util.escapeJavaScript($input.params('token'))" },
    "connectedAt":  { "N": "$now" },
    "expiresAt":    { "N": "$expiresAt" }
  }
}
```

`$disconnect`:

```vtl
{
  "TableName": "waiting-room-connections",
  "Key": {
    "connectionId": { "S": "$context.connectionId" }
  }
}
```

Four things in those eleven lines are load-bearing:

- **`$util.escapeJavaScript` on both query-string values.** Without it a token containing a
  quote breaks the template's own JSON and the write fails — or worse, succeeds with a shape
  nobody intended. It escapes; it does **not** validate, which is why the notifier serialises
  its frame rather than formatting it.
- **`requestTimeEpoch / 1000`.** That context variable is **milliseconds**. DynamoDB TTL reads
  epoch *seconds* and silently ignores anything else, so a millisecond value here produces a
  table that looks correctly configured and never expires a row. `ConnectionTableSchema`
  carries the same warning next to the constant.
- **`{ "N": "..." }` with the digits in a string.** DynamoDB's wire format quotes numbers.
  `{"N": 123}` is rejected.
- **No condition expression.** `connectionId` is minted by API Gateway and never reused, so
  there is nothing to race, and one token legitimately holds several rows when someone opens
  several tabs.

### The response side, which is where this actually goes wrong

The request half of this worked first time. The response half took four attempts and cost the
whole debugging budget of the session, so it is written out in full.

A non-proxy WebSocket route needs **three** things beyond the integration, and missing any of
them produces the same symptom: `Unexpected server response: 500` at the client, **with the
DynamoDB write already committed**. That is the nastiest part — the side effect happens, the
row is correct, and the handshake still fails, so scanning the table tells you everything is
fine while nothing works.

1. **An integration response keyed `/200/`, not `$default`.** An AWS service integration is
   created with `IntegrationResponseSelectionExpression = ${integration.response.statuscode}`
   — read it back with `get-integration` and you will see it. Selection is therefore by status
   code, and a `$default` key **can never match**. This is the trap: creating the integration
   response succeeds, the console shows one, and it is silently unreachable.
2. **A response template, selected by `--template-selection-expression '\$default'`.**
   Without it DynamoDB's `{}` body passes through to a `$connect` handshake that must not
   carry one. Setting the response template alone is not enough — the selection expression
   has to be set too, or the template is as unreachable as the response in (1) was.
3. **A route response**, plus `--route-response-selection-expression '$default'` on the route.

**Bisect with a MOCK integration** if this bites. Repointing `$connect` at
`--integration-type MOCK` with `--request-templates '{"$default": "{\"statusCode\":200}"}'`
separates "route/stage/route-response wiring is wrong" from "the DynamoDB response mapping is
wrong" in one test. It is what found (2) here, after (1) and (3) had each been fixed without
the handshake improving.

Execution logging would have answered this in one look, but it needs an account-level
CloudWatch role for API Gateway that this account does not have (`aws apigateway get-account`
→ `cloudwatchRoleArn: None`). Setting one up is a prerequisite worth doing before the next
API Gateway slice, not during one.

### The commands, as run

The console can do all of this, but the templates are multi-line VTL and the response wiring
is four resources deep, so the CLI is both faster and re-runnable after a teardown. Templates
live in files; `--request-templates` wants a JSON map, built here with `python3 -c` rather
than fought with in shell quoting.

```bash
API=$(aws apigatewayv2 create-api --name ticket-queue-socket --protocol-type WEBSOCKET \
  --route-selection-expression '$request.body.action' --tags project=ticket-waiting-room \
  --region eu-central-1 --query ApiId --output text)

ROLE=arn:aws:iam::<account-id>:role/ticket-queue-socket-apigw-role
python3 -c 'import json,sys; print(json.dumps({"$default": open(sys.argv[1]).read()}))' \
  connect-request.vtl > /tmp/connect-templates.json

CONNECT=$(aws apigatewayv2 create-integration --api-id $API --region eu-central-1 \
  --integration-type AWS \
  --integration-uri 'arn:aws:apigateway:eu-central-1:dynamodb:action/PutItem' \
  --integration-method POST --credentials-arn $ROLE --passthrough-behavior NEVER \
  --template-selection-expression '\$default' \
  --request-templates file:///tmp/connect-templates.json \
  --query IntegrationId --output text)

# The three response resources. Skipping any one of them = 500 with the row written.
aws apigatewayv2 create-integration-response --api-id $API --integration-id $CONNECT \
  --region eu-central-1 --integration-response-key '/200/' \
  --template-selection-expression '\$default' --response-templates '{"$default": ""}'

ROUTE=$(aws apigatewayv2 create-route --api-id $API --region eu-central-1 \
  --route-key '$connect' --target "integrations/$CONNECT" --query RouteId --output text)
aws apigatewayv2 update-route --api-id $API --route-id $ROUTE --region eu-central-1 \
  --route-response-selection-expression '$default'
aws apigatewayv2 create-route-response --api-id $API --route-id $ROUTE \
  --region eu-central-1 --route-response-key '$default'

aws apigatewayv2 create-stage --api-id $API --region eu-central-1 --stage-name dev \
  --auto-deploy --tags project=ticket-waiting-room
```

`$disconnect` is the same with `action/DeleteItem`, the disconnect template, and route key
`$disconnect`.

**The route returns whatever DynamoDB returns.** A failed `PutItem` fails the handshake with a
500, not a 403 — there is no place in a mapping template to run the old UUID check. That is
the right trade here (authenticating a token is an authorizer's job, queued for slice 6.3),
but it means a malformed token now produces an *open socket nobody will ever address* rather
than a refused one. It costs one row and one TTL; it is written down because it is a real
behaviour change, not because it needs fixing.

**Nothing in this repo writes that item**, which removes the compile error that used to catch
a renamed attribute. `QueueSocketIT.theRowShapeTheMappingTemplateWritesIsTheOneTheLookupReads`
is what replaces it: it seeds a row using these attribute names as *literals* and asserts the
notifier's GSI query finds it. Change this template without changing that test and the build
goes red.

## Function Configuration

One function.

| Function | Handler (image `CMD`) | Memory / timeout | Env |
|---|---|---|---|
| `ticket-queue-notifier` | `com.ticketwaitingroom.queuesocket.AdmissionNotifierHandler::handleRequest` | 1024 MB / 30 s | `CONNECTION_TABLE_NAME`, `WEBSOCKET_ENDPOINT` |

Container image, `x86_64`, log group `/aws/lambda/ticket-queue-notifier` at **3-day**
retention, tag `project=ticket-waiting-room`.

**1024 MB for a function that posts a few frames.** Sized at 512 MB first, which was wrong for
the reason `runbook-qr-generation.md` already records: Lambda scales CPU with memory, so the
memory is buying INIT speed rather than heap. Sizing to the handful of megabytes actually used
would make the cold start worse. Measure before trimming, and measure the *cold* number.

This is also the function whose cold start matters least in the whole project, which is the
point of the shape. It is invoked once per **batch of ten admissions**, so a 50k-person
door-open is ~5,000 invocations rather than 50,000 — the INIT cost is amortised across a
batch instead of being paid per socket. That is exactly why it can afford a real JSON
serialiser and `$connect` could not.

**Set the execution role explicitly.** Lambda's create form collapses *Change default
execution role* and quietly attaches a generated logs-only role — slice 2 lost time to
exactly this, surfacing two steps later as an `AccessDenied` that reads like a policy bug
rather than "wrong role".

### Execution roles — two, and only one of them is a Lambda's

Different identities for different jobs: API Gateway may write the registry and nothing else;
the notifier may read it and post frames, and cannot create a row at all.

`ticket-queue-socket-apigw-role` — **trust policy `apigateway.amazonaws.com`**, not
`lambda.amazonaws.com`. This is the role named in both integrations' *Execution role* field,
and getting the trust wrong produces an integration that fails with an opaque 500 and no
CloudWatch entry to explain it.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "MaintainConnectionRegistry", "Effect": "Allow",
      "Action": ["dynamodb:PutItem", "dynamodb:DeleteItem"],
      "Resource": "arn:aws:dynamodb:eu-central-1:<account-id>:table/waiting-room-connections" }
  ]
}
```

Note what this role does **not** have: no `dynamodb:GetItem`, no `Query`, no `Scan`. The
integration writes and forgets, so a template edited into reading the table fails rather than
quietly exposing who else is waiting.

`ticket-queue-notifier-lambda-role` — `AWSLambdaBasicExecutionRole` (without it there are
**no logs at all**: no group, no stream, no error) plus:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "ConsumeAdmissions", "Effect": "Allow",
      "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:queue-admissions" },
    { "Sid": "FindSockets", "Effect": "Allow",
      "Action": ["dynamodb:Query", "dynamodb:DeleteItem"],
      "Resource": [
        "arn:aws:dynamodb:eu-central-1:<account-id>:table/waiting-room-connections",
        "arn:aws:dynamodb:eu-central-1:<account-id>:table/waiting-room-connections/index/by-token"
      ] },
    { "Sid": "PushFrames", "Effect": "Allow",
      "Action": "execute-api:ManageConnections",
      "Resource": "arn:aws:execute-api:eu-central-1:<account-id>:<api-id>/dev/POST/@connections/*" }
  ]
}
```

**The index needs its own ARN.** A `dynamodb:Query` grant on the table alone does not cover a
query against `.../index/by-token`, and the failure is an `AccessDeniedException` naming the
index — easy to read as "the index is wrong" rather than "the policy is short one resource".

`execute-api:ManageConnections` is the permission for pushing, and it is scoped to this API
and stage. Note what neither role has: **no `dynamodb:Scan`**, so nothing here can enumerate
who is waiting; and the notifier has **no `PutItem`**, so a bug in it cannot invent a
connection.

### Event source mapping

**`ticket-queue-notifier` → Configuration → Triggers → Add trigger → SQS**

| Field | Value |
|---|---|
| Queue | `queue-admissions` |
| Batch size | 10 |
| Batch window | 0 |
| **Report batch item failures** | **ON** |
| Maximum concurrency | leave empty — setting it disables Lambda's scale-down-to-two poller optimisation, so the one field that reads like a cost control raises the idle cost |

Without *Report batch item failures* Lambda ignores the returned `SQSBatchResponse` entirely
and treats every invocation as a blanket success, which silently **drops** failures rather
than retrying them — strictly worse than not implementing partial failures at all.

## The Waiting Room Changes Too

`waiting-room-service` now makes an AWS API call, which it never did before. Both halves of
that are console work:

- **Task role** — `ticket-waiting-room-task-role`, trust policy `ecs-tasks.amazonaws.com`,
  one statement: `sqs:SendMessage` on
  `arn:aws:sqs:eu-central-1:<account-id>:queue-admissions`. Set it as the **task role** in a
  new task-definition revision; the execution role stays `ticket-ecs-execution-role`.
- **Environment variable** — `ADMISSION_QUEUE_URL` =
  `https://sqs.eu-central-1.amazonaws.com/<account-id>/queue-admissions`.

Leaving `ADMISSION_QUEUE_URL` unset is a supported state, not a broken one: the service falls
back to `LoggingAdmissionPublisher` and runs with no AWS at all, which is how it runs locally.
The trap is that it is also **silent** — an admission that should have been pushed is a log
line, not an error. If frames stop arriving, check this variable before checking the socket.

`docs/runbook-waiting-room-redis.md` said "no task role — this service makes no AWS API
calls". That is now superseded, and the note there says so.

## Build & Deploy

```bash
export JAVA_HOME=/home/boka/.sdkman/candidates/java/26.0.1-tem
export DOCKER_HOST=unix:///var/run/docker.sock

./gradlew :queue-socket-lambda:build          # runs stageLambdaLib into build/lambda/lib
docker build -t queue-socket:local queue-socket-lambda

aws ecr create-repository --repository-name queue-socket --region eu-central-1 \
  --tags Key=project,Value=ticket-waiting-room
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/queue-socket
docker tag queue-socket:local "${REPO}:latest"
docker push "${REPO}:latest"

aws lambda update-function-code --function-name ticket-queue-notifier --region eu-central-1 \
  --image-uri "${REPO}:latest"
aws lambda wait function-updated-v2 --function-name ticket-queue-notifier --region eu-central-1
```

**`update-function-code` is not optional.** Lambda copies the image at deploy time, so a new
push to `:latest` does not reach a running function on its own — the tag moves and the
function stays exactly where it was.

Verify the classpath has not regrown the HTTP stacks the INIT budget cannot afford:

```bash
./gradlew :queue-socket-lambda:dependencies --configuration compileClasspath | grep -Ei 'apache|netty'
# -> no output
```

## Test The Image Before Pushing

Same RIE approach as the other two Lambdas, on **port 9102** (9100 and 9101 are taken).

```bash
export DOCKER_HOST=unix:///var/run/docker.sock

docker run -d --name queue-socket-rie -p 9102:8080 \
  -e AWS_REGION=eu-central-1 -e AWS_ACCESS_KEY_ID=local -e AWS_SECRET_ACCESS_KEY=local \
  -e CONNECTION_TABLE_NAME=waiting-room-connections \
  -e WEBSOCKET_ENDPOINT=https://example.execute-api.eu-central-1.amazonaws.com/dev \
  -v ~/.aws-lambda-rie:/aws-lambda --entrypoint /aws-lambda/aws-lambda-rie queue-socket:local \
  java --enable-native-access=ALL-UNNAMED -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -cp '/var/task/lib/*' com.amazonaws.services.lambda.runtime.api.client.AWSLambda \
  'com.ticketwaitingroom.queuesocket.AdmissionNotifierHandler::handleRequest'

curl -s -XPOST localhost:9102/2015-03-31/functions/function/invocations \
  -d '{"Records":[{"messageId":"m-1","body":"11111111-2222-3333-4444-555555555555",
       "messageAttributes":{"eventId":{"stringValue":"evt-1","dataType":"String"},
       "admissionExpiresAt":{"stringValue":"1757088000","dataType":"Number"}}}]}'
# -> {"batchItemFailures":[{"itemIdentifier":"m-1"}]}

docker logs queue-socket-rie 2>&1 | grep 'INIT REPORT'
docker rm -f queue-socket-rie
```

**The batch item failure is the pass, not a failure.** There is no DynamoDB reachable here, so
the record cannot be processed — and the function reporting that one record while returning
200 is precisely the `ReportBatchItemFailures` contract working. A thrown exception or an
empty `batchItemFailures` would both be bugs. So this one call proves INIT, handler dispatch,
SQS event deserialisation, message-attribute reading and the partial-failure protocol, with no
AWS credentials involved at all.

**The RIE does not enforce the INIT budget**, so this cannot reveal a cold start that is too
slow. Check `INIT_REPORT` in CloudWatch after the first real invoke — that is the only place
the number appears.

## Verify End-To-End

```bash
API=<api-id>
TOKEN=$(curl -s -XPOST https://<rest-api>/dev/events/evt-1/queue | jq -r .token)

# Connect and leave it open in one terminal
npx wscat -c "wss://${API}.execute-api.eu-central-1.amazonaws.com/dev?eventId=evt-1&token=${TOKEN}"

# The registry row should appear immediately, written by the mapping template
aws dynamodb scan --table-name waiting-room-connections --region eu-central-1 \
  --projection-expression 'connectionId,eventId,#t,expiresAt' \
  --expression-attribute-names '{"#t":"token"}'
# Check expiresAt is ~10 digits (epoch SECONDS), not 13 (milliseconds) — a 13-digit
# value is accepted on write and then ignored by TTL forever.

# In another terminal — the admit that should produce a frame
curl -s -XPOST https://<rest-api>/dev/events/evt-1/queue/admit \
  -H 'content-type: application/json' -d '{"count":1}'
# -> the wscat terminal prints
#    {"type":"ADMITTED","eventId":"evt-1","token":"...","admissionExpiresAt":...}
```

**Check the template before anything else.** It is the one piece of this slice with no local
test — there is no LocalStack for API Gateway mapping templates — so the scan above is where a
typo in `$connect` surfaces. If the row is absent, read the API's execution log before
suspecting the notifier; if the row is present but the frame never arrives, the template is
fine and the problem is downstream.

Then the two things that are easy to skip and are the actual claims:

- **Close the wscat session and admit again.** The row must disappear — the `$disconnect`
  integration firing — and the notifier must log no 410 at all. Then delete a row by hand while
  a socket is open and admit: the notifier should log `pruned closed connection=...` and still
  report success.
- **Open the same token in three tabs and admit once.** Three frames, one message. That is
  the fan-out claim, and one tab passing proves nothing about it.

Measure the number worth recording: time from the `admit` response to the frame arriving,
against the poll interval it replaces.

## Results

### Stood up on AWS, 2026-09-06 — the connection half

Account `<account-id>`, `eu-central-1`, API id `<websocket-api-id>`. Verified with a `ws` client
against the real endpoint, not a simulation:

| Claim | Result |
|---|---|
| The `$connect` template writes the item the notifier reads | **Row correct on the first try** — `connectionId`, `eventId`, `token` all as expected |
| TTL is epoch **seconds**, not milliseconds | `connectedAt` 10 digits; `expiresAt` − `connectedAt` = **86400** exactly |
| `$util.escapeJavaScript` stops injection | `eventId=evt", "injected":"yes` stored as literal data; **no `injected` attribute created** |
| Two tabs, one token → two rows | 2 rows, distinct `connectionId`s |
| The KEYS_ONLY GSI answers the notifier's question | `Query(eventId, token)` → **count 2**, both `connectionId`s, no projected payload |
| `$disconnect` prunes | table `Count` back to **0** within ~3 s of both sockets closing |

The escaping result is the one worth dwelling on: it is the exact scenario
`QueueSocketIT.aTokenThatWouldHaveBrokenAHandBuiltFrameIsEscapedInstead` covers, confirmed
end-to-end. A hostile `eventId` **does** reach DynamoDB as data, so the notifier reading that
row genuinely has to serialise its frame rather than format it. The decision to drop the
hand-formatted string was not theoretical.

What it cost to run all of this: **under $0.01**, and $0 standing, so it is still up.

### Stood up on AWS, 2026-09-06 — the notifier half

Verified by putting a synthetic message on `queue-admissions` with a real socket open — the
whole path except `waiting-room-service`'s own publish:

| Claim | Result |
|---|---|
| Event source mapping → notifier → frame | **`{"type":"ADMITTED","eventId":"evt-1","token":"...","admissionExpiresAt":1788698504}`** arrived at the client, `admissionExpiresAt` matching the sent attribute exactly |
| Fan-out: two tabs, one message | **both** tabs received the frame from a single admission |
| A 410 is news, not failure | orphan row pruned, `admission notify complete records=1 failures=0`, DLQ **0** |
| `ReportBatchItemFailures` reaches Lambda | a failing record returned `failures=1` and was retried rather than dropped |

**Cold start is 5.3× the local number, not 2×.**

| | Local RIE | Real Lambda |
|---|---|---|
| INIT | 538 ms | **2849 ms** |
| Invoke | 345 ms | 1372 ms (cold), 127 ms (warm) |
| Max memory used | — | **147 MB of 1024 MB** |

`runbook-qr-generation.md` measured roughly a 2× spread between a warm host and a cold one, and
this runbook repeated that as the expected shape. It is wrong here: 2849 ms against a 538 ms
RIE reading. Still comfortably inside the 10-second INIT budget, but the margin is a third of
what the local number suggested, and **an RIE measurement is not a forecast of anything**. Take
the first real `INIT_REPORT` before believing a cold-start claim.

The memory line is the other half of that story: 147 MB used of 1024 MB provisioned. The
memory is not there for heap, it is there for the CPU share that Lambda scales with it — which
is exactly the argument for not trimming this to 256 MB because "it only uses 147".

### The whole path, 2026-09-06

`waiting-room-service` on Fargate Spot (`waiting-room-service:2`, image `b249bbb`) against a
real ElastiCache node, publishing to `queue-admissions` under `ticket-waiting-room-task-role`:

| Step | Result |
|---|---|
| `POST /events/evt-live-1/queue` | `{"status":"WAITING","position":1}` |
| Socket opened for that token | 1 row via the `$connect` mapping template |
| `POST /events/evt-live-1/queue/admit` | `{"admitted":1,"admissionExpiresAt":"2026-09-06T12:58:48Z"}` |
| Frame at the browser | **`{"type":"ADMITTED","eventId":"evt-live-1","token":"82834735-...","admissionExpiresAt":1788699528}`** |
| Batch: 3 tokens, 3 sockets, **one** admit call | **3 frames, each to the right socket**, identical `admissionExpiresAt` |
| Publish failures | none — no `WARN` from `WaitingRoomService.announce` |
| Notifier | `records=1 failures=0` ×4, DLQ **0** |

The batch case is the one worth having: it is `SqsAdmissionPublisher`'s `SendMessageBatch` path
against real SQS, delivering one message per token, and it confirms the fan-out routes by token
rather than broadcasting — each socket got its own token and no one else's.

### Local, 2026-09-06 — measured before deploying

| Check | Result |
|---|---|
| `QueueSocketIT` | 8 tests green against LocalStack DynamoDB — template row shape, frame escaping, prune-on-410, partial batch failure, 50-tab fan-out |
| `AdmissionAnnouncementTest` | 3 tests green — publish per token, and an admission that survives a publisher throwing |
| `SqsAdmissionPublisherIT` | 2 tests green against LocalStack SQS — 500 tokens across 50 concurrent batches arrive exactly once |
| Staged deployment package | **37 jars** — no `apache5-client`, no `netty-nio-client` |
| Image | 355 MB on `eclipse-temurin:26-jre` |
| RIE INIT (`AdmissionNotifierHandler`) | **538 ms** |
| RIE invoke (partial batch failure returned) | 345 ms |

Well inside the 10-second INIT budget.

**37 jars is the same count as before the frame was serialised**, which is the measurement
worth keeping: `json-utils` was already on the classpath as a transitive of the DynamoDB and
API Gateway clients, so declaring it and calling `JsonWriter` added nothing to the image. The
earlier draft's hand-formatted string was avoiding a cost that was already paid.

The RIE runs on a warm host with the image already local, so this is the optimistic half of
the distribution; `runbook-qr-generation.md` measured a 2× spread between a cold host and a
warm one. Treat it as a floor, not a forecast.

Two functions and their tests are **gone** rather than green: `ConnectHandler`,
`DisconnectHandler`, and the four cases that covered them. What they did is now a mapping
template with no local test at all — see *Verify End-To-End*, where checking it moved.

## Traps Hit While Building This

**`$connect` cannot push to its own connection.** The obvious design — send the position in
the connect handler — cannot work, because the connection is not established until the
handler returns 200. Discovering this after building the Redis path into the Lambda would
have meant VPC-attaching it for nothing.

**A `dynamodb:Query` grant on a table does not cover its index.** The index needs its own
ARN in the policy, and the resulting `AccessDeniedException` names the index, which reads
like a broken index rather than a short policy.

**The management endpoint is not the default endpoint.** `ApiGatewayManagementApiClient`
built without an `endpointOverride` points at the control plane, so `postToConnection` fails
against a URL that has nothing to do with the socket.

**Kotlin block comments nest — Java's do not.** Inherited from `runbook-hold-cleanup.md` and
still true: the wildcard classpath in a `/* ... */` inside `build.gradle.kts` silently
swallows the rest of the script. The staging task uses line comments.

**A missing `ADMISSION_QUEUE_URL` is silent by design.** The service logs the admission and
carries on. Correct behaviour, and a genuinely confusing failure mode if you forget which
half of the system is not configured.

**A mapping template has no compiler and no local test.** Moving `$connect` off Lambda traded
a compile error for a deploy-time one: rename an attribute in `ConnectionTableSchema` and
nothing breaks until a frame silently fails to arrive. `QueueSocketIT`'s row-shape test is the
replacement, and it only works because it spells the attribute names out as literals instead
of importing the constants.

**A 500 handshake with the write already committed.** The response wiring failed three
different ways while the `PutItem` succeeded every time — see *The response side*. Worth
internalising as a shape: in a service integration the side effect and the client's answer are
separate failure domains, so "the data looks right" is not evidence the route works. The
Lambda version could not fail this way, because there the write and the return value were the
same function returning once. That is a genuine thing given up along with the handler, and it
was not on the list when the trade was made.

**The request template was right first time; the response wiring took four attempts.** The
part with no compiler and no test was not the part that broke. Worth remembering before
trusting an intuition about where the risk in a change lives.

**A malformed `connectionId` is a 400, not a 410.** Testing the prune path with an invented id
proves nothing: API Gateway answers `ApiGatewayManagementApiException: Invalid connectionId
(Status Code: 400)`, which the handler correctly treats as retryable, so the record goes back
to the queue rather than pruning anything. Only an id API Gateway actually minted, whose socket
has since closed, returns `GoneException`. To test it properly: open a socket, capture its
`connectionId` from the table **while it is open**, let it close, then re-insert that row by
hand — that is a well-formed id pointing at a dead connection.

Worth knowing what the 400 path costs if a corrupt row ever does appear: five receives, each
one re-pushing the frame to every *healthy* socket for that token, and then the DLQ. Harmless
by design — a duplicate `ADMITTED` frame is news received twice — but it is waste on a
permanent failure. It cannot arise from normal operation now that API Gateway writes the rows
itself; it is listed because the only way it happened here was someone editing the table by
hand, which is also how it would happen in production.

### Predicted but not hit

Duplicate frames from SQS redelivery, which the design accepts. An `ADMITTED` frame carries
news of a transition rather than a transition, so receiving it twice is not observable.

## Teardown

Everything here is **$0 standing** except the DynamoDB table, which is on-demand and holds
kilobytes — so unlike the ElastiCache and RDS slices, this one does not have to come down
between sessions. See *What It Costs*.

```bash
# The hourly pair. Everything below this is $0 standing and can stay.
aws ecs stop-task --cluster ticket-waiting-room --task <task-arn> --region eu-central-1
aws elasticache delete-cache-cluster --cache-cluster-id ticket-waiting-room-cache --region eu-central-1

aws lambda delete-function --function-name ticket-queue-notifier --region eu-central-1
aws apigatewayv2 delete-api --api-id <api-id> --region eu-central-1
aws sqs delete-queue --queue-url https://sqs.eu-central-1.amazonaws.com/<account-id>/queue-admissions
aws dynamodb delete-table --table-name waiting-room-connections --region eu-central-1
```

The ECR repository and the two IAM roles cost nothing and are worth keeping between sessions.
The WebSocket API's id changes on every recreate, which means `WEBSOCKET_ENDPOINT` and the
notifier's `execute-api` ARN both have to be reset — the same session-scoped-hostname problem
`runbook-qr-generation.md` hit with `TICKET_VERIFY_BASE_URL`.

## What It Costs

Approximate eu-central-1 rates; check the calculator before quoting them anywhere.

**Nothing here bills while idle.** No hourly charge on the WebSocket API, the DynamoDB table
(on-demand), SQS or Lambda — the whole slice is per connection-minute, per message, per
request. That puts it with `hold-cleanup` and `qr-generation` rather than with ElastiCache
(~$0.017/hr), the ALB (~$0.027/hr) or RDS (~$0.022/hr), and it is why *Teardown* is optional
for this one.

| | Dev session (a few tabs, an hour) | Load test (1,000 sockets, 10 min, 1,000 admits) | Real on-sale (50,000 users) |
|---|---|---|---|
| API GW connection-minutes | $0.00 | $0.003 | $0.22 |
| API GW messages | $0.00 | $0.003 | $0.17 |
| DynamoDB writes | $0.00 | $0.006 | $0.32 |
| SQS | free tier | free tier | $0.01 |
| Lambda (notifier) | $0.00 | $0.001 | $0.05 |
| Logs | ~$0.01 | ~$0.01 | ~$0.02 |
| **Total** | **~$0.01** | **~$0.02** | **~$0.79** |

A full fifty-thousand-person door-open costs **under a dollar**. Two things in that table are
worth knowing rather than deriving:

- **The GSI doubles the DynamoDB bill.** A `PutItem` into a table carrying one GSI is **two**
  write request units, not one, so connect + disconnect for 50k users is 200,000 WRU — the
  largest line in the table. It is the price of the KEYS_ONLY lookup and it is the right
  trade, but it is not obvious from the design.
- **Connection-minutes bill for idle sockets.** A tab left open bills until API Gateway drops
  it: 10 minutes idle, 2 hours maximum. Worst case per abandoned tab is ~$0.00003, which is
  why it does not matter here — and why the uncapped fan-out below is a cost concern as well
  as a delivery one.

**What the direct integration saves is not the invoice.** Keeping `$connect`/`$disconnect` as
Lambdas would add about **$0.15** to that 50k event, which is nothing. The saving is that the
function shape has a cold-start problem whose only fix is provisioned concurrency —
**~$15/hour** at 1,000 concurrent × 1 GB, standing, whether anyone connects or not, because
container images cannot use SnapStart. The cheap path also stays cheap only while `$connect`
keeps up: throttled handshakes push clients back to polling, and polling is billed in Fargate
tasks, ALB requests and Redis.

## What Would Change For Production

Phase 1 is a manual-console learning exercise and this slice is built to be one. Three of
these decisions would go the other way under real load — recorded here so the Phase 2 rebuild
is not a straight transcription of the Phase 1 shape.

### Settled: `$connect`/`$disconnect` are not Lambdas — decided 2026-09-06

This section used to argue that they should not be, and the argument is now above, in *The
WebSocket API*. Kept here as a record of the reasoning and of what it cost, because the second
half is the part worth remembering.

The change deleted two handlers, their four tests, and the 403 validation gate — and with the
gate gone the hand-formatted frame had to go too, since the two were propping each other up:
the formatting was cheap only because a per-connection Lambda validated its inputs, and the
validation was justified only by the formatting. Removing the function dissolved both. That is
the useful shape of the lesson: a workaround and its justification usually fall together, and
if only one of them falls you have probably moved the problem rather than solved it.

What it cost is real and is not hidden: the write path left the test suite. A renamed attribute
was a compile error and is now a deploy-time surprise, covered by one test that spells the
template's attribute names out as literals. That trade is defensible because nothing on that
path *decides* anything — but it would not be defensible for a path that did.

The rejected-connection behaviour changed too, and slice 6.3 is where it should be answered
properly: a malformed token now opens a socket instead of being refused, and authenticating
the token is an authorizer's job rather than a regex's.

### The fan-out is uncapped

Nothing limits how many sockets one token may register. Ten thousand connections on one
token is a ten-thousand-way push on a single admission, billed and delivered. Production needs
a per-token cap and WAF in front of the WebSocket API — the anti-scalping requirement in
`CLAUDE.md` applies to a socket that can be opened in a loop just as much as to the purchase
endpoint.

**This got harder, not easier, with the direct integration**, and that is the honest cost of
the previous section. A cap is a *decision* — read the current count, compare, refuse — and a
mapping template has nowhere to make one. The options are a conditional write against a
counter item (expressible in VTL, but the failure has to surface as a refused handshake),
a Lambda authorizer on `$connect` that does the counting, or WAF rate-limiting by IP and
accepting that it does not cap per *token*. Worth settling before Phase 2 rather than during
it.

### The swallowed publish failure needs an alarm

`WaitingRoomService.admitNext` logs and continues when the announcement cannot be published,
which is the correct behaviour — `ZPOPMIN` does not roll back, so failing the admit would
make the retry pop a second batch of people. But **silent by design still needs to be
visible**: a metric filter on that WARN and an alarm behind it, or the only evidence that
admissions stopped being pushed is a log line nobody is reading. Slice 7 is where that
belongs.

### The client contract is an assumption

A frame sent to a socket that has dropped is gone — there is no replay and no resume. The
REST poll covers it, but only for a client that actually polls on reconnect. That behaviour
is currently assumed of a client that does not exist yet; production would specify it and
test it.

## Notes for Phase 2 (Terraform)

- `aws_apigatewayv2_api` with `protocol_type = "WEBSOCKET"` and
  `route_selection_expression = "$request.body.action"`, then `aws_apigatewayv2_route` per
  route.
- `$connect`/`$disconnect` are `aws_apigatewayv2_integration` with `integration_type = "AWS"`,
  `integration_uri = "arn:aws:apigateway:eu-central-1:dynamodb:action/PutItem"` (and
  `DeleteItem`), `credentials_arn` pointing at `ticket-queue-socket-apigw-role`, and the VTL
  in `request_templates`. Each needs an `aws_apigatewayv2_integration_response` alongside it
  — the piece most easily forgotten, because the route looks complete without one.
- **Put the mapping templates in files**, `templates/connect.json.vtl` read through
  `file()`, not heredocs inline in the resource. They are the only logic in this slice with no
  compiler behind them, so they should at least be diffable on their own.
- One `aws_lambda_function` for the notifier. The `aws_lambda_permission` for
  `apigateway.amazonaws.com` that this list used to warn about is no longer needed by any
  route — nothing invokes a function from the API any more.
- `aws_dynamodb_table` with `global_secondary_index { projection_type = "KEYS_ONLY" }` and
  `ttl { attribute_name = "expiresAt", enabled = true }` — TTL defaults to disabled, and a
  disabled TTL is invisible until the table has been growing for a week.
- `aws_lambda_event_source_mapping` with
  `function_response_types = ["ReportBatchItemFailures"]`.
- `WEBSOCKET_ENDPOINT` interpolates from the API's own `api_endpoint` (swapping `wss` for
  `https`), which removes the manual reset after every teardown.
- The per-token cap is the open question, not a detail: see *The fan-out is uncapped*. If it
  lands as a Lambda authorizer, `$connect` grows an `aws_apigatewayv2_authorizer` and a second
  function comes back — so settle it before writing the module, because it changes what the
  module's inputs are.
