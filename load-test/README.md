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
| `scripts/create-test-users.sh N` | `lt-1…N@example.test` in the pool, permanent password from `LOAD_TEST_PASSWORD` (required); idempotent |
| `scripts/mint-tokens.sh N` | operator + N customer token pairs (`0`: the operator alone) → `.tokens.json` (gitignored, valid ~1 h) |
| `scripts/seat-holds.sh <event> <seats>` | scenario A's check in the table: every seeded seat HELD exactly once, with its one `holdId`, and nothing else (compare with k6's `holds_won`) |
| `scripts/cognito-env.sh` | sourced, not run: the Phase 2 pool, clients and token URL from SSM, and the `ops` secret from Cognito, into the shell only |
| `scripts/invariants.sh <event>` | *Phase 1, until slice 5's reconciliation task (D16):* **the "0 double-sold seats" check**: seats SOLD = orders PAID = payments CAPTURED = tickets; no seat with two PAID orders or two tickets; no CAPTURED on a non-PAID order; no order with two live payments |
| `scripts/settle.sh <event>` | *Phase 1:* how every saga of the run ended, from Step Functions (`SUCCEEDED` / `FAILED SeatUnavailable` / anything else) |
| `scripts/delete-test-users.sh N` | tidy the pool |

## Running (Phase 2)

k6 is not installed on the laptop; the official image is enough. From the repository root:

```bash
source infra/scripts/tf-env.sh                     # on its own; twr-dev
# --user: the image runs k6 as its own uid, and .tokens.json is mode 600 for yours.
alias k6='docker run --rm -i --user "$(id -u):$(id -g)" -v "$PWD/load-test:/work" -w /work grafana/k6:0.55.0'
# The stage's invoke URL is looked up, never committed: it names the API.
API=$(terraform -chdir=infra/live/session output -raw invoke_url)

# Scenario A, the seat race: the operator token alone.
load-test/scripts/mint-tokens.sh 0
EVENT=race-$(date +%s)
k6 run -e API=$API -e EVENT=$EVENT -e VUS=200 -e SEATS=10 k6/seat-race.js
load-test/scripts/seat-holds.sh $EVENT 10           # within the 5-minute hold

# Scenario B, the purchase race (slice 5: needs the saga, RDS and every service).
export LOAD_TEST_PASSWORD="$(openssl rand -base64 18)Aa1!"   # required, never committed
load-test/scripts/create-test-users.sh 50          # once; the users are permanent
load-test/scripts/mint-tokens.sh 50                # before every run (tokens live an hour)
k6 run -e API=$API -e EVENT=buy-$(date +%s) -e USERS=50 -e SEATS=10 k6/purchase-race.js
```

The full session around the seat race (image, apply, checks, destroy) is
`docs/runbook-session-edge.md` §6.

Results land in `results/` (gitignored) as k6's full summary JSON; the runbook copies the
numbers that matter.

## What the scripts assume

- `EVENT` is passed in, one id per run: k6's init context runs once per VU and again for
  setup, teardown and the summary, so an id generated in the script is a different event
  for each of them (found the hard way on the first run: 200 × 409 on seats nobody seeded).
- The Cognito pool, its clients and the token URL come from foundation's SSM parameters,
  and the `ops` client secret from Cognito itself, all at run time (`scripts/cognito-env.sh`,
  sourced by the three Cognito scripts). Nothing is written to disk but `.tokens.json`.
- **Scenario A** needs seat-inventory behind the edge and nothing else. Its paths are not
  buyer entry, so no WAF buyer rule and no method throttle applies (D6): the stage's account
  default is the only limit, and the race measures the seat lock, not the edge's brakes.
- **Scenario B** goes through the buyer rules on purpose. Run it with the runner's address in
  `-var loadtest_allow_cidr=…/32` (D6), or the flood and credential guards turn the tail of the
  run into 403s.
- `scripts/invariants.sh` and `scripts/settle.sh` are Phase 1's (the `ticket-checkout-db`
  instance and the unprefixed table). They are replaced by slice 5's reconciliation task (D16);
  `scripts/seat-holds.sh` is the Phase 2 check for scenario A.
