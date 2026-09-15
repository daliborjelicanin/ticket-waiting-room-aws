// Scenario A — the seat race, through the whole edge.
//
// N virtual users, released at once, each trying to hold one of S seats via
// POST /events/{event}/seats/{seat}/hold as the operator (the saga's own path). The claim:
// exactly S × 201 and N−S × 409, and the p99 of losing — a loser pays the full trip to
// DynamoDB and back for a ConditionalCheckFailed. This is slice 1's 30-hold proof at scale,
// on the real stack, with API Gateway, the ALB and the security filter chain in the way.
//
//   k6 run -e VUS=200 -e SEATS=10 load-test/k6/seat-race.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { API, opsHeaders, seatIds, seed } from './lib.js';

const VUS = Number(__ENV.VUS || 200);
const SEATS = Number(__ENV.SEATS || 10);
const EVENT = __ENV.EVENT || `race-${Date.now()}`;

const won = new Counter('holds_won');
const lost = new Counter('holds_lost');
const unexpected = new Counter('holds_unexpected');
const wonLatency = new Trend('hold_latency_won', true);
const lostLatency = new Trend('hold_latency_lost', true);

// 201 and 409 are both correct answers here; only anything else is a failure.
http.setResponseCallback(http.expectedStatuses(201, 409));

export const options = {
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    holds_unexpected: ['count==0'],
    http_req_failed: ['rate==0'],
    'http_req_duration{name:hold}': ['p(95)<2000'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const seats = seatIds(SEATS, 'R');
  const result = seed(EVENT, seats);
  console.log(`event ${EVENT}: seeded ${result.created} seats (${result.skipped} existed), ${VUS} VUs about to race them`);
  return { seats };
}

export default function (data) {
  const seat = data.seats[(__VU - 1) % data.seats.length];
  const res = http.post(`${API}/events/${EVENT}/seats/${seat}/hold`, null, {
    headers: opsHeaders(), tags: { name: 'hold' },
  });
  if (res.status === 201) {
    won.add(1);
    wonLatency.add(res.timings.duration);
  } else if (res.status === 409) {
    lost.add(1);
    lostLatency.add(res.timings.duration);
  } else {
    unexpected.add(1);
    console.error(`VU ${__VU} seat ${seat}: ${res.status} ${res.body}`);
  }
  check(res, { 'hold answered 201 or 409': (r) => r.status === 201 || r.status === 409 });
}

export function teardown(data) {
  console.log(`expected exactly ${data.seats.length} winners for event ${EVENT}; verify with scripts/invariants.sh ${EVENT}`);
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify({
      event: EVENT, vus: VUS, seats: SEATS,
      won: data.metrics.holds_won?.values.count ?? 0,
      lost: data.metrics.holds_lost?.values.count ?? 0,
      unexpected: data.metrics.holds_unexpected?.values.count ?? 0,
      hold_won_ms: data.metrics.hold_latency_won?.values,
      hold_lost_ms: data.metrics.hold_latency_lost?.values,
    }, null, 2) + '\n',
    [`load-test/results/seat-race-${EVENT}.json`]: JSON.stringify(data, null, 2),
  };
}
