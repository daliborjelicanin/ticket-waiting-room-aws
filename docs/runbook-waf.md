# Runbook — WAF on the Purchase Endpoint

Slice 6, last required item. Puts a WAFv2 web ACL on the `ticket-edge` `dev` stage with
two rules scoped to `POST /events/{eventId}/purchase`: a per-IP rate limit and AWS Bot
Control. This is the anti-scalping requirement from CLAUDE.md, and it is the thing the
rest of the edge has been built around — `X-Edge-Secret` on the ALB exists *only* so
that traffic cannot reach a service without passing this ACL (`runbook-cognito-auth.md`,
Phase 2 notes), and the stage's 50/100 method throttling was set so the difference between
"throttled" and "blocked" is visible once WAF lands (`runbook-alb-apigateway.md` §7).

Session-only: a web ACL bills monthly, prorated, and a forgotten one is ~$17/month —
above the budget alert. Teardown at the bottom. Prerequisites: `ticket-edge` and its
`dev` stage (`runbook-alb-apigateway.md` §7) and the Cognito authorizer on `/purchase`
(`runbook-cognito-auth.md` §10). **Nothing else needs to be up** — see *What To Bring Up First*.

## Design Decisions Made Before Touching the Console

**Attach to the API Gateway stage, not the ALB.** Both are legal WAFv2 targets. The
purchase route is APIGW → Step Functions with no ALB in the path, so an ACL on the ALB
would miss the one endpoint this slice is named after. Attaching at the stage also means
WAF is evaluated **before the Cognito authorizer**: a flood of bogus tokens is dropped
before it costs an authorizer invocation. (WAF cannot attach to a WebSocket API at all;
the `$connect` stampede is handled by the DynamoDB service integration, not here.)

**Both rules carry a scope-down statement on the purchase path.** Not for cost first —
though Bot Control bills per request *analysed*, so scoping it is what keeps a load test
cheap — but for correctness: the saga's own `http:invoke` calls come back in through
`/{proxy+}` on this same stage with a non-browser user agent. An unscoped Bot Control
rule would classify the saga as `CategoryHttpLibrary` and block the checkout from inside.
The plan line says "on the purchase endpoint" and the scope-down is what makes that literal.

**Rate limit keyed on source IP, 100 requests per 60 s.** The per-*identity* limit already
exists — one live queue place per `sub`, atomic in `join.lua` — so WAF's job is the
pre-authentication layer: an address hammering `/purchase` is refused before any token is
looked at. 100/min is far above what a human does and far below what a script does, and
the 60 s window (the shortest offered; 120/300/600 are the others) makes the demo
observable in a minute rather than five. The variant worth knowing about: WAFv2 can
aggregate on a **custom key** (a header, cookie, query argument, JA3 fingerprint …), so
`Authorization` as the key would give a per-token limit at the edge. Not done here —
it would duplicate `join.lua`'s rule with a weaker mechanism.

**The rate rule answers 429 with its own body, not the default 403.** Three different
things on this stage can refuse a request: the authorizer (`401 Unauthorized`), the
stage throttle (`429 Too Many Requests`), and WAF (`403 Forbidden` by default). A custom
response — `429` with body `{"message":"rate limited by WAF"}` — is free and is the only
way the verification below can *prove* which layer answered. Bot Control keeps the plain 403.

**Bot Control starts in Count mode.** The common inspection level blocks
`SignalNonBrowserUserAgent` and `CategoryHttpLibrary` by default, and every verification
command in every runbook in this repository is `curl`. Build with the rule group's action
overridden to Count, verify the rate rule, then flip Bot Control to enforce and verify it
separately with a browser `User-Agent`. Doing it the other way round produces twenty
minutes of "the API is broken". The same applies to slice 7's k6 run: either send a
browser UA or leave Bot Control in Count for the duration.

**Sampled requests instead of full logging.** WAF logging needs a CloudWatch log group
named `aws-waf-logs-*` (or Firehose/S3) and is billed as vended logs. Sampled requests are
free, kept for three hours, and show the matched rule and the labels applied — enough for
the runbook and the *Results* table. Per-rule CloudWatch metrics (`AWS/WAFV2`
`BlockedRequests`/`CountedRequests`) are free and become slice 7 dashboard widgets.

## Target Topology

```
curl ──► WAF web ACL ticket-edge-waf (REGIONAL, eu-central-1)
           │  default action: Allow
           │  0  purchase-rate-limit   rate-based, IP, 100 / 60 s
           │        scope-down: method POST AND path ~ ^/(dev/)?events/[^/]+/purchase$
           │        block → 429 {"message":"rate limited by WAF"}
           │  1  purchase-bot-control  AWSManagedRulesBotControlRuleSet (COMMON)
           │        same scope-down; Count first, then enforce (403)
           ▼
         API Gateway REST ticket-edge, stage dev
           ├─ POST /events/{eventId}/purchase  → Cognito authorizer → Step Functions
           └─ ANY  /{proxy+}                   → ALB (untouched by either rule)
```

## What To Bring Up First

Nothing. The purchase route needs no ALB, no ECS task, no RDS: an unauthenticated `POST`
reaches the authorizer and comes back `401`, and once WAF trips it comes back `429`
instead. That flip is the whole rate-limit proof, at $0 beyond the ACL itself. The
Bot Control proof is the same request with and without a browser `User-Agent`. Only the
optional last check ("a legitimate purchase still goes through") needs the full stack,
and it is a repeat of `runbook-cognito-auth.md` §11 claim 5 — worth doing only if the
stack is up for another reason.

## Console Steps

Everything in **eu-central-1**, signed in as the daily IAM user. Console labels below
are as of September 2026; the WAF console gets re-skinned more often than most, so if a
label has moved, the *field value* is what matters — the values are the design.

### 0. Get to the right place

1. Search bar → **WAF & Shield** → left nav **Web ACLs**.
2. The Web ACLs list has **its own region dropdown at the top of the page**, separate
   from the console's global region selector. Set it to **Europe (Frankfurt)**. A web ACL
   is a regional object; created in the wrong region it is valid, billable, and unable
   to see `ticket-edge`.
3. The list should be empty (the tag sweep on 2026-09-12 found none). Click
   **Create web ACL**.

### 1. Wizard step 1 — Describe web ACL and associate it to AWS resources

**Web ACL details**

| Field | Value |
|---|---|
| Resource type | **Regional resources** (the other radio is *Amazon CloudFront distributions* — that would silently switch scope to global/us-east-1) |
| Region | **Europe (Frankfurt)** — re-check it here, it is shown again |
| Name | `ticket-edge-waf` |
| Description | `slice 6 - rate limit and bot control on POST /events/id/purchase` (the field rejects `*`, `{` and `}`) |
| CloudWatch metric name | auto-fills to `ticket-edge-waf`; leave it |

**Associated AWS resources — optional**

1. **Add AWS resources**.
2. In the dialog, resource type radio → **Amazon API Gateway REST APIs**.
3. The table lists one row: **`ticket-edge - dev`** (name and stage). Tick it → **Add**.
   If the table is empty, the region dropdown in step 0 was wrong.

**Web request body inspection** — leave the default (16 KB). Nothing here inspects a body.

**Web ACL tags — optional** → **Add new tag** → Key `project`, Value
`ticket-waiting-room`. This is what the teardown sweep searches for.

**Next.**

### 2. Wizard step 2 — Add rules and rule groups: the rate rule

**Rules → Add rules ▾ → Add my own rules and rule groups.**

**Rule type:** **Rule builder** (the alternatives are *IP set* and *Rule group*).

**Rule**

| Field | Value |
|---|---|
| Name | `purchase-rate-limit` |
| Type | **Rate-based rule** (not *Regular rule*) |

**Rate-based rule settings**

| Field | Value |
|---|---|
| Rate limit | `100` |
| Evaluation window | **1 minute (60 seconds)** |
| Request aggregation | **Source IP address** |
| Scope of inspection and rate limiting | **Only consider requests that match the criteria in a rule statement** |

Choosing the second radio opens the **statement builder** under it.

**If a request** ▾ → **matches all the statements (AND)**. Two statement blocks appear.

*Statement 1*

| Field | Value |
|---|---|
| Inspect | **HTTP method** |
| Match type | **Exactly matches string** |
| String to match | `POST` |
| Text transformation | **None** |

*Statement 2*

| Field | Value |
|---|---|
| Inspect | **URI path** |
| Match type | **Matches regular expression** |
| Regular expression | `^/(dev/)?events/[^/]+/purchase$` |
| Text transformation | **None** |

(If *Matches regular expression* is not in the dropdown, the console is offering only
*Matches pattern from regex pattern set*: create a pattern set named `purchase-path`
with that one regex from the left-nav **Regex pattern sets** page, come back, and select
it. Same statement, one more object to tear down.)

**Then — Action**

| Field | Value |
|---|---|
| Action | **Block** |
| Custom response | tick **Enable** |
| Response code | `429` |
| Response body | **Create new** (radio, beside *Choose from existing*) |
| — Response body object name | `rate-limited` |
| — Content type | **JSON** |
| — Response body | `{"message":"rate limited by WAF"}` |
| Response headers | **Add new custom header** → Key `Retry-After`, Value `60` |

Leave *Custom request header* alone (that is for the Allow/Count actions). **Add rule.**
Back on the rules page it appears with Capacity **2**.

### 3. Wizard step 2, continued — Bot Control

**Rules → Add rules ▾ → Add managed rule groups.**

1. Expand **AWS managed rule groups**. The table has *Free rule groups* and a paid
   section; **Bot Control** is in the paid one, marked **$10.00/month + $1.00/million requests**.
2. On the **Bot Control** row, switch **Add to web ACL** on. An **Edit** button appears
   on the same row (in some renders it is a pencil icon) — click it. Do not skip this:
   added without editing, it inspects every request on the stage at Common level, in
   Block — which is the configuration that blocks the saga (see *Design Decisions*).

**Bot Control rule group configuration**

| Field | Value |
|---|---|
| Inspection level | **Common** |
| Scope of inspection | **Only inspect requests that match a scope-down statement** |

The same statement builder as §2 opens. Enter the same two statements: *matches all
the statements (AND)* → `HTTP method` *Exactly matches string* `POST` → `URI path`
*Matches regular expression* `^/(dev/)?events/[^/]+/purchase$`, both **None** transform.

**Rules** (the list of the group's own rules, ~10 rows: `CategoryAdvertising`,
`CategoryHttpLibrary`, `SignalNonBrowserUserAgent`, … each with its default action)

| Field | Value |
|---|---|
| Override all rule actions ▾ (above the list) | **Count** |

Every row's *Action* column now reads *Count (override)*. This is the first-pass
setting; §7 removes it. **Save rule.**

3. Back on *Add managed rule groups*, click **Add rules** (bottom right). The rules
   page now lists both, and **Web ACL capacity units used** reads **62 / 1500** — Bot Control common is 50, the rate rule with its scope-down 12.

**Default web ACL action for requests that don't match any rules:** **Allow**.
**Token domain list** — leave empty (CAPTCHA/Challenge only). **Next.**

### 4. Wizard steps 3–5

**Step 3 — Set rule priority.** `purchase-rate-limit` above `purchase-bot-control`.
Select a row and use *Move up* / *Move down* if the order came out reversed. **Next.**

**Step 4 — Configure metrics.** Both rules already have their own metric names
(`purchase-rate-limit`, `purchase-bot-control`). **Request sampling options → Enable
sampled requests** — keep it, it is what the verification reads. **Next.**

**Step 5 — Review and create web ACL.** Check four things on the summary before
clicking: *Associated AWS resources* shows `ticket-edge - dev`; rule 1 action is **Block**
with a **custom response**; rule 2 shows **Count** override and a scope-down statement;
default action **Allow**. **Create web ACL.**

The list returns with `ticket-edge-waf`, capacity 62, one associated resource. It is
enforcing immediately. To see the association from the other side: **API Gateway →
APIs → ticket-edge → Stages → dev**; the *Stage details* panel has a **Web ACL** field
reading `ticket-edge-waf` (it can take about a minute to appear there).

### 5. Where to look while verifying

**WAF & Shield → Web ACLs → ticket-edge-waf → Overview.** Two things on this page:

- **Traffic overview** — per-rule graphs of *Allowed*, *Blocked*, *Counted*. Set the
  time range to *1 hour*. A minute or two of lag.
- **Sampled requests** — the table under it, filtered by rule via the dropdown. Each row
  has time, source IP, **URI**, method, rule name, **action**. Expanding a row shows the
  headers and, for Bot Control, the **labels**
  (`awswaf:managed:aws:bot-control:signal:non_browser_user_agent` etc.). This is the
  view that resolves the `/dev` question: the URI column shows exactly the path WAF
  matched the regex against.

**Flipping Bot Control to enforce** (§7): **Rules tab → tick
`purchase-bot-control` → Edit → Override all rule actions ▾ → Remove all overrides**
(the choice may be labelled *None* or *Use the rule group's actions*) → **Save rule**.
There is no second confirmation; the change is live within seconds.

### CLI equivalent


```bash
API_ID=<rest-api-id>
SCOPE_DOWN='{"AndStatement":{"Statements":[
  {"ByteMatchStatement":{"SearchString":"POST","FieldToMatch":{"Method":{}},"PositionalConstraint":"EXACTLY","TextTransformations":[{"Priority":0,"Type":"NONE"}]}},
  {"RegexMatchStatement":{"RegexString":"^/(dev/)?events/[^/]+/purchase$","FieldToMatch":{"UriPath":{}},"TextTransformations":[{"Priority":0,"Type":"NONE"}]}}]}}'

cat > /tmp/waf-rules.json <<EOF
[
 {"Name":"purchase-rate-limit","Priority":0,
  "Statement":{"RateBasedStatement":{"Limit":100,"EvaluationWindowSec":60,"AggregateKeyType":"IP","ScopeDownStatement":$SCOPE_DOWN}},
  "Action":{"Block":{"CustomResponse":{"ResponseCode":429,"CustomResponseBodyKey":"rate-limited","ResponseHeaders":[{"Name":"Retry-After","Value":"60"}]}}},
  "VisibilityConfig":{"SampledRequestsEnabled":true,"CloudWatchMetricsEnabled":true,"MetricName":"purchase-rate-limit"}},
 {"Name":"purchase-bot-control","Priority":1,
  "Statement":{"ManagedRuleGroupStatement":{"VendorName":"AWS","Name":"AWSManagedRulesBotControlRuleSet",
    "ManagedRuleGroupConfigs":[{"AWSManagedRulesBotControlRuleSet":{"InspectionLevel":"COMMON"}}],
    "ScopeDownStatement":$SCOPE_DOWN}},
  "OverrideAction":{"Count":{}},
  "VisibilityConfig":{"SampledRequestsEnabled":true,"CloudWatchMetricsEnabled":true,"MetricName":"purchase-bot-control"}}
]
EOF

aws wafv2 create-web-acl --region eu-central-1 --scope REGIONAL --name ticket-edge-waf \
  --cli-binary-format raw-in-base64-out \
  --description "slice 6 - rate limit and bot control on POST /events/id/purchase" \
  --default-action Allow={} --rules file:///tmp/waf-rules.json \
  --custom-response-bodies '{"rate-limited":{"ContentType":"APPLICATION_JSON","Content":"{\"message\":\"rate limited by WAF\"}"}}' \
  --visibility-config SampledRequestsEnabled=true,CloudWatchMetricsEnabled=true,MetricName=ticket-edge-waf \
  --tags Key=project,Value=ticket-waiting-room

ACL_ARN=$(aws wafv2 list-web-acls --region eu-central-1 --scope REGIONAL --query "WebACLs[?Name=='ticket-edge-waf'].ARN" --output text)
aws wafv2 associate-web-acl --region eu-central-1 --web-acl-arn $ACL_ARN \
  --resource-arn arn:aws:apigateway:eu-central-1::/restapis/$API_ID/stages/dev
```

**`--cli-binary-format raw-in-base64-out` is not optional.** `ByteMatchStatement.SearchString`
is a blob, and AWS CLI v2 decodes blob parameters *from base64* by default — so a literal
`"POST"` is stored as the three bytes that `POST` base64-decodes to, the method never
matches, both scope-downs silently fail, and every request falls through to the default
action. `get-web-acl` then prints the stored value back as `POST` (it base64-*encodes* on
output), which is why the bug is invisible until you know to look: the correct read-back
is `UE9TVA==`. See *Traps Hit*.

Flipping Bot Control from Count to enforce later is `update-web-acl` with
`"OverrideAction":{"None":{}}`, the same `--cli-binary-format`, the *entire* rule set, and
the current `--lock-token` from `get-web-acl` — every WAFv2 write is an
optimistic-concurrency call, which is why the console is the easier tool for the toggle in §7.

### 6. Verify the rate rule — nothing else running

```bash
API=https://$API_ID.execute-api.eu-central-1.amazonaws.com/dev
P() { curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-w/purchase" "$@"; }
```

Every numbered line is a claim; *Results* is where each becomes a number.

```bash
# 1. the ACL is on the stage, and it passes a single request through to the authorizer
aws wafv2 get-web-acl-for-resource --region eu-central-1 \
  --resource-arn arn:aws:apigateway:eu-central-1::/restapis/$API_ID/stages/dev --query WebACL.Name
P                                                          # 401 — authorizer, not WAF

# 2. the flip: 150 sequential posts → 401s, then 429s, in that order, with WAF's body
for i in $(seq 150); do P; done | uniq -c                  # e.g. "1xx 401" then "xx 429"
curl -si -X POST "$API/events/evt-w/purchase" | grep -iE '^HTTP|retry-after|message'
#    HTTP/2 429 · retry-after: 60 · {"message":"rate limited by WAF"} — not "Too Many Requests"

# 3. the rule is scoped: the same address is NOT limited on any other path while blocked.
#    With the ALB torn down these land on ANY /{proxy+} and 500 — the claim is "not 429"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-w/queue"      # 500 (401 with the stack up), never 429
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-w/purchase/x" # 500 — [^/]+ keeps sub-paths out of scope
curl -s -o /dev/null -w '%{http_code}\n' -X GET  "$API/events/evt-w/purchase"   # 500 — GET has no method here, falls to /{proxy+}

# 4. it lets go: after the window, the same request is 401 again
sleep 90; P                                                # 401
```

Expect the block to start **late and end late**: a rate-based rule is evaluated on a
trailing window and applied on a ~10–30 s cadence, so the first 429 typically arrives a
few dozen requests past 100, and it persists until the trailing count is under the limit
again. Record the actual counts from `uniq -c` — that lag is the number worth having.

Then **WAF & Shield → Web ACLs → ticket-edge-waf → Overview**: the *Sampled requests*
table lists the blocked posts with rule `purchase-rate-limit` and shows the exact URI path
WAF inspected (with or without `/dev` — resolve the regex question here).

### 7. Verify Bot Control — flip to enforce

**Rules → `purchase-bot-control` → Edit → Rule group action: *Use the actions defined in
the rule group* (remove the Count override) → Save.** Wait for the rate rule to have let
go (claim 4), then:

```bash
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36'

# 5. curl is a bot: blocked before the authorizer, plain WAF 403
curl -si -X POST "$API/events/evt-w/purchase" | grep -iE '^HTTP|message'      # 403 {"message":"Forbidden"}

# 6. the same request wearing a browser user agent reaches the authorizer
P -A "$UA"                                                                     # 401

# 7. scoped: curl is still welcome everywhere else on the stage
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/events/evt-w/queue"     # 500 / 401, not 403
```

Sampled requests for claim 5 carry the labels
`awswaf:managed:aws:bot-control:signal:non_browser_user_agent` and
`…:bot:category:http_library`, with the terminating rule inside the group
(`SignalNonBrowserUserAgent` or `CategoryHttpLibrary`, whichever evaluates first).
Claim 7 is the one that protects the saga: `http:invoke`'s calls on `/{proxy+}` look
exactly like claim 7's request to WAF.

### 8. Optional — a real purchase still succeeds

Only if the stack is up anyway (ALB, four services, RDS, ElastiCache per their runbooks):
repeat `runbook-cognito-auth.md` §11 claim 5 with `-A "$UA"` added to the purchase
`curl`. `202`, saga `SUCCEEDED`, and a sampled request showing `purchase-rate-limit` and
`purchase-bot-control` both *allowed* is the complete claim: the edge refuses scripts and
floods and passes a person.

## Results, 2026-09-13

Built and verified by CLI from the repo, `eu-central-1`, with **nothing else running** —
no ALB, no task, no RDS, no cache. Stack cost for the session: the ACL alone.

| Resource | Value |
|---|---|
| Web ACL | `ticket-edge-waf` `fb20c212-b89c-44fb-8104-3ec5d02faf37`, REGIONAL, **62 WCU** |
| Associated | `arn:aws:apigateway:eu-central-1::/restapis/<rest-api-id>/stages/dev` |
| Rule 0 | `purchase-rate-limit` — IP, 100 / 60 s, Block → 429 `rate-limited` body, `Retry-After: 60` |
| Rule 1 | `purchase-bot-control` — Bot Control COMMON, scoped; Count first, then enforce |

| # | Claim | Result |
|---|---|---|
| 1 | ACL on the stage; a single post reaches the authorizer | `get-web-acl-for-resource` → `ticket-edge-waf`; `POST` → **401** |
| 2 | Flood flips 401 → 429 with WAF's body | 150 posts in 15 s → **150 × 401** (lag); a 90 s loop started ~40 s later → **983 × 429**; body `{"message":"rate limited by WAF"}`, `retry-after: 60`, `HTTP/2 429` |
| 3 | Scoped to `POST …/purchase` | while blocked: `POST /queue` **500**, `POST /purchase/x` **500**, `GET /purchase` **500** — all `/{proxy+}` → dead ALB; **none 429** |
| 4 | Releases | polled every 10 s after the loop: 429 ×5, **401 at +50 s** |
| 5 | Bot Control enforced: curl blocked before the authorizer | **403** `{"message":"Forbidden"}` |
| 6 | Same request with a Chrome UA reaches the authorizer | **401** `{"message":"Unauthorized"}` |
| 7 | curl still passes elsewhere | `POST /queue` → **500**, not 403 |

**The URI path carries the stage prefix**: sampled requests show `/dev/events/evt-w/purchase`,
so `(dev/)?` in the regex is the branch that fires. Keep the optional group anyway — the
regex is the one string that would otherwise have to change per stage.

**Bot Control's terminating rule on curl is `CategoryHttpLibrary`**, not
`SignalNonBrowserUserAgent` — sampled rows read
`AWS#AWSManagedRulesBotControlRuleSet#CategoryHttpLibrary`. In Count mode it produced
129 counted samples and no block; enforced, the same request is a 403 within 20 s of
the update. The default-action metric is the tell-tale for a scope-down that does not
match: `AllowedRequests` with `Rule=ticket-edge-waf` (the default action's own metric
name) climbing while both rule metrics stay absent.

**The rate rule's lag, measured**: 150 requests in 15 s did not trip it; the block was in
place by the time a second loop started ~40 s later, and it released ~50 s after the
last burst. So: ~30 s to engage, ~1 window to release. The console's *Traffic overview*
shows the same shape a couple of minutes behind.

**Verification did not reach `runbook-cognito-auth.md` claim 5** (a real purchase with
`-A "$UA"` succeeding through both rules) — the stack was down and the runbook says to
skip it unless it is up for another reason. Do it in the next full-stack session.

## Traps Hit

**AWS CLI v2 base64-decodes `SearchString`.** The first build matched nothing: 133
requests allowed by the default action, zero samples under either rule. Sampled requests
showed the right URI and method, `get-web-acl` showed `SearchString: POST` — and that is
the trap, because the CLI *encodes* on output, so the stored garbage reads back as the
value you typed. The proof was `printf POST | base64` → `UE9TVA==` ≠ what `get-web-acl`
printed. Fix: `--cli-binary-format raw-in-base64-out` on every `create-`/`update-web-acl`
(or `cli_binary_format = raw-in-base64-out` in `~/.aws/config`). The console never has
this problem, which is the argument for the console in a by-hand phase. Terraform's
`search_string` is a plain string and is also immune.

**`--description` rejects `*`, `{` and `}`** (`^[\w+=:#@/\-,\.][\w+=:#@/\-,\.\s]+…$`).
Two attempts before `POST /events/id/purchase`.

**`associate-web-acl` can throw `WAFUnavailableEntityException` immediately after
`create-web-acl`** — "couldn't retrieve the resource… Retry your request." One retry
after ten seconds succeeded. Eventual consistency, not a permissions problem.

**Sampled requests lag a minute or more** and the per-rule *sample* of a just-flipped
rule can still show the previous action for a while. Metrics are quicker to confirm a
match; samples are for reading URIs and labels.

## What It Cost

Read from the pricing page before the session and confirmed against the bill afterwards:
web ACL **$5.00/month**, each rule **$1.00/month** (the managed group counts as one),
Bot Control common **$10.00/month** — all prorated by the hour, so the ACL as built is
**≈ $0.024/hour**. Per request: $0.60 per million through the ACL, plus $1.00 per million
*analysed by Bot Control* — which, thanks to the scope-down, is only `/purchase` traffic.
Verification here is a few hundred requests. A two-hour session is about **$0.05**.

The number that matters for the cost ground rules: **a forgotten ACL is ~$17/month**,
the second most expensive thing that can be left behind after the ALB, and it is easy to
forget because it bills whether or not anything is associated with it. Teardown deletes
it; the CLI block above is one command to bring back.

## Traps Predicted

- **Wrong region inside the WAF console.** The page has its own region dropdown; the
  ACL must be `REGIONAL` in `eu-central-1` or `ticket-edge` is not offered as a resource.
- **URI path may or may not carry `/dev`.** It does (see *Results*); regex tolerates both.
- **Rate rule lag looks like a broken rule.** The first 429 comes late and the last one
  leaves late; count, do not eyeball.
- **429 collision with the stage throttle.** Both answer 429; only the body tells them
  apart. The loop in claim 2 is sequential (well under 50 rps) so the throttle never
  fires, but a parallel `xargs -P50` would trip both and confuse the count.
- **Bot Control blocks curl** — every runbook's verification, and k6 in slice 7. Count
  mode first; browser UA afterwards.
- **Bot Control unscoped would block the saga** (`http:invoke` is an HTTP library). The
  scope-down is a correctness requirement, not an optimisation.
- **`update-web-acl` needs a lock token** and the *entire* rule set — a partial update
  replaces the rules with the partial list. Use the console for toggles, the CLI for creation.

## Teardown

**Done 2026-09-13.** `disassociate-web-acl` → `get-web-acl` for the lock token →
`delete-web-acl`; `list-web-acls` → 0, `get-web-acl-for-resource` on the stage → none.
Nothing was ever created besides the ACL itself (the custom response body lives inside
it), so standing cost after is ECR only, as before. The ACL was up ~45 minutes.

```bash
aws wafv2 disassociate-web-acl --region eu-central-1 \
  --resource-arn arn:aws:apigateway:eu-central-1::/restapis/$API_ID/stages/dev
ACL_ID=$(aws wafv2 list-web-acls --region eu-central-1 --scope REGIONAL --query "WebACLs[?Name=='ticket-edge-waf'].Id" --output text)
LOCK=$(aws wafv2 get-web-acl --region eu-central-1 --scope REGIONAL --name ticket-edge-waf --id $ACL_ID --query LockToken --output text)
aws wafv2 delete-web-acl --region eu-central-1 --scope REGIONAL --name ticket-edge-waf --id $ACL_ID --lock-token $LOCK
aws wafv2 list-web-acls --region eu-central-1 --scope REGIONAL --query 'length(WebACLs)'   # 0
```

Console: Web ACLs → `ticket-edge-waf` → *Associated AWS resources* → disassociate the
stage → back to the list → Delete (it refuses while an association exists). Nothing else
this slice adds is billable: the stage, the authorizer and the route are unchanged and
free. Standing cost after: ECR only, as before.

## Notes for Phase 2 (Terraform)

- `aws_wafv2_web_acl` with the two rules as nested blocks, `aws_wafv2_web_acl_association`
  on `aws_api_gateway_stage.dev.arn`. The scope-down statement appears twice; a `locals`
  block or a `dynamic` keeps it single-sourced.
- Bot Control's `override_action { count {} }` vs `none {}` is the Count/enforce toggle —
  make it a variable so a load-test workspace can flip it without editing rules.
- The ACL is the strongest argument for the NLB + VPC Link question recorded in
  `runbook-cognito-auth.md`: with WAF real, the internet-facing ALB is the one path that
  bypasses it, held shut only by `X-Edge-Secret`. Decide on cost in Phase 2.
