// Scenario B — the full purchase, contended.
//
// M real customers (one Cognito identity each: join.lua allows one queue place per sub)
// join the waiting room, are admitted in batches by an operator VU running beside them,
// and race POST /events/{event}/purchase for S seats, S < M. Each purchase is a saga:
// hold → order → charge → confirm → issue → notify. The claim: exactly S sagas SUCCEED and
// every other one FAILS at HoldSeat with nothing charged — the compensation paths under
// load — and no seat ends with two orders or two tickets (scripts/invariants.sh).
//
// The ratio M:S is the contention; the number the README wants is what a buyer felt from
// POST /purchase to the order reading PAID, at p50/p95/p99.
//
//   k6 run -e USERS=50 -e SEATS=10 load-test/k6/purchase-race.js
//
// WAF's rate rule allows 100 purchases per minute per source IP (runbook-waf.md); with
// one laptop as the source keep USERS ≤ 100 per run, or put the rule in Count for it.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { API, tokens, opsHeaders, customerHeaders, purchaseHeaders, seatIds, seed, uuid } from './lib.js';

const USERS = Number(__ENV.USERS || 50);
const SEATS = Number(__ENV.SEATS || 10);
const EVENT = __ENV.EVENT || `buy-${Date.now()}`;
const ADMIT_BATCH = Number(__ENV.ADMIT_BATCH || 25);
const SETTLE_TIMEOUT_S = Number(__ENV.SETTLE_TIMEOUT_S || 30);

if (tokens.users.length < USERS) {
  throw new Error(`need ${USERS} customer tokens, have ${tokens.users.length}: run scripts/mint-tokens.sh ${USERS}`);
}

const admitted = new Counter('customers_admitted');
const won = new Counter('purchases_won');       // saga SUCCEEDED: order PAID
const lost = new Counter('purchases_lost');     // saga failed at HoldSeat: no order exists
const unsettled = new Counter('purchases_unsettled');
const accepted = new Trend('purchase_accept_ms', true);       // POST /purchase → 202
const settled = new Trend('purchase_end_to_end_ms', true);    // POST /purchase → order PAID
const waited = new Trend('queue_wait_ms', true);              // join → admitted

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 404));

export const options = {
  scenarios: {
    customers: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      maxDuration: '5m',
      exec: 'customer',
    },
    doorman: {
      executor: 'constant-vus',
      vus: 1,
      duration: '4m',
      exec: 'doorman',
      startTime: '5s',
    },
  },
  thresholds: {
    purchases_unsettled: ['count==0'],
    'http_req_failed{name:purchase}': ['rate==0'],
    'purchase_end_to_end_ms': ['p(95)<20000'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const seats = seatIds(SEATS, 'B');
  const result = seed(EVENT, seats);
  console.log(`event ${EVENT}: ${result.created} seats seeded, ${USERS} customers, ${SEATS} seats — contention ${(USERS / SEATS).toFixed(1)}:1`);
  return { seats, startedAt: Date.now() };
}

/** One operator, admitting whoever is waiting, a batch every few seconds, until the customers are through. */
export function doorman(data) {
  const res = http.post(`${API}/events/${EVENT}/queue/admit`, JSON.stringify({ count: ADMIT_BATCH }), {
    headers: opsHeaders(), tags: { name: 'admit' },
  });
  if (res.status === 200) {
    admitted.add(res.json().admitted);
  }
  sleep(3);
}

export function customer(data) {
  const me = tokens.users[__VU - 1];
  const headers = customerHeaders(me);

  // 1. Join. Idempotent by identity, so a retry cannot cost the place.
  const joinedAt = Date.now();
  const join = http.post(`${API}/events/${EVENT}/queue`, null, { headers, tags: { name: 'join' } });
  check(join, { 'joined': (r) => r.status === 201 });

  // 2. Wait to be admitted — polling GET /queue, which is the source of truth the
  //    WebSocket only saves a client from having to hit.
  let place = join.status === 201 ? join.json() : null;
  for (let i = 0; i < 120 && (!place || place.status !== 'ADMITTED'); i++) {
    sleep(1);
    const poll = http.get(`${API}/events/${EVENT}/queue`, { headers, tags: { name: 'position' } });
    place = poll.status === 200 ? poll.json() : place;
  }
  if (!place || place.status !== 'ADMITTED') {
    unsettled.add(1);
    console.error(`VU ${__VU}: never admitted (${place && place.status})`);
    return;
  }
  waited.add(Date.now() - joinedAt);

  // 3. Buy. Everyone wants one of the same few seats.
  const seat = data.seats[(__VU - 1) % data.seats.length];
  const orderId = uuid();
  const purchase = http.post(`${API}/events/${EVENT}/purchase`, JSON.stringify({
    orderId, seatId: seat, amountCents: 12000, paymentToken: 'tok_ok',
  }), { headers: purchaseHeaders(me), tags: { name: 'purchase' } });
  const acceptedAt = Date.now();
  if (!check(purchase, { 'purchase accepted (202)': (r) => r.status === 202 })) {
    unsettled.add(1);
    console.error(`VU ${__VU}: purchase ${purchase.status} ${purchase.body}`);
    return;
  }
  accepted.add(purchase.timings.duration);

  // 4. Watch the order. PAID = the saga won the seat and went the distance. A 404 that
  //    persists = the saga lost HoldSeat and never created the order — the expected fate
  //    of USERS − SEATS of these VUs. Anything else after the timeout is a finding.
  for (let waitedS = 0; waitedS < SETTLE_TIMEOUT_S; waitedS++) {
    sleep(1);
    const order = http.get(`${API}/orders/${orderId}`, { headers, tags: { name: 'order' } });
    if (order.status === 200) {
      const status = order.json().status;
      if (status === 'PAID') {
        won.add(1);
        settled.add(Date.now() - acceptedAt);
        return;
      }
      if (status === 'FAILED' || status === 'CANCELLED') {
        // An order that exists and failed means the hold was won but the charge lost:
        // not this scenario's expected path (tok_ok always captures). Count it, loudly.
        unsettled.add(1);
        console.error(`VU ${__VU}: order ${orderId} ${status}`);
        return;
      }
    }
  }
  // Timed out on 404 (or still PENDING): the hold was lost, or the saga is slow. Tell
  // them apart afterwards with scripts/settle.sh, which asks Step Functions.
  lost.add(1);
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify({
      event: EVENT, users: USERS, seats: SEATS,
      admitted: data.metrics.customers_admitted?.values.count ?? 0,
      won: data.metrics.purchases_won?.values.count ?? 0,
      lost_or_timed_out: data.metrics.purchases_lost?.values.count ?? 0,
      unsettled: data.metrics.purchases_unsettled?.values.count ?? 0,
      queue_wait_ms: data.metrics.queue_wait_ms?.values,
      purchase_accept_ms: data.metrics.purchase_accept_ms?.values,
      purchase_end_to_end_ms: data.metrics.purchase_end_to_end_ms?.values,
    }, null, 2) + '\n',
    [`load-test/results/purchase-race-${EVENT}.json`]: JSON.stringify(data, null, 2),
  };
}
