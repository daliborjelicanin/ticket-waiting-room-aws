# Runbook — The Load Test

Slice 7's deliverable and the number the whole project exists to produce: many buyers
racing the same few seats through the **entire** stack — API Gateway, ALB, the security
filter chain, DynamoDB's conditional write, Step Functions, Postgres, SQS, Lambda — and
afterwards, in the data stores, **0 double-sold seats**, with what it cost each buyer at
p50/p95/p99. The mechanics (scripts, scenarios, invariant queries) are in
`load-test/README.md`; this is the session and its numbers.

Every earlier proof raced one component: 30 holds against DynamoDB (slice 1), 50 charges
against Postgres (slice 4), 20 deliveries against a unique constraint (slice 5). This one
races the system.

## Design Decisions Made Before Running

**Two scenarios, because they prove different things.** *A — the seat race*: N operators
hit `POST …/seats/{seat}/hold` on S seats at once, the saga's own path. It isolates the
centerpiece from the saga's latency and asks exactly slice 1's question at scale: S × 201,
the rest 409, and what losing costs. *B — the full purchase, contended*: M real customers,
one Cognito identity each (`join.lua` allows one place per `sub`; one identity cannot stand
in line M times), join the waiting room, are admitted in batches by a doorman VU, and race
`POST /purchase` for S seats. Each purchase is a saga; the claim is S `SUCCEEDED`, M−S
`FAILED SeatUnavailable` with nothing charged, and the invariants holding in Postgres and
DynamoDB afterwards.

**k6 over Gatling.** One JS file, one binary (here, the `grafana/k6` image), native
percentiles and thresholds, and nothing to warm on the load generator.

**The invariants are checked in the stores, not from HTTP codes.** `invariants.sh` reads
DynamoDB and both Postgres databases: seats `SOLD` = orders `PAID` = payments `CAPTURED` =
tickets; no seat with two PAID orders; no seat with two tickets; no `CAPTURED` payment on a
non-PAID order; no order with two live payments. `settle.sh` asks Step Functions how every
saga of the run ended, because k6 can only see "PAID" or "still 404".

**Without WAF.** The web ACL is session-only and was not recreated: the first load test
measures the system, not the rate rule (100/min/IP would have turned scenario B's tail into
429s from one laptop). A run *with* Bot Control enforced — proving a browser-UA buyer passes
under load — is a separate, later data point.

**Contention ratios.** A: 200 VUs on 10 seats (20:1). B: 50 customers on 10 seats (5:1),
admitted 25 at a time every 3 s. Both are well above what one seat ever sees; both are
within the stage throttle (50 rps / burst 100) for B and deliberately at its burst for A.

**Warm first.** The first authenticated request on each service is JIT, not the system
(3.5 s seed, 2.1 s join, 5.3 s tickets on the cold pass; 0.1 s on the second). One pass per
endpoint and one complete purchase before any load; the first of everything is recorded
but not measured.

## What Ran — 2026-09-17

Stack-up by CLI from the runbooks, **18:16 → 18:24 UTC** (8 min to four healthy targets);
50 test users created (`lt-1…50@example.test`, free, permanent); tokens minted at 18:25;
warm-up; runs 18:27–18:47; teardown 18:48 → clean sweep 18:53. **Stack up 37 minutes,
≈ $0.09.** Revisions `seat-inventory:6`, `waiting-room:5`, `checkout:10`, `issuance:8`,
`notification:4` — image `272e582`, the build with histograms and the housekeeping predicate.

### Scenario A — 200 VUs, 10 seats, through the edge

| Run | Won / lost / unexpected | Winners p50 / p95 / p99 | Losers p50 / p95 / p99 | Wall time |
|---|---|---|---|---|
| 1 (first after warm-up) | **10 / 190 / 0** | 1.05 s / 1.90 s / 2.26 s | 5.33 s / 8.64 s / 8.84 s | 9 s |
| 2 (hot) | **10 / 190 / 0** | 0.58 s / 1.13 s / 1.20 s | 3.02 s / 4.91 s / 5.01 s | 9 s |

DynamoDB after each: **10 HELD, 10 distinct holdIds**, 190 seats never existed to lose —
the 409s are the conditional write refusing, exactly as in slice 1, now behind API Gateway,
the ALB and a JWT check, 200 at a time. `http_req_failed` 0: every one of 201 requests was
a 201 or a 409.

The shape of the two latency columns is the finding: winners are fast and losers are slow
*because* they lose — 200 requests land on one 0.25 vCPU task at once, the first ten
through take their seats in half a second, and the other 190 queue behind them for the
same round trip only to be told no. On a single small task the cost of a lost race is
the queueing, not the write.

### Scenario B — 50 customers, 10 seats, the whole saga

| Run | Admitted | Won / lost / unsettled | Step Functions | Accept (202) p50 / p99 | End-to-end (202 → PAID) p50 / p95 / p99 | Queue wait p50 / p99 |
|---|---|---|---|---|---|---|
| 1 (first) | 49¹ | **10 / 39 / 0** | 10 SUCCEEDED · 39 FAILED SeatUnavailable | 134 / 159 ms | 5.24 / 7.48 / 7.69 s | 7.3 / 12.4 s |
| 2 (hot) | 50 | **10 / 40 / 0** | 10 SUCCEEDED · 40 FAILED SeatUnavailable | 125 / 175 ms | **3.00 / 4.14 / 4.25 s** | 8.5 / 10.9 s |

¹ one customer VU crashed on a script bug before joining (see *Traps Hit*); the 49 who
ran are a complete result on their own.

**Invariants, both runs** (`invariants.sh`):

```
seats SOLD = orders PAID = payments CAPTURED = tickets :  10 = 10 = 10 = 10
seats with two PAID orders   (must be 0): 0
seats with two tickets       (must be 0): 0
CAPTURED on a non-PAID order (must be 0): 0
orders with two live payments(must be 0): 0
RESULT: 0 double-sold seats
```

Read against the code: the 40 losers reached `HoldSeat`, lost the conditional write, and
the saga stopped there — no order row, no charge, no ticket, no mail. Nothing was
compensated because nothing had been taken. The 10 winners went the distance: hold →
order → charge → confirm → issue → notify, each a `SUCCEEDED` execution, each a `PAID` row
with exactly one `CAPTURED` payment and exactly one ticket.

Queue wait is the doorman's cadence (25 admitted every 3 s), not the room's speed: a
customer who joined just after a batch waited for the next one. Accept latency is API
Gateway starting the saga — 125 ms median, the fastest thing on the board.

### What CloudWatch saw

Over the session: `seat.hold.attempts{held}` **43** (four runs × 10 plus three warm-up
holds), `{unavailable}` **660**; `checkout.charges{captured}` **21** = `tickets.issued`
**21**; `waiting_room.admissions` **100**; `notifications.sent{failed}` **63** = 21 × the
three SES-sandbox retries; DynamoDB's own `ConditionalCheckFailedRequests` **662** — the
service's count of lost races and the platform's, two independent sources, agreeing (the
two extra are the warm-up's deliberately failed purchase and its retry). X-Ray held every
purchase as one trace, as on the 15th.

## Traps Hit

- **`Date.now()` in k6's init context is a different value per VU.** The first scenario A
  run seeded event `race-…219` and raced `…870`: 200 holds on seats nobody seeded, 200 ×
  409, "won: 0" — a perfect-looking result for the wrong reason, caught only because 0
  winners is impossible. The init context runs once per VU and again for setup, teardown
  and the summary. The event id is now a required `-e EVENT=` argument, one per run.
- **`__VU` is numbered across all scenarios.** With the doorman running beside 50
  customers, one customer was VU 51 and had no token. `exec.scenario.iterationInTest` is
  the per-scenario counter; `exec.vu.idInScenario`, which I reached for first, does not
  exist and cost a whole run (`Cannot read property 'accessToken' of undefined` × 50).
- **The k6 image runs as its own user**; `.tokens.json` is mode 600 for yours. `--user
  "$(id -u):$(id -g)"` on the container. The results path in `handleSummary` is relative
  to the container's working directory, not the repo.
- **The histogram export does not give CloudWatch percentiles yet.** `http.server.requests`
  now leaves as an explicit-bucket histogram, but the EMF exporter only emits a
  distribution CloudWatch can take `p99` of when `detailed_metrics: true` is set in the
  collector config; without it the metric arrived with `p50`…`p99` = 0 and a `SampleCount`
  (8 793 for 660 lost holds) that is the sum of Micrometer's *cumulative* bucket counts.
  Server-side percentiles are therefore still open; the k6 side — what a buyer felt — is
  what this runbook reports. Fix: `detailed_metrics: true` (SSM v3) and check the
  bucket-count semantics against the collector's debug exporter locally first.
- **100 % sampling and a polling client make 3 000 traces in 15 minutes.** Scenario B's
  customers poll `GET /queue` and `GET /orders/{id}` every second; each poll is a trace.
  For load runs, sample (`management.tracing.sampling.probability`) or exclude the polls
  with the housekeeping predicate; the health-probe exclusion from the last session did
  its job — none of the 3 203 were `/actuator/health`.
- **A hold in the warm-up is a seat the warm-up purchase cannot have.** My own warm-up
  held `W-2` and then tried to buy it; the saga said `SeatUnavailable`, correctly. Seed
  one seat per purpose.

## Teardown

**Done 18:48–18:53 UTC**: five services, the ALB, RDS (`--skip-final-snapshot`),
ElastiCache, the EventBridge connection and its managed secret, the notifications DLQ
purged of its 21 sandbox refusals, `.tokens.json` removed. Sweep: 0 everywhere. The 50
test users stay in the pool (free); `delete-test-users.sh 50` when they should go.

## Next

- Server-side percentiles: `detailed_metrics: true` in the collector config, verified
  against the local collector before the next stack-up.
- A run with WAF: Bot Control enforced, rate rule in Count, a browser-UA buyer under load.
- Higher contention and a second task per service — the losers' latency above is one
  0.25 vCPU task's queue; two tasks behind the ALB is the obvious next data point, and
  the one that shows the conditional write scaling where the JVM does not.
- The README chart, from the two tables above (slice 8).
