# Runbook — Cognito User Pool, JWT Everywhere, Identity-Bound Queue

Slice 6's third item. The first slice in this project that is about *who* rather than
*what*: until now `customerRef` was a string the caller typed, every service believed it,
and `GET /tickets/{id}/qr` handed a presigned S3 URL to anyone who asked.

Prerequisites: `runbook-alb-apigateway.md` (the REST edge and the ALB, which this slice
builds on and changes in two places), `runbook-checkout-saga.md` (the state machine and the
EventBridge connection, one of which is redefined and the other replaced),
`runbook-ecr-packaging.md` (every service image is rebuilt).

**Status: executed and verified end-to-end on AWS (2026-09-12).** Four purchases through
the new entry point `SUCCEEDED`; all thirteen claims in *Results* are answered with numbers.
Session-only resources torn down the same afternoon; the pool, its clients and the new
`/purchase` method stay, at $0.

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

**Then against the real pool, locally (2026-09-12).** `waiting-room-service` on `bootRun`
with `COGNITO_ISSUER_URI` pointing at `ticket-users`, local Redis, real tokens from step 6 —
the first time this project decoded a Cognito token anywhere, and every answer was the one
the tests had predicted:

| request | answer |
|---|---|
| anonymous `POST …/queue` | **401**, `WWW-Authenticate: Bearer` |
| alice's access token, first ever | **201**, position 1 — **629 ms**, the discovery + JWKS fetch |
| the same, again | **201** — **6.6 ms**. Claim 12 answered before AWS: ~95× between the first token a task sees and every one after |
| alice's **ID** token | **401**, `error_description="… Not an access token: token_use must be 'access'"` — the validator's own words on the wire |
| alice on `…/queue/admit` | **403**, handler never ran |
| operator on `…/queue/admit` | **200**, alice admitted |
| bob, 50 concurrent joins | **one** token, count 50 |

Startup was 1.4 s with the pool not yet contacted — `SupplierJwtDecoder` doing exactly its
job. Zero AWS spend beyond the one M2M token from step 6.

## Console / CLI Steps

Set the region once. `$R="--region …"` in a variable does **not** work from zsh — it is
passed as a single argument — and that trap is now four for four across the runbooks:

```bash
export AWS_DEFAULT_REGION=eu-central-1
```

Each step below gives the console path first, then the CLI. They reach the same settings;
the console's "Create user pool" wizard (redesigned late 2024) creates a pool *and* its first
app client together, under generated names, so the console path does steps 1 and 4a at once.

### 1. User pool

**Console — Cognito → User pools → Create user pool.** The wizard is titled *Define your
application*:

| Field | Value | Why |
|---|---|---|
| Application type | **Single-page application** | the only public type with **no client secret**. *Traditional web application* generates one, which forces a `SECRET_HASH` on every `initiate-auth` |
| Name your application | `ticket-web` | becomes the first app client's name |
| Options for sign-in identifiers | **Email** only | |
| Required attributes for sign-up | **email** | selected automatically once Email is the identifier |
| Add a return URL | leave empty | no hosted login page is used |

**Create your application** → on the *Set up your application* page, **Go to overview**.
The pool has a generated name: **Rename** it to `ticket-users`. Copy the **User pool ID**
and derive the issuer:

```
POOL_ID = eu-central-1_XXXXXXXXX
ISSUER  = https://cognito-idp.eu-central-1.amazonaws.com/<POOL_ID>
```

Then walk the left navigation — the wizard's defaults are close, not exact:

| Menu | Setting | Value |
|---|---|---|
| Authentication → Sign-in | Password policy | minimum length **12**; uncheck the four character-class requirements (Phase 2: tighten) |
| Authentication → Sign-in | Multi-factor authentication | **No MFA** |
| Authentication → Sign-up | Attribute verification | **email** |
| Messaging → Email | Email provider | **Send email with Cognito** — the 50/day default sender, no SES needed here |

**CLI equivalent:**

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

**Console — Branding → Domain.** The wizard usually created a Cognito prefix domain
already; if the page shows none, **Create Cognito domain** with prefix
`ticket-waiting-room-<account-id>`. Copy the full domain into `TOKEN_URL` as
`https://<domain>/oauth2/token`.

**CLI equivalent:**

```bash
aws cognito-idp create-user-pool-domain --user-pool-id $POOL_ID --domain ticket-waiting-room-<account-id>
TOKEN_URL=https://ticket-waiting-room-<account-id>.auth.eu-central-1.amazoncognito.com/oauth2/token
```

The prefix must be globally unique, hence the account id. This is *not* the hosted UI being
adopted: there is no frontend to use it from, and an app client nothing exercises is
configuration pretending to be architecture. The domain exists because `client_credentials`
needs a token endpoint, and the token endpoint lives under the domain.

### 3. Resource server and scope

**Console — Branding → Domain → Resource servers → Create a resource server.** (It lives
under *Domain*, not *Applications*, because scopes belong to the authorization server.)

| Field | Value |
|---|---|
| Resource server name | `Ticketing services` |
| Resource server identifier | `ticketing` |
| Custom scopes → Scope name | `ops` |
| Custom scopes → Description | `Operator and saga access` |

**Create.** The *Custom scopes* column now reads `ticketing/ops`.

**CLI equivalent:**

```bash
aws cognito-idp create-resource-server --user-pool-id $POOL_ID \
  --identifier ticketing --name "Ticketing services" \
  --scopes ScopeName=ops,ScopeDescription="Operator and saga access"
```

`ticketing/ops` is the string in `TicketingScopes.OPS`. It is configured here, checked in
four services, and read by no compiler — the same class of contract as `SeatTableSchema`.

### 4. Two app clients

**4a. `ticket-web` — Console — Applications → App clients → `ticket-web`.** The wizard
created it. **Edit** the *App client information* and check:

| Setting | Value |
|---|---|
| Client secret | **none** — if the page shows one, the wrong application type was chosen; delete the client and create a new one with type *Single-page application* |
| Authentication flows | ✅ **Sign in with username and password: ALLOW_USER_PASSWORD_AUTH** · ✅ refresh tokens · SRP may stay on |
| Access token expiration / ID token expiration | 60 minutes |
| Login pages | leave as created; nothing uses them |

Copy **Client ID** → `WEB_CLIENT`.

**4b. `ticket-ops` — Console — App clients → Create app client.**

| Field | Value |
|---|---|
| Application type | **Machine-to-machine application** |
| Name your application | `ticket-ops` |
| Custom scopes | ✅ **`ticketing/ops`** (offered here because step 3 already exists; if the list is empty, the resource server was not created) |

**Create app client.** Open it: copy **Client ID** and **Client secret** → `OPS_CLIENT_ID`,
`OPS_CLIENT_SECRET`. The secret is shown in full on this page; it goes into the EventBridge
connection in step 8 and into your shell for step 6 — never into a file in the repo. If the
scope is missing after creation: **Login pages → Edit → Custom scopes**.

**CLI equivalent:**

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

**Console — Users → Create user**, twice:

| Field | alice | bob |
|---|---|---|
| Invitation message | **Don't send an invitation** | same |
| Email address | `alice@example.test` — or a real address you can read, for claim 10 | `bob@example.test` |
| Mark email address as verified | ✅ | ✅ |
| Temporary password | **Set a password** → `correct-horse-battery` | same |

The console creates both in `FORCE_CHANGE_PASSWORD` state, so the first sign-in would be
a challenge rather than a token. One CLI command per user makes the password permanent —
the console cannot:

```bash
for u in alice bob; do
  aws cognito-idp admin-set-user-password --user-pool-id $POOL_ID \
    --username $u@example.test --password 'correct-horse-battery' --permanent
done
```

**CLI equivalent (create + permanent password in one go):**

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

The console has no "give me a token" button, so this step is CLI only. (There is also
`aws cognito-idp get-client-token`, which mints an M2M token with no domain at all — but
the EventBridge connection in step 8 uses the token *endpoint*, so the domain is needed
regardless, and the curl below exercises the same path the saga will.)

```bash
# Customer: one call returns both kinds of token.
aws cognito-idp initiate-auth --client-id $WEB_CLIENT --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=alice@example.test,PASSWORD=correct-horse-battery \
  --query 'AuthenticationResult.[AccessToken,IdToken]' --output text
# → ALICE_AT  ALICE_IT

# Operator: client credentials.
OPS_AT=$(curl -s -X POST $TOKEN_URL -u "${OPS_CLIENT_ID}:${OPS_CLIENT_SECRET}" \
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

*Session of 2026-09-12. Stack up at 14:27:57 local; RDS, ElastiCache and the ALB all
available by 14:32:42 (under five minutes, started together); four targets healthy at
14:36:44, zero stopped tasks. Claims verified so far are filled; the rest await the
`/purchase` half.*

| # | Claim | Result |
|---|---|---|
| 1 | anonymous → 401 from the service, `WWW-Authenticate: Bearer` | **Yes.** Through the ALB with the edge secret and no token: `401`, `WWW-Authenticate: Bearer resource_metadata=…` — the service's own challenge, not API Gateway's |
| 2 | customer seeding → 403; **no line in the seat-inventory log group** | **Yes.** alice → `403`; operator → `201 {"created":2}`. **Zero** lines matching 403/forbidden/denied in `/ecs/seat-inventory-service` — the request died at the filter |
| 3 | 50 concurrent joins as one identity → 1 token, count 50 | **Yes.** `50 22da9c96-…` — one token, count 50, through API Gateway → ALB → Fargate → ElastiCache |
| 4 | customer admit → 403; operator admit → 200 | **Yes.** alice `403`; operator `200 {"admitted":1}` |
| 5 | purchase: `customerRef` = alice's `sub`, body value ignored | **Yes.** Body carried `"customerRef":"forged"`; the execution input carried `33849842-…` and `email` from the ID token's claims. Saga `SUCCEEDED` in 8 s: `HoldSeat → CreateOrder → ChargePayment → ConfirmPurchase → EnqueueTicketIssuance → NotifyPurchaseConfirmed`. Order `PAID`, seat `SOLD`, ticket issued, all DLQs empty |
| 6 | retry → `ExecutionAlreadyExists`, order unchanged | **Yes.** `400 {"error":"…#ExecutionAlreadyExists"}` from the `4\d{2}` integration response; the first execution untouched |
| 7 | bob → 404 ×3, byte-identical to an unknown id | **Yes, with one correction to the claim.** Order → `404 {"detail":"No order …"}`, QR → `404 {"detail":"No ticket …"}`, both identical to an unknown id. The tickets *listing* → **`200 []`** — the designed answer (ownership is in the query, an empty list is indistinguishable from "not issued yet"), and the claim as written was wrong, not the code. Alice's QR: `READY`, presigned URL served a 1,185-byte PNG |
| 8 | ID token at a service → 401 | **Yes.** `GET /orders/{id}` with alice's ID token → `401` through the whole edge |
| 9 | customer `POST /orders` → 403 | **Yes.** `403` at the filter |
| 10 | mail to the verified claim | **Yes, in two steps.** Purchase 1: SES **rejected** `alice@example.test` — *"Email address is not verified"* — the sandbox doing what `runbook-notifications.md` said it would; the message retried and landed in the DLQ, which is at-least-once working. The address had come from the verified *claim*, not the body — the design held; delivery needed an SES-verified recipient. alice's email was re-pointed at the `NOTIFICATION_TO` identity; purchases 2–4: `sent purchase confirmation` ×3, no rejection |
| 11 | saga's calls carry the M2M `sub` | **Yes, by construction rather than by log line.** The services do not log the caller. But `POST /orders` and `/payment` are ops-scoped — alice's token gets `403` on them (claim 9) — and the saga created and charged four orders through the same edge. Only a token carrying `ticketing/ops` can have done that, and only `ticket-ops` can mint one. The connection went `AUTHORIZED` within a minute of creation |
| 12 | first-token latency vs. steady state | **12.8 s** for the first token a task ever sees, **0.50 s** for the next — locally it was 629 ms vs 6.6 ms. The 20× spread on a 0.25-vCPU task is the discovery + JWKS fetch *plus* the JIT compiling the whole Nimbus/Security stack on a cold JVM. Startup itself was 1.4 s (`Started … in 1.447 s`) — the supplier kept the pool off the boot path exactly as intended; it moved the cost onto the first customer instead. See *Follow-up* |
| 13 | target healthy on 401 | **Yes.** All four targets `healthy` within ~2 min of service creation, with `GET /` answering `401` behind the `200-499` matcher |

### Follow-up from claim 12

A 12.8 s first request is not acceptable for the first customer after every deploy, and the
fix is small: warm the decoder on `ApplicationReadyEvent` — decode nothing, just trigger
the JWKS fetch — so startup still never *waits* on the pool but the cost lands before the
first request rather than on it. Not done in this session; recorded rather than hidden.

### Traps hit in the `/purchase` half

- **`StartExecution` returns no `name`.** The first 202 body had `"orderId": ""`. Derive
  it from `executionArn` instead — and in Velocity that takes two `#set`s, because
  arithmetic is not allowed inside a method argument
  (`$arn.substring($arn.lastIndexOf(":") + 1)` is a template error;
  `#set($cut = … + 1)` then `$arn.substring($cut)` is not).
- **A stage deployment takes seconds to propagate.** Two purchases fired within a second
  of `create-deployment` got `500 Internal server error` from a template that
  `test-invoke-method` showed working perfectly. Wait, or test-invoke first — it prints the
  execution log, which the deployed stage does not.
- **`HeadObject` on a missing key is 403 without `s3:ListBucket`.** `TicketQrStore.exists()`
  catches `NoSuchKeyException`, so alice's QR answered **500** while the object did not yet
  exist. Latent since slice 5 — the Lambda always won the race then — and exposed now
  because **the `qr-generation` event source mapping had been left `Disabled` by a previous
  teardown**. Fixed both: `ticket-issuance-task-role` gains `s3:ListBucket` on the bucket
  with `s3:prefix = tickets/*`; the mapping is re-enabled. Add both to
  `runbook-ticket-issuance.md`'s bring-up.
- **Changing a user's email moves their sign-in alias.** After `admin-update-user-attributes`,
  `admin-get-user --username alice@example.test` is `UserNotFoundException`; the user is
  reachable by `sub`, and signs in with the new address. Tokens must be re-minted.

### Traps hit in the first half

- **`$lb` unquoted is one argument in zsh.** Building `--load-balancers … --health-check-grace-period-seconds 120`
  in a variable and expanding it bare handed the whole string to `--tags`. Six for six now;
  the rule is unchanged, the runbook just keeps proving it.
- **`ticket-saga-role` did not need updating.** Its policy already wildcards
  `connection/ticket-services/*` and `secret:events!connection/*`, so a recreated connection
  needs no IAM change — better than `runbook-checkout-saga.md` §4 feared.
- **The EventBridge OAuth connection went `AUTHORIZING → AUTHORIZED` inside a minute**, on
  the body-parameter format written above — predicted as a trap, not hit.
- **Your public IP had changed** (`<my-ip>`, was `<my-ip>`). Added on 5432 to
  `checkout-db-sg` (needed to create `ticketing`) and on 8080 to the four task SGs; the old
  rule is left in place.

## What It Cost

Cognito's pool, domain, resource server and the public client are free (Essentials tier,
the default for new pools: 10,000 MAU free, $0.015/MAU after). The authorizer and the Step
Functions integration are free. **M2M token requests are billed: $0.00225 per successful
token request**, no free tier, no per-client charge (pricing page, read 2026-09-06). At this
traffic it is a handful per session — EventBridge caches the token for its hour of life and
the operator fetches one — so a full session is under a cent. Confirm against the bill
afterwards; the number is the one cost in this slice that is not zero.

Standing: **$0**. The pool is the first resource in this project that is *never* torn down,
and that is fine because it costs nothing — but destroying it would invalidate every user,
both client ids (one of which is inside the EventBridge connection) and the issuer baked
into four task definitions. Leave it.

## Traps Predicted — reconciled after the session

Written before the session so they could be checked off rather than rediscovered. Verdicts
in bold; the ones that bit are expanded under *Traps Hit* in the Results section.

- **HIT (locally, before the session).** `NimbusJwtDecoder.withIssuerLocation(…).build()` is eager. It fetches the issuer's
  discovery document at construction. Found locally by a failing `contextLoads` — the
  starter wraps it in `SupplierJwtDecoder`, which is what Boot does. Consequence on AWS: the
  first request each task serves pays the JWKS fetch. Measure it (claim 12).
- **NOT HIT.** The Cognito authorizer accepted the bare ID token on `/purchase` first time; `Bearer` was never needed there. The Cognito authorizer's token format. The `ticket-users` authorizer is configured on
  `method.request.header.Authorization`; whether it accepts `Bearer <token>` or wants the
  bare token is the kind of thing that costs twenty minutes. The verify block passes the
  bare ID token to `/purchase` and `Bearer` everywhere else. Record which worked.
- **NOT HIT, by construction** — every purchase sent the ID token. `$context.authorizer.claims.email` is only there for an ID token. Send an access token
  to `/purchase` and the claim resolves to an empty string, the template still renders, the
  saga runs, and the confirmation goes to the fallback address — silently. That is why
  step 6's claim table exists.
- **NOT HIT.** Connection `AUTHORIZED` within a minute on the format below. EventBridge's OAuth body parameters vs. Cognito's token endpoint. Cognito accepts the
  client id and secret in the body for `client_credentials`; the connection is configured
  that way above. If the saga's first `http:invoke` fails with a connection error, check
  the connection's status and `describe-connection`'s `StateReason` before touching IAM.
- **NOT HIT this session.** `Missing Authentication Token` still means an unmatched path — `runbook-alb-apigateway.md`
  recorded this for `GET /` and it is more misleading now that authentication genuinely
  exists. A 403 with that message from API Gateway is a routing problem; a 401 from a
  service is an authentication one. Tell them apart by the `WWW-Authenticate` header, which
  only the service sends.
- **NOT HIT** — the `4\d{2}` integration response mapped it to a clean 400 first time. `ExecutionAlreadyExists` surfaces as a 400 from the `AWS` integration unless an
  integration response maps it. The verify block expects 400; if it comes back as 500,
  add a selection pattern for it rather than changing the design.
- **NOT HIT.** Four targets healthy in ~2 min behind `200-499`. The ALB health check sees 401 now. `200-499` accepts it. If a target group was ever
  created with a narrower matcher, the service will register unhealthy for a reason that
  looks like an auth failure and is not one.
- **HIT (while verifying step 1).** zsh mangles `"$VAR:word"`. Inside double quotes zsh still reads `:h`, `:t`, `:r`…
  after a bare parameter as a *modifier*, so `-u "$OPS_CLIENT_ID:wrong"` sent curl a
  client id with three characters eaten. Hit while verifying the token endpoint with a
  deliberately bad secret. Every `user:secret` in this runbook now braces both halves —
  `"${OPS_CLIENT_ID}:${OPS_CLIENT_SECRET}"` — which is the fifth zsh trap across the
  runbooks and the same rule as the other four: quote and brace anything a shell might
  read as syntax.
- **HELD, as designed.** Two tokens for the customer. The ID token opens `/purchase`; the access token opens
  everything else. A client sending the wrong one gets a 401 in both directions (claim 8 is
  the service refusing the ID token). This is Cognito's design — the ID token carries
  identity for the client, the access token carries authorization for APIs — and the
  runbook names it rather than hiding it behind a helper.

## Teardown

**Done 2026-09-12 15:18.** Five ECS services (`--force`), then the ALB (its listener and
rules with it; the four target groups stay), then RDS with `--skip-final-snapshot
--delete-automated-backups`, then ElastiCache. The notifications DLQ was purged of order 1's
undeliverable mail. The five superseded task-definition revisions were deregistered and
their images deleted from ECR, which is back to ~0.8 GB. The `qr-generation` event source
mapping is **left enabled** — that is its correct standing state, and a previous teardown
had left it off. Standing cost after: ECR only.

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
