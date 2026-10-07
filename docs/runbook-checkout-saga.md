# Runbook — Checkout Saga (Step Functions + SQS + SNS)

Slice 4's second half. The state machine definition lives beside this file in
`checkout-saga.asl.json`; this runbook is how it gets built, wired and proven.

The saga: **hold seat → create order → charge → confirm purchase → enqueue issuance →
notify**, with **release seat** as the compensating action when payment does not capture,
and **refund, then release** when it captured but the seat cannot be confirmed (added
2026-10-07, see *The Refund Path*).

## Two Design Points That Shape Everything

### A declined card is HTTP 200

`checkout-payment-service` answers a declined charge with `200 OK` and a body of
`{"orderStatus":"FAILED","paymentStatus":"DECLINED"}`. That is correct — the request
succeeded, the *payment* did not. But Step Functions' `Catch` only fires on errors, so a
decline sails straight past it.

Compensation is therefore driven by a **Choice** state reading `paymentStatus`, with
`Catch` handling only transport failures. Wiring compensation to `Catch` alone is the
single most likely way to build a saga that silently keeps seats held after every decline —
and it would pass a happy-path test.

### Compensation stops being "release the seat" once money moves

Before capture, the compensating action is a seat release. After capture it is a
*refund*, and only when the seat is definitively not this order's. So the failure states
are deliberately not one generic `Fail`:

| Failure state | Reached when | Correct action |
|---|---|---|
| `SeatUnavailable` | the hold itself failed | nothing to undo |
| `PaymentFailed` | no capture, seat released | none — saga is clean |
| `CompensationFailed` | no capture, release *also* failed | hold TTL + cleanup Lambda are the backstop |
| `PurchaseRefunded` | captured, confirm **404** (hold expired or lost), refund SUCCEEDED, hold released best-effort | none: the customer has their money back |
| `RefundFailed` | captured, confirm 404, refund FAILED or never answered | retry `POST /orders/{id}/refund` under a new key: money is still taken |
| `PaidButNotConfirmed` | captured, confirm **timed out / 5xx** after retries: the seat may be SOLD | read the seat: SOLD under this `holdId` → resume issuance; otherwise refund. **Never** release blind |
| `PaidButNotIssued` | captured and SOLD, enqueue failed | replay from issuance; do not re-charge, never refund |

`CompensationFailed` is survivable precisely because slice 2 exists: the DynamoDB TTL and
the EventBridge-scheduled cleanup Lambda reclaim the hold without anyone intervening. That
is the payoff for having built expiry as infrastructure rather than as a client behaviour.

## `http:invoke` Requires HTTPS — Discovered The Hard Way

The first execution failed before any state ran:

```
The value for the 'ApiEndpoint' field must have the scheme 'https'
```

Step Functions' HTTP task refuses plain HTTP outright. It is a validation error on the
`ApiEndpoint` field, raised at state entry, so no request is ever attempted and no security
group or IAM grant is involved. The Fargate tasks serve `http://<ip>:8080` and nothing about
them can satisfy this.

Worth stating plainly because it is easy to mis-diagnose: this is **not** reachability. The
services were up, both were reachable from the laptop, and the IAM role simulated `allowed`
on all five actions. A saga that cannot start looks like a networking problem and is not one.

Consequences for the design:

- The services need an **HTTPS front door** before Step Functions can call them. An
  AWS-managed certificate comes free with API Gateway (`*.execute-api.<region>.amazonaws.com`);
  an ALB would need ACM, which needs a domain, which Phase 1 deliberately defers.
- The IAM condition is `states:HTTPEndpoint: "https://*"`, not `http://*`.
- Base URLs still belong in the **execution input** rather than the definition, because
  whatever fronts the services is itself session-scoped in this phase.

## Prerequisites

Both services running with public IPs, reachable through an HTTPS front door, with the two
base URLs passed as *execution input*:

```json
{ "inventoryBaseUrl": "https://<host>", "checkoutBaseUrl": "https://<host>" }
```

Slice 6 replaces the front door with API Gateway → ALB properly; whatever is used here is a
stand-in for that.

| Prerequisite | State |
|---|---|
| `seat-inventory` DynamoDB table | exists |
| `seat-inventory-service:3` task definition | exists (has the DynamoDB task role) |
| `checkout-payment-service:2` task definition | exists |
| RDS `ticket-checkout-db` | must be up |
| Seats seeded for the test event | `POST /admin/events/{eventId}/seats` |

## 1. SQS Queue

**SQS → Create queue**

| Field | Value |
|---|---|
| Type | **Standard** |
| Name | `ticket-issuance-queue` |
| Visibility timeout | 30s |
| Message retention | 4 days |
| Tags | `project` = `ticket-waiting-room` |

Standard, not FIFO: issuance is idempotent on `orderId`, and FIFO's throughput ceiling buys
nothing here. A redrive policy belongs with the consumer in slice 5 — there is nothing to
consume yet, so a DLQ now would only collect messages nobody reads.

## 2. SNS Topic

**SNS → Topics → Create topic**

| Field | Value |
|---|---|
| Type | **Standard** |
| Name | `purchase-confirmed` |

Add an **Email** subscription to yourself and confirm it from the inbox, otherwise
`sns:publish` succeeds into the void and the notify step proves nothing.

## 3. EventBridge Connection

`http:invoke` will not call anything without a connection — it is where credentials live,
so that a state machine definition never contains a secret.

**EventBridge → Connections → Create connection**

| Field | Value |
|---|---|
| Name | `ticket-services` |
| Authorization type | **API key** |
| API key name | `X-Saga-Auth` |
| Value | any non-empty string |

Our services do not check the header and ignore it as an unknown one. The connection is
required machinery, not real authentication — slice 6 replaces it when Cognito arrives.

> **Superseded by `runbook-cognito-auth.md` §8.** The connection is now
> `OAUTH_CLIENT_CREDENTIALS` against the Cognito token endpoint, and the services verify the
> bearer token it produces. The steps above are kept as the record of what slice 4 built;
> a fresh rebuild should follow the Cognito runbook for this section, and note that
> `POST /orders` now requires a client-supplied `orderId` in its body.
Creating it also creates a managed secret under `events!connection/…`, which the execution
role must be allowed to read.

## 4. IAM Role

**IAM → Roles → Create role → AWS service → Step Functions**, named
`ticket-saga-role`, with an inline policy `SagaInvoke`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "CallHttpEndpoints", "Effect": "Allow",
      "Action": "states:InvokeHTTPEndpoint", "Resource": "*",
      "Condition": { "StringLike": { "states:HTTPEndpoint": "https://*" } } },
    { "Sid": "UseConnection", "Effect": "Allow",
      "Action": "events:RetrieveConnectionCredentials",
      "Resource": "arn:aws:events:eu-central-1:<account-id>:connection/ticket-services/*" },
    { "Sid": "ReadConnectionSecret", "Effect": "Allow",
      "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
      "Resource": "arn:aws:secretsmanager:eu-central-1:<account-id>:secret:events!connection/*" },
    { "Sid": "Enqueue", "Effect": "Allow",
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:eu-central-1:<account-id>:ticket-issuance-queue" },
    { "Sid": "Notify", "Effect": "Allow",
      "Action": "sns:Publish",
      "Resource": "arn:aws:sns:eu-central-1:<account-id>:purchase-confirmed" }
  ]
}
```

Three things that are easy to miss:

- **`ReadConnectionSecret` is separate from `UseConnection`.** The connection's credentials
  live in Secrets Manager under an `events!connection/` prefix, and the role needs both
  grants. Omitting the secret one fails at execution with a permissions error that names
  the *connection*, not the secret.
- **`states:InvokeHTTPEndpoint` cannot be scoped by resource** — it takes `"Resource": "*"`
  and is narrowed by the `states:HTTPEndpoint` condition instead. `https://*` is as tight as
  this gets while the front door is recreated each session; with a stable ALB hostname in
  slice 6 it narrows to one host.
- **CloudWatch Logs permissions are added by the console** if you enable logging when
  creating the state machine. Building the role by hand first means adding
  `logs:CreateLogDelivery`, `logs:GetLogDelivery`, `logs:UpdateLogDelivery`,
  `logs:DeleteLogDelivery`, `logs:ListLogDeliveries`, `logs:PutResourcePolicy`,
  `logs:DescribeResourcePolicies` and `logs:DescribeLogGroups` on `"*"` — they do not
  accept a narrower resource.

## 5. State Machine

**Step Functions → Create state machine → Blank → Code** — then paste
`docs/checkout-saga.asl.json` with the three placeholders substituted:

| Placeholder | Replace with |
|---|---|
| `__CONNECTION_ARN__` | the `ticket-services` connection ARN (**7** occurrences) |
| `__QUEUE_URL__` | the `ticket-issuance-queue` URL |
| `__TOPIC_ARN__` | the `purchase-confirmed` topic ARN |

| Field | Value |
|---|---|
| Name | `checkout-saga` |
| Type | **Standard** |
| Execution role | `ticket-saga-role` |
| Logging | ALL, to `/aws/vendedlogs/states/checkout-saga` |
| Tags | `project` = `ticket-waiting-room` |

**Standard, not Express.** Express drops per-state execution history, which is the entire
diagnostic value here, and does not support the exactly-once semantics a saga wants.
Standard bills per state transition — around 25 per execution at $0.000025, so a hundred
test runs cost under a cent.

### What the definition is doing

- **`QueryLanguage: JSONata`** throughout. URL assembly is plain string concatenation
  (`$req.checkoutBaseUrl & '/orders/' & $orderId & '/payment'`); the older JSONPath
  approach needs a `Pass` state per URL just to build strings.
- **`Assign` blocks create variables** (`$req`, `$holdId`, `$orderId`, `$payment`) that
  outlive the state that set them. `$states.input` is per-state, so the execution input is
  captured once into `$req` in `HoldSeat` and referenced everywhere after.
- **`ChargePayment` retries on `States.ALL`, three attempts with backoff.** This is only
  safe because `paymentKey` is fixed for the execution: every retry replays the same
  idempotency key and the service returns the original answer instead of charging again.
  Retrying a charge without that guarantee is how double charges happen. It is worth
  noticing that the retry policy here is *only* correct because of a property of the
  database schema two layers away.
- **`ReleaseSeat` retries harder than it catches.** A failed compensation is worse than a
  failed forward step, so it gets four attempts before conceding to `CompensationFailed`.

> The error names are `States.Http.StatusCode.<code>`, confirmed against the HTTP Task
> documentation (2026-10-07), which catches `States.Http.StatusCode.404` in its own example.
> `ConfirmPurchase` and `ReleaseSeatAfterRefund` now route on the 404 specifically. The
> other `Retry` blocks stay on `States.ALL`: retrying a `409` is wasteful but harmless with
> an idempotency key, and narrowing them buys nothing a session would notice.

## Verify Both Paths

Launch both services and seed the seat first:

```bash
INV=http://<inventory-ip>:8080
CO=http://<checkout-ip>:8080
curl -s -X POST $INV/admin/events/evt-saga/seats -H 'content-type: application/json' \
  -d '{"seatIds":["S-1","S-2"]}'
```

### Happy path

Start an execution with:

```json
{
  "inventoryBaseUrl": "http://<inventory-ip>:8080",
  "checkoutBaseUrl": "http://<checkout-ip>:8080",
  "eventId": "evt-saga", "seatId": "S-1",
  "customerRef": "cust-saga-1", "amountCents": 12000,
  "paymentToken": "tok_ok",
  "orderKey": "saga-order-1", "paymentKey": "saga-pay-1"
}
```

Expect the graph to run `HoldSeat → CreateOrder → ChargePayment → PaymentCaptured →
ConfirmPurchase → EnqueueTicketIssuance → NotifyPurchaseConfirmed → Succeeded`, then:

```bash
aws dynamodb get-item --table-name seat-inventory --region eu-central-1 \
  --key '{"PK":{"S":"EVENT#evt-saga"},"SK":{"S":"SEAT#S-1"}}' \
  --query 'Item.status.S'                     # -> SOLD

aws sqs receive-message --queue-url <queue-url> --region eu-central-1 \
  --query 'Messages[0].Body' --output text    # -> the issuance payload
```

Plus the confirmation email from SNS.

### Compensation path

Same input with `"seatId": "S-2"`, `"paymentToken": "tok_decline"`, and **fresh**
`orderKey`/`paymentKey`. Expect `HoldSeat → CreateOrder → ChargePayment →
PaymentCaptured → ReleaseSeat → PaymentFailed`, and then the point of the whole exercise:

```bash
aws dynamodb get-item --table-name seat-inventory --region eu-central-1 \
  --key '{"PK":{"S":"EVENT#evt-saga"},"SK":{"S":"SEAT#S-2"}}' \
  --query 'Item.status.S'                     # -> AVAILABLE, not HELD
```

The seat is back on sale, no message was enqueued, and the order is `FAILED` in Postgres.
Confirm the Choice state — not a `Catch` — is what routed it, by checking that
`ChargePayment` shows as **succeeded** in the execution graph while the execution as a
whole failed.

## The HTTPS Front Door (API Gateway HTTP API)

`http:invoke` requires HTTPS and the Fargate tasks speak plain HTTP on 8080, so something
has to terminate TLS. The cheapest thing that does is an **HTTP API**, which comes with an
AWS-managed certificate on `*.execute-api.<region>.amazonaws.com` — no domain, no ACM, no
hourly charge (\$1.00/million requests).

One API per service, each a transparent proxy:

```bash
ID=$(aws apigatewayv2 create-api --name inventory-api --protocol-type HTTP \
      --tags project=ticket-waiting-room --query ApiId --output text)
INT=$(aws apigatewayv2 create-integration --api-id "$ID" \
      --integration-type HTTP_PROXY --integration-method ANY \
      --integration-uri "http://<task-ip>:8080/{proxy}" --payload-format-version 1.0 \
      --query IntegrationId --output text)
aws apigatewayv2 create-route --api-id "$ID" --route-key 'ANY /{proxy+}' --target "integrations/$INT"
aws apigatewayv2 create-stage --api-id "$ID" --stage-name '$default' --auto-deploy
```

`ANY /{proxy+}` paired with `{proxy}` in the integration URI passes method, path and body
straight through, so no route needs to know the services' shapes.

**This is a stand-in for slice 6, not the destination.** The task IP is baked into the
integration URI, so it is re-pointed every session. Slice 6 replaces the integration with a
VPC link to an ALB and the whole problem disappears.

### It requires opening 8080 to the internet

API Gateway reaches an `HTTP_PROXY` target over the public internet, and AWS publishes no
source range or managed prefix list for it. So the task security groups need
`0.0.0.0/0` on 8080 — my-IP-only produces a flat **503** from the gateway with nothing in
the service logs, because the connection never lands.

That exposes two unauthenticated services to the internet for as long as the rule exists.
Acceptable only because the payment gateway is a mock, the database is disposable, and the
tasks are gone at session end. **Open it for the test and revoke immediately after** rather
than leaving it to teardown:

```bash
aws ec2 revoke-security-group-ingress --group-id <sg> --protocol tcp --port 8080 --cidr 0.0.0.0/0
```

Options B and C avoid this entirely: an ALB with an ACM certificate (needs a domain, which
Phase 1 defers) or a proxy Lambda (needs another JDK 26 container-image module). Slice 6
gets there properly.

## Results (2026-08-27)

### Happy path — `SUCCEEDED`

```
HoldSeat → CreateOrder → ChargePayment → PaymentCaptured → ConfirmPurchase
        → EnqueueTicketIssuance → NotifyPurchaseConfirmed → Succeeded
```

| Check | Result |
|---|---|
| Seat `S-1` in DynamoDB | **SOLD**, `holdExpiresAt` removed |
| SQS | one message, correct `orderId` / `seatId` / `gatewayRef` |
| Postgres | `PAID` / `CAPTURED` |

The TTL being gone matters: an expiry attribute left behind on a SOLD seat would let the
cleanup Lambda reclaim a seat somebody paid for.

### Compensation path — `FAILED` with `PaymentFailed`

```
HoldSeat → CreateOrder → ChargePayment → PaymentCaptured → ReleaseSeat → PaymentFailed
```

| Check | Result |
|---|---|
| Seat `S-2` in DynamoDB | **AVAILABLE**, `holdId` and TTL cleared |
| SQS | no new message |
| Postgres | `FAILED` / `DECLINED`, reason `card_declined` |
| `ChargePayment` in execution history | **TaskSucceeded** |

That last row is the one to keep. All four HTTP tasks report success, including the charge
that was declined — so the branch to `ReleaseSeat` was taken by the **Choice** state
reading `paymentStatus`, exactly as designed. Had compensation been wired to `Catch`, this
execution would have run straight through to `Succeeded` with the seat still held and an
issuance message enqueued for a purchase that never happened.

Invariants re-checked in Postgres after both runs: 0 orders with more than one live
payment, 0 CAPTURED payments whose order is not PAID.

## Teardown

The state machine, queue, topic, role and the two HTTP APIs are **$0 standing** and stay
between sessions. The Fargate tasks and RDS go — see `runbook-checkout-rds.md`.

**The EventBridge connection is the exception: it is not free.** Every connection stores its
credentials as a Secrets Manager secret at **$0.40/month**, billed whether or not the saga
ever runs. That is the only standing charge this slice creates, so the connection is deleted
at the end of a session like any other paid resource:

```bash
aws events delete-connection --name ticket-services --region eu-central-1
```

Recreating it next session takes one command, but the ARN changes, so the state machine
definition has to be re-pointed at the new one — all **7** occurrences:

```bash
CONN=$(aws events create-connection --name ticket-services \
  --authorization-type API_KEY \
  --auth-parameters "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"X-Saga-Auth\",\"ApiKeyValue\":\"$(openssl rand -hex 16)\"}}" \
  --query ConnectionArn --output text)

sed -e "s|__CONNECTION_ARN__|$CONN|g" \
    -e "s|__QUEUE_URL__|https://sqs.eu-central-1.amazonaws.com/<account-id>/ticket-issuance-queue|g" \
    -e "s|__TOPIC_ARN__|arn:aws:sns:eu-central-1:<account-id>:purchase-confirmed|g" \
    docs/checkout-saga.asl.json > /tmp/saga.json

aws stepfunctions update-state-machine \
  --state-machine-arn arn:aws:states:eu-central-1:<account-id>:stateMachine:checkout-saga \
  --definition file:///tmp/saga.json
```

Keeping the template in git with placeholders rather than a substituted copy is what makes
that a one-liner instead of a hand-edit.

The HTTP APIs survive but point at dead task IPs. Re-point rather than recreate:

```bash
aws apigatewayv2 update-integration --api-id <id> --integration-id <int-id> \
  --integration-uri "http://<new-task-ip>:8080/{proxy}"
```

Also worth deleting if you rebuild: CloudWatch log group
`/aws/vendedlogs/states/checkout-saga`, set to 1-day retention.

## The Refund Path

Added 2026-10-07, in code and tests. **It has not been run on AWS yet**: the steps below
deploy and prove it in the next session that has the stack up.

### Why it waited for a fix in seat inventory

The refund could not be added on its own. `ConfirmPurchase` retries, and until this change
the seat inventory's confirm was **not idempotent**: if the first attempt committed but its
response was lost, the retry found the seat SOLD and got a 404. Refunding on that 404 would
have given the buyer the seat *and* the money. So the confirm was fixed first: a failed
condition returns the item as it stood (`ReturnValuesOnConditionCheckFailure = ALL_OLD`),
and SOLD under the caller's own `holdId` is answered 200 (`seat-inventory-service/ARCHITECTURE.md`).
After that, a 404 means one thing: this order does not own the seat, and it never will.

### What the definition does now

- `ConfirmPurchase` has two retriers and two catchers, in order. A
  `States.Http.StatusCode.404` is **not retried** (`MaxAttempts: 0`, since retrying a
  definite answer only delays the refund) and routes to `RefundPayment`. Everything else
  retries four times and then stops at `PaidButNotConfirmed`, because a timeout cannot tell
  a sold seat from an unsold one.
- `RefundPayment` posts `{idempotencyKey: paymentKey + ":refund", reason}`. The key is fixed
  per execution, so its retries replay rather than refund twice. In the database,
  `ux_refunds_one_live_per_payment` makes a second live refund of the same payment
  impossible whatever key it carries.
- `RefundSucceeded` is a **Choice** on `refundStatus`, for the same reason
  `PaymentCaptured` is: a failed refund is HTTP 200 with `refundStatus: FAILED`, and a
  `Catch` would never see it.
- `ReleaseSeatAfterRefund` frees the hold, best effort. A 404 there is the normal case (the
  hold usually expired, which is why the confirm failed), and both outcomes end at
  `PurchaseRefunded`.
- `CheckoutSagaContractTest` pins the request bodies and Choice fields to the controller's
  records, and pins the 404 route so it cannot quietly widen to `States.ALL`.

### Deploy (next session)

1. Rebuild and push **both** images: `seat-inventory-service` (idempotent confirm) and
   `checkout-payment-service` (refund endpoint, Flyway **V2** runs at startup and adds the
   `refunds` table plus the `REFUNDED` status). Register new task-definition revisions on
   the new digests, as in `runbook-ecr-packaging.md`. **The order matters:** if
   seat-inventory still runs the old image while the new saga is live, a lost confirm
   response becomes a refund of a sold seat.
2. Update the state machine with the substitution command under *Teardown* (now 7
   occurrences of the connection ARN). No IAM change: `RefundPayment` is a POST to the same
   `https://` host the role already allows.
3. **Optional, recommended:** an alarm, because none exists in the account today. An
   EventBridge rule on `aws.states` / `Step Functions Execution Status Change`, `status:
   FAILED`, with `detail.error` in `RefundFailed`, `PaidButNotConfirmed`, `PaidButNotIssued`
   and `CompensationFailed`, targeting a new SNS topic with one email subscription. These
   are the four outcomes a human has to finish, and $0 at this volume. Check the event's
   `detail` in the console's sample events before relying on the `error` field.

### Verify

Force the hold to expire while the payment is in flight: run seat-inventory with
`SEAT_INVENTORY_HOLD_DURATION=PT10S` and checkout with `PAYMENT_GATEWAY_LATENCY=PT15S`, then
purchase with `tok_ok`. Expected:

- The execution history shows `ConfirmPurchase` failing **once** with
  `States.Http.StatusCode.404`, with no retries, then `RefundPayment → RefundSucceeded →
  ReleaseSeatAfterRefund → PurchaseRefunded`.
- In Postgres: order `REFUNDED`, payment `CAPTURED`, one `refunds` row `SUCCEEDED` with
  `amount_cents` equal to the payment's.
- `load-test/scripts/invariants.sh` passes: it counts a refunded capture against a REFUNDED
  order, not as money adrift.
- Repeat with `tok_refund_error`: `RefundFailed`, order still `PAID`, refund `FAILED`,
  `checkout.refunds{outcome=failed}` = 1 on the dashboard.

## Phase 2 Notes

- The base URLs become one ALB hostname, and `states:HTTPEndpoint` narrows from `http://*`
  to that host.
- The API-key connection becomes real authentication.
- The four "a human finishes this" Fail states get their EventBridge rule and SNS topic as
  code (*The Refund Path*, step 3).
