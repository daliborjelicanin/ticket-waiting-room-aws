# Load test — slice 7's proof

Two k6 scenarios that race real buyers for the same seats through the whole stack, and
the scripts that give them identities and check the data stores afterwards. The session
that runs them, its decisions (WAF, stage throttle, gateway latency) and its numbers live
in `docs/runbook-load-test.md`; this is the mechanics.

| Path | What |
|---|---|
| `k6/seat-race.js` | **Scenario A** — N operators race `POST …/seats/{seat}/hold` on S seats: exactly S×201, the rest 409, and what losing costs at p99 |
| `k6/purchase-race.js` | **Scenario B** — M customers (one Cognito identity each) join, are admitted by a doorman VU, and race `POST /purchase` for S seats: S sagas succeed, the rest fail at `HoldSeat`, and what a buyer felt from 202 to `PAID` |
| `k6/lib.js` | the edge URL, a browser `User-Agent` (Bot Control blocks k6's), token headers, seeding, a v4 uuid |
| `scripts/create-test-users.sh N` | `lt-1…N@example.test` in the pool, permanent password; idempotent |
| `scripts/mint-tokens.sh N` | operator + N customer token pairs → `.tokens.json` (gitignored, valid ~1 h) |
| `scripts/invariants.sh <event>` | **the "0 double-sold seats" check**: seats SOLD = orders PAID = payments CAPTURED = tickets; no seat with two PAID orders or two tickets; no CAPTURED on a non-PAID order; no order with two live payments |
| `scripts/settle.sh <event>` | how every saga of the run ended, from Step Functions (`SUCCEEDED` / `FAILED SeatUnavailable` / anything else) |
| `scripts/delete-test-users.sh N` | tidy the pool |

## Running

k6 is not installed on the laptop; the official image is enough:

```bash
alias k6='docker run --rm -i -v "$PWD/load-test:/work" -w /work grafana/k6:0.55.0'
load-test/scripts/create-test-users.sh 50        # once
load-test/scripts/mint-tokens.sh 50              # before every run (tokens live an hour)
k6 run -e VUS=200 -e SEATS=10 k6/seat-race.js
k6 run -e USERS=50 -e SEATS=10 k6/purchase-race.js
load-test/scripts/invariants.sh <event id printed by the run>
load-test/scripts/settle.sh <event id>
```

Results land in `results/` (gitignored) as k6's full summary JSON; the runbook copies the
numbers that matter.

## What the scripts assume

- The stack is up per the runbooks (ALB, RDS, ElastiCache, five services, the connection).
- `~/.ticket-waiting-room/cognito.env` holds `POOL_ID`, `WEB_CLIENT`, `TOKEN_URL`, and the
  operator client credentials, as the Cognito runbook left it.
- Scenario B keeps `USERS` ≤ 100 per run from one source IP, or WAF's 100/min rate rule on
  `/purchase` turns the tail of the run into 429s — a real finding if it happens, but not
  the one being measured.
- The API Gateway stage throttles at 50 rps / burst 100 (`runbook-alb-apigateway.md`);
  scenario A at 200 VUs is designed to hit that burst, which is part of what it measures.
