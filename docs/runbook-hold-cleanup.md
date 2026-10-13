# Runbook — Expired Hold Cleanup (EventBridge → Lambda)

Slice 2: the scheduled job that returns abandoned seat holds to the pool. Built by hand on
2026-08-22 — ECR repo, container-image Lambda, EventBridge schedule — and verified against
the real `seat-inventory` table in `eu-central-1`.

## Why This Exists

`runbook-seat-inventory.md` documents the two DynamoDB TTL properties that make this job a
**correctness requirement rather than housekeeping**:

- **TTL is lazy** ("typically within 48 hours"). An expired hold stays readable, and the
  seat therefore stays un-holdable, long after the hold lapsed. `SeatRepository.holdSeat`
  requires `status = AVAILABLE`, so an abandoned checkout removes a seat from inventory
  indefinitely.
- **TTL deletes the whole item, not the hold.** When it does fire on a `HELD` seat it
  erases the seat row rather than resetting it, so inventory silently shrinks.

`confirmPurchase` already defends *itself* with `holdExpiresAt > :now`, so nobody can buy a
stale hold. Nothing else reclaims the seat. That is this Lambda's entire job.

## What Exists

| Resource | Value |
|---|---|
| Lambda function | `hold-cleanup` (`eu-central-1`) |
| Package type | Container image, `x86_64` |
| ECR repository | `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/hold-cleanup` |
| Memory / timeout | 1024 MB / 30 s |
| Env | `SEAT_TABLE_NAME=seat-inventory` |
| Execution role | `ticket-hold-cleanup-lambda-role` |
| Log group | `/aws/lambda/hold-cleanup`, **3-day** retention |
| Schedule | `hold-seat-schedule`, `rate(1 minutes)`, flexible window OFF |
| Schedule invoke role | `Amazon_EventBridge_Scheduler_LAMBDA_374c074501` (auto-created) |
| Tags | `project=ticket-waiting-room` |

**Standing cost: $0.** 43,200 invokes/month against a 1M-request free tier, ~43,000 GB-s
against 400,000 free. The image is 135 MB compressed in ECR (see the free-tier note in
`runbook-ecr-packaging.md` — two images now, ~275 MB of 500 MB).

## The Concurrency Bit

The scan that finds an expired hold is a **stale read** by the time the reclaim write
lands. `ExpiredHoldRepository.reclaim` therefore re-checks everything at write time in a
single conditional `UpdateItem`:

```
UPDATE    SET #status = :available REMOVE #holdId, #ttl
CONDITION #status = :held AND #holdId = :holdId AND #ttl <= :now
```

- `#status = :held` — never resurrect a seat that has since been SOLD.
- `#holdId = :holdId` — the hold the scan saw is still the hold on the item. Without this,
  a seat that expired and was legitimately re-held in the intervening milliseconds would
  have the *new* owner's hold torn up.
- `#ttl <= :now` — re-check expiry against a fresh clock.

**A `ConditionalCheckFailedException` here is normal operation, not an error.** It means a
purchase won the race, and the seat is counted as `lostRace` rather than retried. This is
also why the schedule runs with retries disabled: the next tick *is* the retry.

`HoldCleanupIT` proves this against LocalStack, including `cleanupAndPurchaseCannotBothWin`,
which races 50 reclaims against 50 confirms with **deliberately skewed clocks** — the
cleanup's clock says the hold expired, the checkout's says it hasn't, so both conditions
evaluate true and the race is genuine rather than decided by construction. Exactly one wins.

The Lambda's IAM policy grants `Scan` and `UpdateItem` only. **No `DeleteItem`** — the job
reclaims seats, and IAM makes deleting one structurally impossible rather than merely
unintended.

## Build & Deploy

```bash
export JAVA_HOME=/path/to/jdk   # any JDK 17+ launches Gradle; the build provisions its own 26
export DOCKER_HOST=unix:///var/run/docker.sock

./gradlew :hold-cleanup-lambda:build          # runs stageLambdaLib into build/lambda/lib
docker build -t hold-cleanup:local hold-cleanup-lambda

aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/hold-cleanup
docker tag hold-cleanup:local "${REPO}:latest"
docker push "${REPO}:latest"

aws lambda update-function-code --function-name hold-cleanup --region eu-central-1 \
  --image-uri "${REPO}:latest"
aws lambda wait function-updated-v2 --function-name hold-cleanup --region eu-central-1
```

**`update-function-code` is not optional.** Lambda *copies* the image at deploy time — a
new push to `:latest` does not reach a running function, unlike ECS which pulls on restart.

### Why a container image, not a zip

Lambda offers a managed `java25` runtime, which would have meant a zip upload and no ECR
repo at all. The container image was chosen to keep the JDK 26 toolchain used everywhere
else in the repo. That is a one-version gain for a meaningfully longer deploy loop — worth
recording honestly as a judgement call, not an obvious win. The Dockerfile documents the
consequences: no managed base image means the **runtime interface client** must be supplied
as a dependency and invoked as the `ENTRYPOINT`, and JDK 26 needs
`--enable-native-access=ALL-UNNAMED` because the RIC loads its transport via `System.load`.

## Test The Image Before Pushing

The AWS base images bundle the Runtime Interface Emulator; `eclipse-temurin` does not, so
it is downloaded once to `~/.aws-lambda-rie/`:

```bash
curl -Lo ~/.aws-lambda-rie/aws-lambda-rie \
  https://github.com/aws/aws-lambda-runtime-interface-emulator/releases/latest/download/aws-lambda-rie
chmod +x ~/.aws-lambda-rie/aws-lambda-rie

umask 077
aws configure export-credentials --format env-no-export > /tmp/aws.env
printf 'AWS_REGION=eu-central-1\nSEAT_TABLE_NAME=seat-inventory\n' >> /tmp/aws.env

docker run -d --name hc-rie -p 9100:8080 -v ~/.aws-lambda-rie:/aws-lambda --env-file /tmp/aws.env \
  --entrypoint /aws-lambda/aws-lambda-rie hold-cleanup:local \
  java --enable-native-access=ALL-UNNAMED -cp '/var/task/lib/*' \
  com.amazonaws.services.lambda.runtime.api.client.AWSLambda \
  'com.ticketwaitingroom.holdcleanup.HoldCleanupHandler::handleRequest'

curl -s -XPOST localhost:9100/2015-03-31/functions/function/invocations -d '{}'
docker rm -f hc-rie && rm -f /tmp/aws.env
```

This catches entrypoint and handler-resolution errors, which otherwise surface only after
a push as `Runtime.InvalidEntrypoint`. **It does not catch everything** — see the INIT
budget below.

## Verify End-To-End

The real test is that an abandoned hold is reclaimed **with no manual invoke**:

```bash
PAST=$(( $(date +%s) - 60 ))
aws dynamodb put-item --region eu-central-1 --table-name seat-inventory --item \
"{\"pk\":{\"S\":\"EVENT#evt-d3\"},\"sk\":{\"S\":\"SEAT#Z-9\"},\"eventId\":{\"S\":\"evt-d3\"},\
\"seatId\":{\"S\":\"Z-9\"},\"status\":{\"S\":\"HELD\"},\"holdId\":{\"S\":\"hold-abandoned\"},\
\"holdExpiresAt\":{\"N\":\"$PAST\"}}"

# wait for a tick, then:
aws dynamodb get-item --region eu-central-1 --table-name seat-inventory \
  --key '{"pk":{"S":"EVENT#evt-d3"},"sk":{"S":"SEAT#Z-9"}}' --consistent-read
```

Verified 2026-08-22, schedule enabled, no manual invocation:

```
17:47:22Z  seeded Z-9, holdExpiresAt 60s in the past
17:47:33Z  status=HELD
17:47:54Z  status=HELD
17:48:15Z  status=AVAILABLE      <- reclaimed ~53s later
```

Controls in the same run: a live hold stayed `HELD`, and a `SOLD` seat carrying a stale TTL
stayed `SOLD`. CloudWatch for the same window:

```
Summary[expiredFound=0, reclaimed=0, lostRace=0]
Summary[expiredFound=1, reclaimed=1, lostRace=0]
Summary[expiredFound=0, reclaimed=0, lostRace=0]
```

The reclaimed item must have **both** `holdId` and `holdExpiresAt` gone — a lingering TTL
attribute on an AVAILABLE seat would leave it scheduled for deletion.

## Traps Hit While Building This

Every one of these was silent. None produced an error at the point of the mistake.

**Lambda's INIT phase has its own 10-second budget**, separate from and unaffected by the
function timeout. The first deploy logged `INIT_REPORT Init Duration: 9999.50 ms Phase: init
Status: timeout`; Lambda recovered by re-running initialisation inside the invoke, so the
function *worked* while wasting ~10s on every cold start. The fix was replacing the AWS
SDK's default HTTP stacks — Apache 5 for sync, Netty for async, 15 jars, neither used —
with `url-connection-client`, a thin wrapper over the JDK's `HttpURLConnection` with no
transitive dependencies. 48 jars → 34, init 9999 ms → **3411 ms**. Class loading is the
dominant JVM cold-start cost on Lambda. **The RIE does not enforce the INIT budget**, so
local testing cannot reveal this.

**`LambdaLogger.log()` writes no line terminator, and a final unterminated write is
discarded when the invocation ends.** The handler's summary line was absent from CloudWatch
entirely while the invocation reported success. Appending `System.lineSeparator()` fixes
it. Worth internalising: an invocation can report success and lose all of its diagnostic
output.

**Kotlin block comments nest — Java's do not.** A KDoc comment in
`hold-cleanup-lambda/build.gradle.kts` contained the literal path `-cp /var/task/lib/*`.
The `/*` in `lib/*` opened a *nested* comment, the closing `*/` closed only that one, and
Gradle silently swallowed the rest of the build script: the `stageLambdaLib` task simply
did not exist, while `dependencies {}` still applied. No error, no warning. Appending
deliberate garbage to the file still produced `BUILD SUCCESSFUL`, which is what finally
identified it. The file now uses line comments there.

**Lambda's create form defaults to making a new execution role**, and the *Change default
execution role* section is collapsed. Leaving it untouched attached an auto-generated
`hold-cleanup-role-<random>` with logs-only permissions instead of
`ticket-hold-cleanup-lambda-role`. This would have surfaced two steps later as
`AccessDeniedException` on `dynamodb:Scan` — which reads like a policy bug rather than
"wrong role". The stray role and its generated policy had to be deleted separately;
neither was tagged, so the tag sweep would not have found them.

**`AWSLambdaDynamoDBExecutionRole` is not the policy you want** for a Lambda that reads
DynamoDB. It grants DynamoDB *Streams* access for stream-triggered functions.
`AWSLambdaBasicExecutionRole` (logs only) plus the table-scoped inline policy is correct.

**Without `AWSLambdaBasicExecutionRole` there are no logs at all** — no log group, no
stream, no error. A scheduled function with no output and no diagnostics is the worst state
to debug from.

**zsh does not word-split unquoted parameters.** `R="--region eu-central-1"; aws dynamodb
put-item $R …` passes one argument and `aws` rejects it. Same family as the
`"${REPO}:latest"` trap in `runbook-ecr-packaging.md`.

**`aws scheduler update-schedule` replaces rather than patches.** Toggling state requires
re-specifying the expression, flexible-time-window, and the whole target block, or they are
silently reset.

## Design Notes

**Scan, not Query.** Seats are stored one partition per event with no index on status, so
there is nothing to Query — the job reads the whole table. Correct at learning scale, wrong
at real scale. The scale answer is a **sparse GSI** carrying `holdExpiresAt` only while a
seat is `HELD`, turning this into a bounded range Query. Deliberately deferred; it means
changing `SeatRepository`'s write path and redeploying the Fargate service.

**The Scan is eventually consistent** (the DynamoDB default). A hold that expires seconds
before a tick may not appear until the next one. Harmless for a job that runs every minute,
and not worth the doubled read cost of `ConsistentRead`.

**The handler ignores its input.** Work is defined entirely by table state at invocation
time, which makes the function idempotent and safe to invoke at any cadence, from any
source, including twice concurrently. `HoldCleanupIT.concurrentInvocationsReclaimEachSeatOnce`
pins that, and it is what justifies running the schedule with zero retries.

**`SeatTableSchema` lives in `common`** because the table now has two writers —
`seat-inventory-service` and this Lambda. A divergent attribute name between them is not a
compile error and not a runtime error; DynamoDB accepts any attribute name and the
condition simply never matches. `SeatStatusSchemaContractTest` pins the service's
`SeatStatus` enum to those wire values so a rename cannot silently break the Lambda.

## Teardown

**Disable the schedule.** Everything else stays at $0.

```bash
aws scheduler update-schedule --name hold-seat-schedule --region eu-central-1 --state DISABLED \
  --schedule-expression 'rate(1 minutes)' --schedule-expression-timezone 'Europe/Belgrade' \
  --flexible-time-window Mode=OFF \
  --target '{"Arn":"arn:aws:lambda:eu-central-1:<account-id>:function:hold-cleanup","RoleArn":"arn:aws:iam::<account-id>:role/service-role/Amazon_EventBridge_Scheduler_LAMBDA_374c074501","RetryPolicy":{"MaximumEventAgeInSeconds":60,"MaximumRetryAttempts":0}}'
```

Console: **EventBridge → Scheduler → `hold-seat-schedule` → Disable.** Re-enable with
`--state ENABLED` next session.

The function, both IAM roles, the ECR image, and the log group all stay. Leaving the
schedule enabled costs nothing measurable but writes a log stream every 60 seconds forever,
which is exactly the kind of drift the teardown discipline exists to prevent.

## Notes for Phase 2 (Terraform)

- `aws_scheduler_schedule` with `flexible_time_window { mode = "OFF" }` and an explicit
  `retry_policy`. The invoke role must be written by hand, including the
  `aws:SourceAccount` condition the console adds automatically — that is confused-deputy
  protection, not boilerplate.
- Two distinct roles, and the distinction is the lesson: the **function's** role is its
  identity when calling DynamoDB; the **scheduler's** role is EventBridge's identity when
  calling `lambda:InvokeFunction`. Same shape as ECS task role vs. execution role in
  slice 1, and it recurs in slice 4's Step Functions.
- `AWSLambdaBasicExecutionRole` is `Resource: "*"` for logs. Tighten to
  `arn:aws:logs:eu-central-1:<account-id>:log-group:/aws/lambda/hold-cleanup:*`.
- Set `aws_cloudwatch_log_group` retention explicitly — the implicit group Lambda creates
  defaults to *never expire*.
- Consider whether the sparse GSI lands here or stays deferred; it changes the Lambda from
  `Scan` to `Query` and touches `seat-inventory-service`'s write path.
