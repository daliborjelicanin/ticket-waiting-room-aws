# Runbook: Alarms

Every alarm on `ops-alarms` links one section of this page (D16). An alarm exists only if a
human has to *do* something when it fires, so each section says what it means, where to look,
and what to do. Recoveries are sent to the same address (`ok_actions`), so an alarm that
clears on its own still leaves a pair of emails behind.

The alarms in this file are the **foundation** ones (`infra/modules/observability`). They run
even with no session up; with nothing running they stay `OK`, because every one of them treats
missing data as not breaching. The session alarms (saga, ALB 5xx, unhealthy or missing tasks)
are added with the session stack.

Where to look, for all of them:

- **Dashboard** `twr-dev` (CloudWatch → Dashboards).
- **Saved queries** under `twr-dev/` (CloudWatch → Logs Insights → Saved queries):
  `service-errors`, `admission-publish-failures`, `lambda-cold-starts`,
  `lambda-record-failures`, `hold-cleanup-runs`, `socket-handshake-failures`.

## A DLQ is not empty

**Means:** a message gave up. Its consumer failed it `maxReceiveCount` times (3 behind a
service, 5 behind a Lambda), or, for `…-to-…-dlq`, SNS could not deliver it to the queue at all.
Nothing retries it any more.

**Look:** the consumer's errors around the time the message first arrived. For a service, the
`service-errors` query on that service's group; for a Lambda, `lambda-record-failures`. Then
read the message itself without consuming it for good:

```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10 \
  --message-attribute-names All --visibility-timeout 30
```

**Do:** fix the cause first. Replaying into a consumer that still fails just returns the
message to the DLQ. Then:

| DLQ | Replay | Why it is safe, or not |
|---|---|---|
| `ticket-issuance-dlq` | redrive | issuance is idempotent on `UNIQUE (order_id, seat_id)` |
| `qr-generation-dlq` | redrive | the key is derived from the ticket id; a replay rewrites the same bytes |
| `purchase-confirmed-notifications-dlq` | redrive | the buyer may get the email twice, never zero times |
| `queue-admissions-dlq` | usually **drop** | the frame carries `admissionExpiresAt`; once past, the buyer has already fallen back to polling, and a late "you're in" is wrong |
| `purchase-confirmed-to-purchase-confirmed-notifications-dlq` | **re-publish** to the topic | SNS failed to deliver, so there is no source queue to redrive to. Fix the queue policy or the key grant first (D11): this DLQ is what both look like when they are wrong |

Redrive moves every message back to the queue it came from:

```bash
aws sqs start-message-move-task --source-arn <dlq-arn>
aws sqs list-message-move-tasks --source-arn <dlq-arn>   # progress
```

The alarm clears once the DLQ is empty again.

## A queue is stuck

**Means:** the oldest message on a main queue is older than three times its visibility
timeout, so its consumer has stopped taking messages, long before anything reaches the DLQ.
The timeouts are derived in `modules/messaging` (qr-generation 360 s, queue-admissions 180 s,
purchase-confirmed-notifications 120 s, ticket-issuance 30 s).

**Look:**

- **Behind a Lambda** (`qr-generation`, `queue-admissions`): is the event source mapping
  `Enabled`? A teardown once left one disabled for a whole slice (retro §3).
  `aws lambda list-event-source-mappings --function-name <function> --query 'EventSourceMappings[].State'`.
  Then `lambda-record-failures` and the function's throttles on the dashboard.
- **Behind a service** (`ticket-issuance`, `purchase-confirmed-notifications`): is the task
  running? With no session up, nothing consumes these queues. A message sent to them outside a
  session (a manual test) will trip this alarm, and that is a correct alarm.

**Do:** restart what stopped: re-enable the mapping, or bring the service up. The backlog drains
on its own, and the alarm clears when the oldest message is young again.

## A Lambda: errors or throttles

**Means:** one of the three functions reported `Errors + Throttles > 0` over five minutes.

- `hold-cleanup` failing: expired holds are not being reclaimed, so those seats stay `HELD` and
  unsellable. Worse, TTL is not a fallback here: when it fires on a `HELD` seat (lazily, within
  about 48 hours) it deletes **the whole seat item**, so a long cleanup outage loses seats from
  the event (`runbook-hold-cleanup.md`). This one is urgent during a sale.
- `qr-generation` failing: tickets are issued without a QR. The messages retry, then go to the
  DLQ.
- `queue-notifier` failing or throttled: admitted buyers are not told over the socket. They
  fall back to polling, which is the stampede the socket exists to prevent.

**Look:** `lambda-record-failures` for per-record failures (these are reported, not thrown, so
they do not count as `Errors`; a function error means the handler itself threw or timed out).
`lambda-cold-starts` for `initTimeouts`: the 10 s INIT limit was hit once on a first deploy
(2026-11-16, `qr-generation`, 9 999 ms).

**Do:** a throttle at the account's concurrency limit means a burst bigger than the account
allows, which is a capacity decision, not a bug. An error is a bug or a permission. Check the
function's role against `modules/iam/lambda.tf` before changing code: most Phase 1 Lambda
failures were permissions (retro §4).

## Admission publish failures

**Means:** `waiting-room-service` admitted tokens in Redis and then failed to put the admission
on `queue-admissions`. It counts the failure and carries on rather than undoing the admission,
so the buyer **holds a place they were never told about** and learns of it only by polling.

**Look:** `admission-publish-failures`: each line names the event and how many tokens were
admitted. The exception that follows says why: usually the role's `sqs:SendMessage` or the
key's `kms:GenerateDataKey` (D11).

**Do:** fix the permission, or the queue. Admissions already made are not re-sent; their buyers
are served by the REST poll until their admission expires. Record the count if it was during a
load test: those buyers are part of the run's numbers.
