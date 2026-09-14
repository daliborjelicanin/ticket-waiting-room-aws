# Runbook — ALB + API Gateway (REST) in Front of the Services

Slice 6, first item. Gives the Fargate services a **stable address** (they currently
sit behind per-task public IPs that change every deployment) and puts an API Gateway
REST stage in front of it, which is the thing WAF attaches to later in this slice.

Session-only: the ALB bills whether or not it serves traffic. Teardown checklist at the
bottom. Prerequisites: `runbook-ecs-fargate.md` (cluster, roles, SGs, task defs).

## Design Decisions Made Before Touching the Console

**Internet-facing ALB + shared-secret header, not VPC Link.** API Gateway **REST**
(v1) VPC Links only target **NLBs** — ALB-behind-VPC-Link is an HTTP API (v2) feature.
The three ways out are: add an NLB in front of the ALB (~$0.025/hr more, teaches
nothing new), switch this edge to an HTTP API (loses the REST-API features the rest of
the slice wants), or make the ALB internet-facing and authenticate API Gateway to it.
Chosen: internet-facing, with every listener rule requiring a secret header that only
API Gateway injects; the default action is a hard `403`. Recorded as a deliberate
Phase-1 simplification, in the same family as "no NAT Gateway".

**Listener is HTTP:80, not HTTPS.** A valid ACM cert needs a domain, and Route 53 is
explicitly out of scope for Phase 1. APIGW→ALB therefore runs unencrypted inside AWS's
network. Revisit in Phase 2 alongside the custom domain.

**Health checks use a matcher, not `/actuator/health`** — *superseded 2026-09-14, kept for
the record.* At the time no service had the actuator dependency, so no path returned 200
without side effects (`/` returned 404, and every real endpoint mutates something). Target
groups therefore health-checked `/` with success codes `200-499`, which proved "Tomcat is
up and routing" and nothing more. Slice 7 added the actuator through `observability-starter`
and moved all four target groups to `/actuator/health` with matcher `200`
(`runbook-observability.md` §7); the table in §2 below shows the original values.

**ALB spans two AZs, tasks stay in one.** An ALB requires subnets in ≥2 AZs; that
costs nothing extra. Cross-zone load balancing is on by default for ALB, so a target
set living only in `eu-central-1a` is served fine from both ALB nodes.

## Target Topology

```
curl → API Gateway REST stage (dev)
         └─ ANY /{proxy+}  HTTP_PROXY  + header X-Edge-Secret: <secret>
              → http://ticket-alb-<id>.eu-central-1.elb.amazonaws.com/{proxy}
                   └─ listener :80, rules match path AND header
                        10  /events/*/queue*        → waiting-room-tg      :8080
                        20  /events/*/seats/*       → seat-inventory-tg    :8080
                        30  /admin/events/*/seats   → seat-inventory-tg    :8080
                        40  /orders/*/tickets, /tickets/*/qr → ticket-issuance-tg :8080
                        50  /orders, /orders/*      → checkout-tg          :8080
                        default → fixed response 403
```

Path collisions are the reason rule **priority** matters here and not just as trivia:
`/orders/*/tickets` (issuance) is a strict subset of `/orders/*` (checkout), and
`/events/*/queue*` (waiting room) sits beside `/events/*/seats/*` (inventory). ALB
evaluates rules in ascending priority number and stops at the first match, so the
specific rule must carry the lower number. Getting 40 and 50 the wrong way round sends
ticket lookups to the checkout service, which answers `404` — a failure that looks like
missing data, not like a routing bug.

## What To Bring Up First

Minimum to make the slice meaningful is two live services behind two target groups:

- `seat-inventory-service` — needs only DynamoDB (standing, $0). Start here.
- `waiting-room-service` — needs an ElastiCache node (~$0.017/hr, ~8–10 min to create,
  see `runbook-waiting-room-redis.md`).

Create **all five** target groups and listener rules regardless — empty target groups
are free, and the point is to record the full routing table. `checkout-payment-service`
and `ticket-issuance-service` need RDS up (~$0.017/hr); bring them up only if you want
to verify rules 40 and 50 live this session.

---

## Console Steps

### 1. Security groups (EC2 → Security groups)

**Create** `ticket-alb-sg` in `<vpc-id>`:
- Inbound: **HTTP / TCP 80 / Source `0.0.0.0/0`**. It has to be open — API Gateway
  calls from AWS-managed addresses with no published range worth allowlisting. The
  secret header, not the SG, is what keeps the public out.
- Outbound: leave the default allow-all.

**Edit each service SG** (`seat-inventory-sg`, `waiting-room-sg`, and the checkout /
issuance ones if you start them) → **Edit inbound rules → Add rule**:
- Type **Custom TCP**, Port **8080**, Source **Custom → `ticket-alb-sg`** (pick the
  security group from the dropdown, *not* a CIDR).
- Keep the existing `<my-ip>/32` rule — direct-to-task curl is still the fastest way to
  tell "the service is broken" apart from "the routing is broken".

### 2. Target groups (EC2 → Target groups → Create target group)

Repeat five times. Only the name changes; every other field is identical.

- **Choose a target type: `IP addresses`.** Not "Instances" — `awsvpc` Fargate tasks
  are ENIs with no EC2 instance to register. Picking Instances here is unrecoverable;
  the type is immutable and you delete and start over.
- Target group name: `waiting-room-tg` / `seat-inventory-tg` / `checkout-tg` /
  `ticket-issuance-tg`
- Protocol **HTTP**, Port **8080**
- VPC `<vpc-id>`, Protocol version **HTTP1**
- **Health checks:** Protocol HTTP, Path `/`
- **Advanced health check settings** (expand — the defaults are all wrong for this):
  | Field | Value | Why |
  |---|---|---|
  | Port | Traffic port | |
  | Healthy threshold | **2** | default 5 × 30s = 2.5 min before a task serves traffic |
  | Unhealthy threshold | 2 | |
  | Timeout | 5 s | |
  | Interval | **10 s** | |
  | Success codes | **`200-499`** | `/` returns 404; see the design note above |
- **Next** → the "Register targets" page: register **nothing**, click **Create target
  group**. ECS fills the group in when the service attaches to it.
- After creation: **Attributes → Edit → Deregistration delay = `30`** seconds (default
  300). Directly determines how long teardown and redeploys hang.

### 3. Load balancer (EC2 → Load balancers → Create load balancer → Application Load Balancer)

- Name `ticket-alb`
- Scheme **Internet-facing**, IP address type **IPv4**
- **Network mapping:** VPC `<vpc-id>`, then tick **two** AZs —
  `eu-central-1a` with `<subnet-id>` (the one the tasks run in) plus any
  second AZ's default subnet. One AZ will not let you proceed.
- **Security groups:** `ticket-alb-sg` only — **remove the default SG** the wizard
  pre-selects, same trap as the ECS service wizard.
- **Listeners and routing:** HTTP : 80 → the wizard forces a target group as the
  default action, so point it at `seat-inventory-tg` for now and fix it in step 4.
- Tags: `project=ticket-waiting-room`
- **Create load balancer**, then wait for **Active** (~2–3 min). Copy the **DNS name**.

### 4. Listener rules (ALB → Listeners → HTTP:80 → Manage rules)

First **edit the default action**: change it from *Forward to seat-inventory-tg* to
**Return fixed response** → status `403`, content type `text/plain`, body
`Direct access denied`. The default is what unauthenticated internet traffic hits.

Then **Add rule** ×5. Each rule takes **two conditions ANDed together**:

1. **Path pattern** — the paths from the topology table
2. **HTTP header** — Header name `X-Edge-Secret`, value = your secret (generate one:
   `openssl rand -hex 16`)

...and one action: **Forward to** the matching target group. Set **Priority** explicitly
per the table (10/20/30/40/50) — leave gaps so a rule can be inserted later without
renumbering.

Notes on the path patterns:
- ALB's `*` matches **any** characters including `/`, so `/events/*/queue*` covers
  `/events/x/queue`, `/events/x/queue/{token}` and `/events/x/queue/admit`.
- A single rule holds up to 5 path patterns — rule 40 uses that for `/orders/*/tickets`
  plus `/tickets/*/qr`.
- Patterns are case-sensitive and max 128 chars.

### 5. Attach the ECS services to their target groups

The service must be **created** with load balancing configured. `UpdateService` can
change load balancer config for rolling deployments, but the console path for it is
inconsistent — and since these services are deleted every session anyway, recreating is
the cheaper move.

**ECS → Clusters → `ticket-waiting-room` → Services → Create**, identical to
`runbook-ecs-fargate.md` step 6, plus:

- **Load balancing → Use load balancing**
  - Load balancer type **Application Load Balancer**, **Use an existing load balancer**
    → `ticket-alb`
  - Container to load balance: `<service>-service 8080:8080`
  - **Use an existing listener** → `HTTP:80`
  - **Use an existing target group** → the service's TG (the wizard otherwise creates a
    new one with *Never expire*-style defaults and its own rule)
  - **Health check grace period: `120`** seconds. Non-negotiable — the services take
    ~39 s to boot, and with the default grace of 0 the ALB reports unhealthy before
    Spring finishes starting, ECS kills the task, and it crash-loops forever while the
    logs show a perfectly healthy startup.
- Networking: one subnet (`<subnet-id>`), the service's SG, **public IP
  Turned on** (still no NAT Gateway; the pull from ECR needs it).

Watch **Target groups → Targets** go `initial → healthy` (~1–2 min).

### 6. Verify the ALB directly, before API Gateway exists

```bash
ALB=ticket-alb-XXXXXXXX.eu-central-1.elb.amazonaws.com
SECRET=<the secret>

# no header -> the default action
curl -s -o /dev/null -w '%{http_code}\n' "http://$ALB/events/evt-alb-1/seats/A-1/hold"   # 403

# with header -> routed
curl -s -X POST -H "X-Edge-Secret: $SECRET" "http://$ALB/admin/events/evt-alb-1/seats" \
  -H 'Content-Type: application/json' -d '{"seatIds":["A-1","A-2","A-3"]}'
curl -s -X POST -H "X-Edge-Secret: $SECRET" "http://$ALB/events/evt-alb-1/seats/A-1/hold"
curl -s -X POST -H "X-Edge-Secret: $SECRET" "http://$ALB/events/evt-alb-1/queue"
```

The last two are the routing proof: same `/events/...` prefix, two different services.

### 7. API Gateway REST API (API Gateway → APIs → Create API)

- **REST API** → **Build** (not "REST API Private", not HTTP API, not the *Example API*)
- **New API**, name `ticket-edge`, **API endpoint type: Regional**. Edge-optimized
  fronts it with CloudFront in another region and adds latency for no benefit here.
- **Resources → Create resource**
  - Tick **Proxy resource**; Resource path `/`, Resource name `proxy` → path shows as
    `/{proxy+}`
  - Enable **CORS** only if you get to the optional static frontend later
  - **Create resource** — this auto-creates the `ANY` method
- The **ANY** method's integration setup:
  - Integration type **HTTP**
  - Tick **HTTP proxy integration**
  - HTTP method **ANY**
  - Endpoint URL: `http://${stageVariables.albDns}/{proxy}`
    — a literal DNS name here means editing the integration every session, because a
    recreated ALB gets a new DNS name. A stage variable makes the session's first task
    a one-field edit instead.
- **Method → Integration request → Edit → URL request headers parameters → Add**:
  - Name `X-Edge-Secret`, Mapped from `stageVariables.edgeSecret`
  - (a literal would be `'the-secret'`, single quotes included — but keep it in a stage
    variable so rotating it needs no redeploy)
- **Deploy API** → *New stage* → name `dev`
- **Stages → dev → Stage variables → Add**:
  - `albDns` = `ticket-alb-XXXXXXXX.eu-central-1.elb.amazonaws.com`
  - `edgeSecret` = the secret from step 4
- **Stages → dev → Default method throttling**: rate `50`, burst `100`. Cheap blast
  radius control, and it makes the difference visible when WAF's rate limit lands on
  this same stage later in the slice.

Copy the **Invoke URL**: `https://<api-id>.execute-api.eu-central-1.amazonaws.com/dev`

### 8. Verify through the whole edge

> **Since `runbook-cognito-auth.md`**, every one of the curls below answers **401** without
> an `Authorization: Bearer <access token>` header, and the admin seed needs the ops scope.
> The routing they prove is unchanged; add the header from that runbook's step 6.

```bash
API=https://<api-id>.execute-api.eu-central-1.amazonaws.com/dev

curl -s -X POST "$API/admin/events/evt-edge-1/seats" \
  -H 'Content-Type: application/json' -d '{"seatIds":["B-1","B-2","B-3"]}'
curl -s -X POST "$API/events/evt-edge-1/seats/B-1/hold"
curl -s      -X POST "$API/events/evt-edge-1/queue"
curl -s -o /dev/null -w '%{http_code}\n' "http://$ALB/events/evt-edge-1/seats/B-2/hold"  # still 403
```

Then the one that matters — **the concurrency proof has to survive the edge**:

```bash
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "$API/events/evt-edge-1/seats/B-2/hold" | sort | uniq -c
#       1 201
#      29 409
```

Same numbers as slice 1 against the raw task IP. Two hops, a load balancer and an API
gateway added, and the conditional write still admits exactly one — which is the claim
the whole project exists to make.

### Results, 2026-09-05

Built in one session, `eu-central-1`, account <account-id>. What existed while it ran:

| Resource | Value |
|---|---|
| ALB | `ticket-alb`, internet-facing, `ticket-alb-<id>.eu-central-1.elb.amazonaws.com` |
| Subnets | `<subnet-id>` (1a, where the task runs) + `<subnet-id>` (1b) |
| ALB SG | `ticket-alb-sg` `<sg-id>` — tcp/80 from `0.0.0.0/0` |
| Target groups | `seat-inventory-tg`, `waiting-room-tg`, `checkout-tg`, `ticket-issuance-tg` — all `ip`, HTTP/8080 |
| Service up | `seat-inventory-service:3`, FARGATE_SPOT, desired 1, grace 120s |
| REST API | `ticket-edge` `<rest-api-id>`, Regional, stage `dev` |
| Invoke URL | `https://<rest-api-id>.execute-api.eu-central-1.amazonaws.com/dev` |

Only `seat-inventory-service` was brought up: it needs nothing but DynamoDB, which stands
at $0. `waiting-room-tg` stayed empty on purpose, and that turned out to be useful — see
the 503 note below.

**Boot and registration timings**, which set the grace period:

```
13:58:10  task starts
13:58:23  Tomcat initialized on 8080
13:58:37  Started SeatInventoryServiceApplication in 30.691 s
13:58:53  target still unhealthy   <- probes that ran during boot
13:59:04  target healthy
```

Roughly **54 s from task start to serving traffic**, of which 31 s is Spring and ~20 s is
the two passing health checks at a 10 s interval.

**Routing, verified against the ALB directly:**

```
POST /admin/events/evt-alb-1/seats     (rule 30)  201  {"created":3,"skipped":0}
POST /events/evt-alb-1/seats/A-1/hold  (rule 20)  201  holdId bea0bfc9…
POST /events/evt-alb-1/seats/A-1/hold  (rule 20)  409  "Seat unavailable" (ProblemDetail)
POST /events/evt-alb-1/queue           (rule 10)  503  matched, no targets registered
POST /events/evt-alb-1/seats/A-2/hold  no header  403  "Direct access denied"
     …with X-Edge-Secret: wrong-value  403  the condition matches on value, not presence
```

The two `/events/...` calls are the routing proof: identical prefix, different services.
**503 and 403 are the two answers worth telling apart** — 403 means no rule matched, 503
means a rule matched and forwarded to a target group with nothing healthy in it. A 503
with tasks running is therefore always a health-check problem and never a rules problem.

**Through the whole edge, sending no secret header at all** (API Gateway injects it):

```
POST $API/admin/events/evt-edge-1/seats      201
POST $API/events/evt-edge-1/seats/B-1/hold   201  holdId 5790af7c…
POST $API/events/evt-edge-1/seats/B-1/hold   409
GET  $API/                                   403  {"message":"Missing Authentication Token"}
POST http://$ALB/events/evt-edge-1/seats/B-2/hold   403  (direct access still refused)
```

**The concurrency proof, end to end — 30 simultaneous holds on one seat, through
API Gateway → ALB → Fargate → DynamoDB:**

```bash
seq 1 30 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST "$API/events/evt-edge-1/seats/B-2/hold" | sort | uniq -c
#       1 201
#      29 409
```

Read back from DynamoDB afterwards, bypassing every hop:

```
B-1  HELD  holdId=5790af7c…  holdExpiresAt=1788617452
B-2  HELD  holdId=a7953ddc…  holdExpiresAt=1788617554   <- the 30-way race: one holdId
B-3  HELD  holdId=51ecad98…  holdExpiresAt=1788617556
```

Identical to slice 1's result against a raw task IP. Two more hops in the path, and the
conditional write still admits exactly one — **the edge changed the address, not the
guarantee.** That is the entire point of adding it.

**Latency cost of the edge**, same call both ways:

| Path | TTFB | Total |
|---|---|---|
| via API Gateway (HTTPS) | 96 ms | 96 ms |
| direct to ALB (HTTP) | 85 ms | 85 ms |

~11 ms for the gateway hop, and 53 ms of the 96 was the TLS handshake on a cold
connection, so the marginal per-request cost on a warm connection is well under that.

## What It Cost

List prices pulled from the Price List API for `eu-central-1` on 2026-09-05, not
estimated:

| Item | Price |
|---|---|
| ALB | $0.027 / hr |
| ALB LCU | $0.008 / LCU-hr (rounds to nothing at this traffic) |
| **Public IPv4** | **$0.005 / hr per address** |
| Fargate | $0.04656 / vCPU-hr + $0.00511 / GB-hr |
| API Gateway REST | $3.70 / million requests |

**The ALB + API Gateway pair is ~$0.037/hr**: $0.027 for the load balancer plus $0.010
for **two public IPv4 addresses**, one per AZ. Since February 2024 every public IPv4 is
billed, so an ALB's real price is 37% above its published hourly rate, and a two-AZ
minimum means you cannot have fewer than two. API Gateway is $0 idle.

With one Spot task behind it the session ran at **~$0.046/hr**. A task at
0.25 vCPU / 0.5 GB is $0.0142/hr on demand and ~$0.0043 on Spot — so **the task's public
IP costs more than the Spot compute it is attached to**. That is a direct consequence of
the no-NAT-Gateway rule, and still the right trade: a NAT Gateway is $0.052/hr plus data
processing, more than the ALB itself.

The number that matters for the cost ground rules: **a forgotten ALB is $27/month**,
which trips the $10 budget alert in about eleven days. It is the most expensive thing
that can be left behind in Phase 1 — more than RDS or ElastiCache.

Slice 6's plan line reads "ALB/WAF session-only (~$0.03/hr combined)". ALB alone is
$0.037/hr with the IPv4 charge, and WAF is not hourly at all: $5/month per web ACL,
$1/month per rule, and Bot Control adds $10/month, all prorated. Session-only that is
pennies, but the estimate understates the standing risk.

---

## Traps Hit

**`aws ecs wait services-stable` returns before the target is healthy.** It reported
stable while Spring was still booting, so `describe-target-health` read `unhealthy` /
`Target.FailedHealthChecks` and looked like a real failure. It was not: the health check
starts probing the moment the target registers, and the first two probes hit a port with
nothing listening yet. **The grace period does not delay the probes — it only stops ECS
from killing the task while they fail.** The signal to wait on is
`describe-target-health` returning `healthy`, never `services-stable`.

**`GET /` returns `404` with `Content-Type: application/json`**, confirmed against the
live task, so the `200-499` matcher accepts it. The matcher decision holds — but it also
means a service that boots into a broken state still reports healthy. See the health
check note in the design decisions.

**`GET <invoke-url>/` returns `{"message":"Missing Authentication Token"}` with 403.**
That is API Gateway itself, not the ALB and not an auth failure: `/{proxy+}` does not
match the stage root, and every unmatched path in a REST API produces that same
misleading message. Worth knowing before Cognito lands later in this slice, or the
message will send you hunting for an authorizer problem that does not exist.

**zsh globs the `--patch-operations` path.** `op=replace,path=/*/*/throttling/rateLimit`
fails with `no matches found` because zsh expands `/*/*/ ` against the filesystem before
the CLI ever sees it. Quote the whole operation. Same family as the `$REPO:latest` and
`$R="--region …"` traps in the ECR and ECS runbooks — three for three now, so the rule is
simply to quote every CLI argument containing `*` or `:`.

**The seat table's key attributes are lowercase `pk` / `sk`**, not `PK` / `SK`. Guessing
uppercase gives `ValidationException: The provided key element does not match the schema`
from `get-item`, which reads like a wrong key value rather than a wrong attribute name.
`SeatTableSchema` in `common` is the authority.

**A recreated ALB gets a new DNS name.** Confirmed twice in one session
(`ticket-alb-<id>…` then `ticket-alb-<id>…`). This is exactly why the API
Gateway integration URI is `http://${stageVariables.albDns}/{proxy}`: next session is one
`update-stage` on `albDns`, with no redeployment of the method.

**Delete the ALB before its target groups.** A target group still referenced by a
listener rule refuses deletion, and the reference outlives the listener briefly — one
retry after ~15 s was needed.

### Predicted but not hit

Left here because they are still real, just avoided by building via CLI rather than the
console wizard: the ALB refusing to create with one subnet; the console pre-selecting the
default security group; and `Instance` rather than `IP` target type, which is immutable
and means deleting the target group to fix.


## Teardown

The ALB is the only thing here that bills.

```bash
export AWS_DEFAULT_REGION=eu-central-1

ALB_ARN=$(aws elbv2 describe-load-balancers --names ticket-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text)
aws elbv2 delete-load-balancer --load-balancer-arn "$ALB_ARN"

for s in seat-inventory-service waiting-room-service; do
  aws ecs delete-service --cluster ticket-waiting-room --service "$s" --force
done
```

Deleting the load balancer deletes its listener and rules with it; **the target groups
survive and are free**, but they are orphaned — recreating the ALB means recreating the
five rules. Keep the rule table above as the source of truth.

Kept between sessions at $0: target groups, both security groups, the API Gateway REST
API and its `dev` stage (billed per request only). Next session, the entire API Gateway
side is one stage-variable edit: point `albDns` at the new load balancer.


Verified after teardown, 2026-09-05: 0 load balancers, 0 ECS services, 0 running tasks,
0 ElastiCache clusters, 0 RDS instances, 0 Elastic IPs, 0 NAT Gateways.

Kept between sessions at $0: the four target groups, `ticket-alb-sg`, the `8080`-from-ALB
ingress rules on all four service security groups, and the `ticket-edge` REST API with
its `dev` stage. The target groups are orphaned once the ALB goes — recreating it means
recreating the five rules, so **the rule table in this runbook is the source of truth**,
not the console.

The API Gateway side survives teardown intact. Next session it is one command:

```bash
aws apigateway update-stage --rest-api-id <rest-api-id> --stage-name dev \
  --patch-operations "op=replace,path=/variables/albDns,value=$NEW_ALB_DNS"
```

## Notes for Phase 2 (Terraform)

- `aws_lb`, `aws_lb_target_group` (`target_type = "ip"`), `aws_lb_listener` with a
  `fixed_response` default action, and `aws_lb_listener_rule` per route — the priority
  numbers become explicit arguments, which is a strict improvement over a console list
  whose ordering is invisible until something misroutes.
- The routing table is data: a `locals` map of `{ path patterns → target group }` and
  `for_each` over it, rather than five copy-pasted rule resources.
- The shared secret belongs in **Secrets Manager**, injected into the listener rule and
  the stage variable from one source, and rotated. A hand-typed value in two consoles is
  fine for a session and wrong for anything longer.
- `aws_api_gateway_rest_api` + `aws_api_gateway_resource` (`{proxy+}`) +
  `aws_api_gateway_integration` (`HTTP_PROXY`) + `aws_api_gateway_deployment`/`_stage`.
  Terraform's usual trap here is deployments not re-triggering on method changes —
  needs a `triggers` hash over the API body.
- Revisit HTTPS end-to-end once a domain exists: ACM cert on the ALB listener, and the
  integration URL moves to `https://`.
