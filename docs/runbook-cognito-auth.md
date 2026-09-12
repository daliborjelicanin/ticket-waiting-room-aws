# Runbook — Cognito User Pool, JWT Everywhere, Identity-Bound Queue

Slice 6's third item. The first slice in this project that is about *who* rather than
*what*: until now `customerRef` was a string the caller typed, every service believed it,
and `GET /tickets/{id}/qr` handed a presigned S3 URL to anyone who asked.

Prerequisites: `runbook-alb-apigateway.md` (the REST edge and the ALB, which this slice
builds on and changes in two places), `runbook-checkout-saga.md` (the state machine and the
EventBridge connection, one of which is redefined and the other replaced),
`runbook-ecr-packaging.md` (every service image is rebuilt).

**Status: written ahead of the session, not yet executed.** The code half is done and
proven locally — see *Proven Before Touching AWS*. The *Results* section is the list of
claims the session has to turn into numbers.

## Design Decisions Made Before Touching the Console

**Every service verifies the caller's JWT itself.** Signature, issuer, expiry — against the
user pool's published keys, in the service, on every request. Nothing is trusted because of
where it came from. This is the alternative to the more common "API Gateway authorizes and
injects a header" shape, and it was chosen over it for one reason: in Phase 1 the ALB is
internet-facing (no NAT Gateway, no private subnets), so an injected header would have
rested entirely on the `X-Edge-Secret` shared secret. A static header secret has no expiry,
no rotation and no audit trail; leaked once, it is full impersonation of every customer.
Verifying in the service makes the network path irrelevant to identity.

**Two doors, told apart by the token's `scope` claim, not by the network path.** A customer's
access token carries Cognito's own `aws.cognito.signin.user.admin` and nothing of ours. A
token from the machine-to-machine app client carries `ticketing/ops`, and that scope is the
only thing that opens seeding, admitting, and the saga's own steps. A customer's token on any
of those is **403 at the filter**, before any handler runs. This is what deleted the
"admin role" problem: there are no Cognito groups, no role mapping, no `@PreAuthorize` — one
scope, checked in one shared filter chain (`security-starter`).

**Access tokens only.** A user pool signs ID tokens and access tokens with the same key, and
signature, issuer and expiry are identical between them. Boot's default decoder would accept
an ID token — a credential meant only for the browser that logged in — as an API bearer
token. `security-starter` insists on `token_use = access`. The one place an ID token is
presented is `POST /events/{id}/purchase` (below), and that is at API Gateway, never at a
service.

**`/{proxy+}` keeps `authorizationType: NONE`.** The services are the authorizer. A second
Cognito authorizer at the edge would verify the same token twice and add a cache and a
config surface for it; what it would *not* add is any protection the service does not
already have. The one method that gets an authorizer is the one with no service behind it.

**`X-Edge-Secret` stays, demoted.** It is no longer a security boundary. It exists so that
traffic cannot bypass API Gateway — and therefore cannot bypass WAF, which is the
anti-scalping control that lands next. The ALB rule is unchanged; only its job description is.

**Registration is required to join the queue, not just to buy.** Ticketmaster and AXS
require an account; guest checkout is what Eventbrite does, at the low-demand end this
project is not modelling. The reason is the project's own: a per-account ticket limit is
unenforceable without an account, and guest checkout hands a scalper an unlimited supply of
identities. `CLAUDE.md` and `phase1-plan.md` said "guest checkout" until this slice; both
now say why not.

**One live queue place per identity, enforced in Redis.** `join.lua` claims the customer's
token with `HSETNX` in the same script that enqueues it — one atomic round trip, the same
move as the DynamoDB conditional write and the partial unique index on payments. Without it,
FIFO ordering is a guarantee about *tokens*: one actor can join ten thousand times, be
ordered impeccably fairly, and take the room. See `waiting-room-service/ARCHITECTURE.md`.

**Purchase gets a real entry point, and its idempotency is the client's order id.**
`POST /events/{id}/purchase` is an API Gateway → Step Functions `StartExecution` service
integration — no Lambda, the same rule the WebSocket routes follow. Its request template
builds the execution input from the body plus the **verified** `sub` and `email` claims,
which is the moment `customerRef` stops being something the customer can lie about. The
client supplies `orderId`, and three things hang off it: it becomes the order's primary key
(so the client knows what to poll before the saga has run), it is the saga's idempotency
key for create-order and charge, and it is the **execution name** — so a retried purchase is
refused at the edge with `ExecutionAlreadyExists` rather than starting a second saga.

## Target Topology

```
customer                                             operator (you)
  │ 1. InitiateAuth (USER_PASSWORD_AUTH)                │ client_credentials
  ▼    → id token + access token                        ▼   → access token, scope ticketing/ops
Cognito user pool  ticket-users
  · domain (token endpoint)  · resource server ticketing, scope ops
  · app client ticket-web (public)  · app client ticket-ops (confidential, M2M)

                 REST API ticket-edge, stage dev
   POST /events/{id}/purchase ── COGNITO authorizer (ID token) ──► states:StartExecution
                                   template injects claims.sub → customerRef
                                                    claims.email → email
   ANY  /{proxy+} ────────────── NONE ─── X-Edge-Secret ─────────► ALB ──► services
                                   Authorization: Bearer <access token> passes through
                                                                       │
                                            security-starter verifies the JWT here,
                                            in every service, on every request
                                                                       ▲
Step Functions checkout-saga ── EventBridge connection ticket-services ┘
   http:invoke through ticket-edge/{proxy+}   OAuth client credentials → Bearer, scope ticketing/ops
```

## Proven Before Touching AWS

Everything below ran green locally with no Cognito anywhere — LocalStack Community has none,
and this design does not need one. Slice tests authenticate with spring-security-test's
`jwt()` post-processor; every `@SpringBootTest` boots against a placeholder issuer that is
never contacted, because the decoder is lazy (see *Traps Predicted*).

- **200 concurrent joins from 200 identities → 200 distinct contiguous places** (slice 3's
  result, unchanged) and **200 concurrent joins from one identity → 1 place, every attempt
  told the same token**. A scalper racing 200 joins against nine people leaves a room of
  ten. `WaitingRoomFairnessIT`.
- A customer's token on `POST …/queue/admit`, `POST /orders`, `POST /orders/{id}/payment`,
  any seat-inventory endpoint, `POST /admin/…` → **403** at the filter; the handler is
  never invoked. Anonymous → **401** with `WWW-Authenticate: Bearer`.
- Another customer's order, ticket or QR → **404, identical to an unknown id**, and for the
  QR the test asserts `presign` is never called — no signature is minted for a non-owner.
- An ID token is refused on `token_use` alone. `AccessTokenValidatorTest`.
- The state machine definition still carries every field of `TicketIssuanceMessage` and
  `PurchaseConfirmedEvent` — `TicketIssuanceContractTest` caught the new `email` field
  before the ASL was updated, which is exactly what it is for.

**123 tests, 0 failures**, and the three Lambda images are unchanged (34 / 40 / 37 jars,
zero Spring on any of them — `security-starter` is deliberately not in `common`).

## Console / CLI Steps

Set the region once. `$R="--region …"` in a variable does **not** work from zsh — it is
passed as a single argument — and that trap is now four for four across the runbooks:

```bash
export AWS_DEFAULT_REGION=eu-central-1
```

### 1. User pool

```bash
POOL_ID=$(aws cognito-idp create-user-pool --pool-name ticket-users \
  --username-attributes email --auto-verified-attributes email \
  --policies 'PasswordPolicy={MinimumLength=12,RequireUppercase=false,RequireLowercase=false,RequireNumbers=false,RequireSymbols=false}' \
  --user-pool-tags project=ticket-waiting-room \
  --query UserPool.Id --output text)
echo $POOL_ID                    # eu-central-1_XXXXXXXXX

ISSUER=https://cognito-idp.eu-central-1.amazonaws.com/$POOL_ID
curl -s $ISSUER/.well-known/openid-configuration | jq -r .jwks_uri
```

That last line is what every service will fetch on its first token. It is a public GET with
no credentials, which is why local `bootRun` can point at the real pool.

The weak password policy is deliberate for a pool holding three test users; note it as a
Phase 2 default to tighten.

### 2. Domain — for the token endpoint, not for a login page

```bash
aws cognito-idp create-user-pool-domain --user-pool-id $POOL_ID --domain ticket-waiting-room-<account-id>
TOKEN_URL=https://ticket-waiting-room-<account-id>.auth.eu-central-1.amazoncognito.com/oauth2/token
```

The prefix must be globally unique, hence the account id. This is *not* the hosted UI being
adopted: there is no frontend to use it from, and an app client nothing exercises is
configuration pretending to be architecture. The domain exists because `client_credentials`
needs a token endpoint, and the token endpoint lives under the domain.

### 3. Resource server and scope

```bash
aws cognito-idp create-resource-server --user-pool-id $POOL_ID \
  --identifier ticketing --name "Ticketing services" \
  --scopes ScopeName=ops,ScopeDescription="Operator and saga access"
```

`ticketing/ops` is the string in `TicketingScopes.OPS`. It is configured here, checked in
four services, and read by no compiler — the same class of contract as `SeatTableSchema`.

### 4. Two app clients

```bash
# Customers: public client, password auth for curl, no secret.
WEB_CLIENT=$(aws cognito-idp create-user-pool-client --user-pool-id $POOL_ID \
  --client-name ticket-web --no-generate-secret \
  --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_REFRESH_TOKEN_AUTH \
  --access-token-validity 60 --id-token-validity 60 --token-validity-units AccessToken=minutes,IdToken=minutes \
  --query UserPoolClient.ClientId --output text)

# Saga and operators: confidential client, client-credentials only, the one scope.
aws cognito-idp create-user-pool-client --user-pool-id $POOL_ID \
  --client-name ticket-ops --generate-secret \
  --allowed-o-auth-flows client_credentials --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-scopes ticketing/ops \
  --query 'UserPoolClient.[ClientId,ClientSecret]' --output text
# → OPS_CLIENT_ID  OPS_CLIENT_SECRET   (the secret is shown once; it goes into the EventBridge connection in step 8)
```

`ticket-ops` cannot log a user in and `ticket-web` cannot obtain `ticketing/ops` — a
customer token and an operator token are minted by different clients with disjoint
capabilities, which is what makes the scope check mean something.

### 5. Two customers

Two, because the headline result is one of them being told the other's order does not exist.

```bash
for u in alice bob; do
  aws cognito-idp admin-create-user --user-pool-id $POOL_ID --username $u@example.test \
    --user-attributes Name=email,Value=$u@example.test Name=email_verified,Value=true \
    --message-action SUPPRESS
  aws cognito-idp admin-set-user-password --user-pool-id $POOL_ID --username $u@example.test \
    --password 'correct-horse-battery' --permanent
done
```

`email_verified=true` is set by hand because `admin-create-user` skips the verification
mail. Use a real address for at least one of them if the confirmation email is to be seen —
and remember `runbook-notifications.md`: SES in sandbox only delivers to verified identities.

### 6. Tokens

```bash
# Customer: one call returns both kinds of token.
aws cognito-idp initiate-auth --client-id $WEB_CLIENT --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=alice@example.test,PASSWORD=correct-horse-battery \
  --query 'AuthenticationResult.[AccessToken,IdToken]' --output text
# → ALICE_AT  ALICE_IT

# Operator: client credentials.
OPS_AT=$(curl -s -X POST $TOKEN_URL -u "$OPS_CLIENT_ID:$OPS_CLIENT_SECRET" \
  -d 'grant_type=client_credentials&scope=ticketing/ops' | jq -r .access_token)
```

Look at the two access tokens before going further — `jq -R 'split(".")[1] | @base64d | fromjson'`:

| claim | customer (`ticket-web`) | operator (`ticket-ops`) |
|---|---|---|
| `token_use` | `access` | `access` |
| `scope` | `aws.cognito.signin.user.admin` | `ticketing/ops` |
| `sub` | the user's id → `customerRef` | the client id |
| `email` | **absent** — only the ID token carries it | absent |

The absent `email` is why step 10's purchase method takes the ID token.

### 7. Rebuild every service image; add the issuer to every task definition

All five services changed (`common` did, and `security-starter` is new to four of them).
Per `runbook-ecr-packaging.md`, then a new task-definition revision for each of the four
resource servers with one added environment variable:

| Variable | Value |
|---|---|
| `COGNITO_ISSUER_URI` | `$ISSUER` from step 1 |

No task role change: JWKS is fetched over plain HTTPS with no AWS credentials. Without the
variable the task exits at startup with the property named — deliberately. Watch for it in
the log group if a service flaps.

`notification-service` gets no variable; it has no HTTP endpoints to protect.

### 8. EventBridge connection — from an ignored header to a real token

`runbook-checkout-saga.md` §3 created `ticket-services` with an API-key header the services
ignored, and promised this slice would replace it. Delete it and create the OAuth version:

```bash
aws events delete-connection --name ticket-services
aws events create-connection --name ticket-services \
  --authorization-type OAUTH_CLIENT_CREDENTIALS \
  --auth-parameters "OAuthParameters={ClientParameters={ClientID=$OPS_CLIENT_ID,ClientSecret=$OPS_CLIENT_SECRET},AuthorizationEndpoint=$TOKEN_URL,HttpMethod=POST,OAuthHttpParameters={BodyParameters=[{Key=grant_type,Value=client_credentials},{Key=scope,Value=ticketing/ops}]}}" \
  --query ConnectionArn --output text
```

EventBridge obtains a token from Cognito, caches it for its lifetime, and sends it as
`Authorization: Bearer …` on every `http:invoke`. **The connection ARN and its managed
secret ARN are new** — update `ticket-saga-role`'s inline policy (`runbook-checkout-saga.md`
§4), exactly as that runbook warns for every recreated connection.

### 9. State machine — redefine with the two new fields

`docs/checkout-saga.asl.json` now sends `orderId` to `CreateOrder` and `email` in
`NotifyPurchaseConfirmed`. Substitute the placeholders and update:

```bash
aws stepfunctions update-state-machine --state-machine-arn <arn> \
  --definition "$(sed -e "s#__CONNECTION_ARN__#$CONNECTION_ARN#" -e "s#__QUEUE_URL__#$QUEUE_URL#" -e "s#__TOPIC_ARN__#$TOPIC_ARN#" docs/checkout-saga.asl.json)"
```

### 10. API Gateway — one authorizer, one new method, one unchanged

**Authorizer** (REST API `ticket-edge` from `runbook-alb-apigateway.md` §7):

```bash
API_ID=<ticket-edge id>
AUTH_ID=$(aws apigateway create-authorizer --rest-api-id $API_ID --name ticket-users \
  --type COGNITO_USER_POOLS --provider-arns arn:aws:cognito-idp:eu-central-1:<account-id>:userpool/$POOL_ID \
  --identity-source method.request.header.Authorization \
  --query id --output text)
```

**`POST /events/{eventId}/purchase`** — resource, method, integration, and the role it needs:

```bash
# Role API Gateway assumes to start the saga
aws iam create-role --role-name ticket-edge-apigw-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"apigateway.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-role-policy --role-name ticket-edge-apigw-role --policy-name StartSaga \
  --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"states:StartExecution\",\"Resource\":\"$SAGA_ARN\"}]}"

ROOT=$(aws apigateway get-resources --rest-api-id $API_ID --query "items[?path=='/'].id" --output text)
EVENTS=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $ROOT --path-part events --query id --output text)
EVENT=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $EVENTS --path-part '{eventId}' --query id --output text)
PURCHASE=$(aws apigateway create-resource --rest-api-id $API_ID --parent-id $EVENT --path-part purchase --query id --output text)

aws apigateway put-method --rest-api-id $API_ID --resource-id $PURCHASE --http-method POST \
  --authorization-type COGNITO_USER_POOLS --authorizer-id $AUTH_ID
```

The integration's request template is the whole point of the method. It takes what the
client may choose from the body, what the client may **not** choose from the verified
claims, and derives every idempotency key from `orderId`:

```bash
cat > /tmp/purchase-template.vtl <<'EOF'
#set($b = $input.path('$'))
{
  "stateMachineArn": "__SAGA_ARN__",
  "name": "$util.escapeJavaScript($b.orderId)",
  "input": "{\"orderId\":\"$util.escapeJavaScript($b.orderId)\",\"eventId\":\"$util.escapeJavaScript($input.params('eventId'))\",\"seatId\":\"$util.escapeJavaScript($b.seatId)\",\"amountCents\":$b.amountCents,\"paymentToken\":\"$util.escapeJavaScript($b.paymentToken)\",\"customerRef\":\"$context.authorizer.claims.sub\",\"email\":\"$context.authorizer.claims.email\",\"orderKey\":\"$util.escapeJavaScript($b.orderId)\",\"paymentKey\":\"$util.escapeJavaScript($b.orderId)-pay\",\"inventoryBaseUrl\":\"$stageVariables.servicesBaseUrl\",\"checkoutBaseUrl\":\"$stageVariables.servicesBaseUrl\"}"
}
EOF
sed -i "s#__SAGA_ARN__#$SAGA_ARN#" /tmp/purchase-template.vtl

aws apigateway put-integration --rest-api-id $API_ID --resource-id $PURCHASE --http-method POST \
  --type AWS --integration-http-method POST \
  --uri arn:aws:apigateway:eu-central-1:states:action/StartExecution \
  --credentials arn:aws:iam::<account-id>:role/ticket-edge-apigw-role \
  --request-templates "{\"application/json\": $(jq -Rs . /tmp/purchase-template.vtl)}"
aws apigateway put-method-response --rest-api-id $API_ID --resource-id $PURCHASE --http-method POST --status-code 202
aws apigateway put-integration-response --rest-api-id $API_ID --resource-id $PURCHASE --http-method POST \
  --status-code 202 --selection-pattern '' \
  --response-templates '{"application/json": "{\"orderId\": $input.json(\"$.name\") , \"executionArn\": $input.json(\"$.executionArn\")}"}'
```

Three things in that template are decisions, not plumbing:

- **`customerRef` and `email` come from `$context.authorizer.claims`**, never from the
  body. The body can carry a `customerRef` and it is simply not read.
- **`name` is `orderId`.** Standard workflows require execution names to be unique for 90
  days, so a retried `POST` is refused at the edge with `ExecutionAlreadyExists` and the
  first saga runs on undisturbed. That is the purchase's idempotency, and it cost nothing.
- **`amountCents` still comes from the body.** It always has — the price belongs to an
  event catalog that does not exist yet. Recorded as the known gap it is, not hidden.

Stage variable `servicesBaseUrl` = `https://$API_ID.execute-api.eu-central-1.amazonaws.com/dev`,
the same API: the saga's `http:invoke` goes back through `/{proxy+}` to the ALB with its
bearer token, satisfying `http:invoke`'s HTTPS requirement without a second front door.

**`ANY /{proxy+}` — no change.** Confirm it still reads `authorizationType: NONE` and still
maps `X-Edge-Secret` from the stage variable. `Authorization` passes through an HTTP proxy
integration untouched, which is all the services need.

Deploy the stage:

```bash
aws apigateway create-deployment --rest-api-id $API_ID --stage-name dev
```

### 11. Verify

```bash
API=https://$API_ID.execute-api.eu-central-1.amazonaws.com/dev
ORDER=$(uuidgen | tr A-Z a-z)
```

Every numbered line below is a claim; the *Results* section is where each becomes a
number, a status code, or a log line.

```bash
# 1. anonymous → 401 from the SERVICE (not from API Gateway), with a Bearer challenge
curl -si -X POST "$API/events/evt-c/queue" | head -5

# 2. seed as operator; then as a customer → 403, and NOTHING in the seat-inventory log group
curl -s -X POST "$API/admin/events/evt-c/seats" -H "Authorization: Bearer $OPS_AT" \
  -H 'content-type: application/json' -d '{"seatIds":["C-1","C-2"]}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/admin/events/evt-c/seats" \
  -H "Authorization: Bearer $ALICE_AT" -H 'content-type: application/json' -d '{"seatIds":["X"]}'

# 3. alice joins; joins again; joins 50 times concurrently → one place, one token
curl -s -X POST "$API/events/evt-c/queue" -H "Authorization: Bearer $ALICE_AT"
seq 50 | xargs -P50 -I{} curl -s -X POST "$API/events/evt-c/queue" -H "Authorization: Bearer $ALICE_AT" \
  | jq -r .token | sort | uniq -c            # exactly one line, count 50

# 4. alice cannot admit; the operator can
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-c/queue/admit" \
  -H "Authorization: Bearer $ALICE_AT" -H 'content-type: application/json' -d '{"count":1}'      # 403
curl -s -X POST "$API/events/evt-c/queue/admit" -H "Authorization: Bearer $OPS_AT" \
  -H 'content-type: application/json' -d '{"count":1}'

# 5. purchase with the ID token; the body's customerRef is ignored
curl -s -X POST "$API/events/evt-c/purchase" -H "Authorization: $ALICE_IT" \
  -H 'content-type: application/json' \
  -d "{\"orderId\":\"$ORDER\",\"seatId\":\"C-1\",\"amountCents\":12000,\"paymentToken\":\"tok_ok\",\"customerRef\":\"forged\"}"
#    → 202 {orderId, executionArn}; wait for SUCCEEDED, then:
curl -s "$API/orders/$ORDER" -H "Authorization: Bearer $ALICE_AT" | jq .customerRef   # alice's sub, not "forged"

# 6. the same purchase again → 400 ExecutionAlreadyExists; the order is unchanged
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-c/purchase" -H "Authorization: $ALICE_IT" \
  -H 'content-type: application/json' -d "{\"orderId\":\"$ORDER\",\"seatId\":\"C-1\",\"amountCents\":12000,\"paymentToken\":\"tok_ok\"}"

# 7. alice sees her order and tickets; bob gets 404 on all three — identical to an unknown id
curl -s "$API/orders/$ORDER/tickets" -H "Authorization: Bearer $ALICE_AT" | jq .
TICKET=$(curl -s "$API/orders/$ORDER/tickets" -H "Authorization: Bearer $ALICE_AT" | jq -r '.[0].id')
curl -s "$API/tickets/$TICKET/qr"    -H "Authorization: Bearer $ALICE_AT" | jq .status
for p in "orders/$ORDER" "orders/$ORDER/tickets" "tickets/$TICKET/qr"; do
  curl -s -o /dev/null -w "$p %{http_code}\n" "$API/$p" -H "Authorization: Bearer $BOB_AT"
done

# 8. an ID token presented to a service is refused — token_use is not "access"
curl -s -o /dev/null -w '%{http_code}\n' "$API/orders/$ORDER" -H "Authorization: Bearer $ALICE_IT"   # 401

# 9. alice cannot create or charge an order directly
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/orders" -H "Authorization: Bearer $ALICE_AT" \
  -H 'content-type: application/json' -d '{}'                                                     # 403

# 10. confirmation mail went to alice's verified address, not to anything in the request
```

And the three that are not curls:

```bash
# 11. the saga authenticated with a real token — the checkout log shows the M2M client id as sub
aws logs tail /ecs/checkout-payment-service --since 10m | grep -i "sub\|scope" | head

# 12. first-token latency: the JWKS fetch happens on the first request per task, not at boot
#     (compare the first 401/200 against the second in the ALB access log or the task log)

# 13. the health check is green with the target answering 401 to GET /
aws elbv2 describe-target-health --target-group-arn <checkout-tg>
```

## Results

*To be filled during the session. Each entry answers one numbered claim above.*

| # | Claim | Result |
|---|---|---|
| 1 | anonymous → 401 from the service, `WWW-Authenticate: Bearer` | |
| 2 | customer seeding → 403; **no line in the seat-inventory log group** | |
| 3 | 50 concurrent joins as one identity → 1 token, count 50 | |
| 4 | customer admit → 403; operator admit → 200 | |
| 5 | purchase: `customerRef` = alice's `sub`, body value ignored | |
| 6 | retry → `ExecutionAlreadyExists`, order unchanged | |
| 7 | bob → 404 ×3, byte-identical to an unknown id | |
| 8 | ID token at a service → 401 | |
| 9 | customer `POST /orders` → 403 | |
| 10 | mail to the verified claim | |
| 11 | saga's calls carry the M2M `sub` | |
| 12 | first-token latency vs. steady state | |
| 13 | target healthy on 401 | |

## What It Cost

Cognito's pool, domain, resource server and the public client are free (Essentials tier,
10k MAU). The authorizer and the Step Functions integration are free. **M2M token requests
are billed** — per request, no free tier — and the exact rate must be read off the pricing
page during the session and written here: ______ per token. At this traffic it is a handful
per session, because EventBridge caches the token for its hour of life and the operator
fetches one.

Standing: **$0**. The pool is the first resource in this project that is *never* torn down,
and that is fine because it costs nothing — but destroying it would invalidate every user,
both client ids (one of which is inside the EventBridge connection) and the issuer baked
into four task definitions. Leave it.

## Traps Predicted

Things this runbook expects to bite, written down before the session so they can be checked
off rather than rediscovered. Move each to *Traps Hit* or *Predicted but not hit* afterwards.

- **`NimbusJwtDecoder.withIssuerLocation(…).build()` is eager.** It fetches the issuer's
  discovery document at construction. Found locally by a failing `contextLoads` — the
  starter wraps it in `SupplierJwtDecoder`, which is what Boot does. Consequence on AWS: the
  first request each task serves pays the JWKS fetch. Measure it (claim 12).
- **The Cognito authorizer's token format.** The `ticket-users` authorizer is configured on
  `method.request.header.Authorization`; whether it accepts `Bearer <token>` or wants the
  bare token is the kind of thing that costs twenty minutes. The verify block passes the
  bare ID token to `/purchase` and `Bearer` everywhere else. Record which worked.
- **`$context.authorizer.claims.email` is only there for an ID token.** Send an access token
  to `/purchase` and the claim resolves to an empty string, the template still renders, the
  saga runs, and the confirmation goes to the fallback address — silently. That is why
  step 6's claim table exists.
- **EventBridge's OAuth body parameters vs. Cognito's token endpoint.** Cognito accepts the
  client id and secret in the body for `client_credentials`; the connection is configured
  that way above. If the saga's first `http:invoke` fails with a connection error, check
  the connection's status and `describe-connection`'s `StateReason` before touching IAM.
- **`Missing Authentication Token` still means an unmatched path** — `runbook-alb-apigateway.md`
  recorded this for `GET /` and it is more misleading now that authentication genuinely
  exists. A 403 with that message from API Gateway is a routing problem; a 401 from a
  service is an authentication one. Tell them apart by the `WWW-Authenticate` header, which
  only the service sends.
- **`ExecutionAlreadyExists` surfaces as a 400 from the `AWS` integration** unless an
  integration response maps it. The verify block expects 400; if it comes back as 500,
  add a selection pattern for it rather than changing the design.
- **The ALB health check sees 401 now.** `200-499` accepts it. If a target group was ever
  created with a narrower matcher, the service will register unhealthy for a reason that
  looks like an auth failure and is not one.
- **Two tokens for the customer.** The ID token opens `/purchase`; the access token opens
  everything else. A client sending the wrong one gets a 401 in both directions (claim 8 is
  the service refusing the ID token). This is Cognito's design — the ID token carries
  identity for the client, the access token carries authorization for APIs — and the
  runbook names it rather than hiding it behind a helper.

## Teardown

**Nothing new to tear down.** Every resource this slice adds is free and permanent: the
pool, its domain, the resource server, two app clients, two users, the authorizer, the
`/purchase` resource, the EventBridge connection and one IAM role. The session-only
resources are exactly the ones every earlier runbook already lists — ECS services, RDS,
ElastiCache, ALB — and their teardown is unchanged.

The one thing to **rotate rather than delete** if it ever leaks: the `ticket-ops` client
secret. `update-user-pool-client` cannot regenerate it; delete the client, create it again,
and update the EventBridge connection — the saga's next token request picks it up.

## Notes for Phase 2 (Terraform)

- `aws_cognito_user_pool`, `_user_pool_domain`, `_resource_server`, two
  `_user_pool_client` — the M2M client's secret goes straight from the client resource's
  attribute into `aws_cloudwatch_event_connection`'s `auth_parameters.oauth`, and never
  through a human.
- `COGNITO_ISSUER_URI` is derived from the pool's `endpoint` attribute, so the four task
  definitions and the pool cannot drift.
- The purchase method's request template is the one piece of this slice that is a string
  inside a string inside JSON. Terraform's `templatefile()` with a `.vtl` file in the repo is
  the sane home for it; the version above is proof of concept.
- **The private-ALB question is now about WAF, not identity.** With every service
  verifying its own caller, `X-Edge-Secret` exists only so traffic cannot bypass API
  Gateway's WAF. An NLB + VPC link would make that structural; a security group restricted
  to API Gateway's published ranges is the $0 version. Neither is a security fix any more,
  and the Phase 2 decision can be made on cost alone.
- Tighten the password policy; it is set to the minimum a test user tolerates.
