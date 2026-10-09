// Shared by both scenarios: where the edge is, how a request is dressed, and what a
// customer or operator sends. Tokens are minted right before a run by
// scripts/mint-tokens.sh into load-test/.tokens.json (gitignored: they are credentials).
import http from 'k6/http';
import { randomBytes } from 'k6/crypto';

// The edge's invoke URL, e.g. https://<rest-api-id>.execute-api.eu-central-1.amazonaws.com/dev.
// Passed in with -e API=..., never written here: it is a live, billable endpoint.
export const API = __ENV.API;
if (!API) {
    throw new Error('set API to the ticket-edge invoke URL: k6 run -e API=https://... ');
}

// Bot Control on POST /purchase classifies k6's own User-Agent as an HTTP library and
// blocks it (runbook-waf.md). A browser UA is what a real buyer's browser sends anyway.
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36';

export const tokens = JSON.parse(open('../.tokens.json'));

export function opsHeaders(extra = {}) {
  return { 'User-Agent': UA, Authorization: `Bearer ${tokens.ops}`, 'content-type': 'application/json', ...extra };
}

export function customerHeaders(user, extra = {}) {
  return { 'User-Agent': UA, Authorization: `Bearer ${user.accessToken}`, 'content-type': 'application/json', ...extra };
}

/** The purchase route's Cognito authorizer takes the ID token bare, not as a Bearer. */
export function purchaseHeaders(user) {
  return { 'User-Agent': UA, Authorization: user.idToken, 'content-type': 'application/json' };
}

export function seatIds(count, prefix) {
  return Array.from({ length: count }, (_, i) => `${prefix}-${i + 1}`);
}

/** Seed an event's seats as the operator; re-runnable, existing seats are skipped. */
export function seed(eventId, seats) {
  const res = http.post(`${API}/admin/events/${eventId}/seats`, JSON.stringify({ seatIds: seats }), {
    headers: opsHeaders(), tags: { name: 'seed' },
  });
  if (res.status !== 201) {
    throw new Error(`seed failed: ${res.status} ${res.body}`);
  }
  return res.json();
}

export function uuid() {
  // RFC 4122 v4 from k6's crypto module (there is no global `crypto` in k6's runtime);
  // good enough for order ids and idempotency keys.
  const b = new Uint8Array(randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
