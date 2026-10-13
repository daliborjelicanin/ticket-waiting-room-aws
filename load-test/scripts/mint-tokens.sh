#!/usr/bin/env bash
# Mint the tokens a run needs — one operator (client credentials) and N customers
# (USER_PASSWORD_AUTH: access token for the services, ID token for POST /purchase) — into
# load-test/.tokens.json, which is gitignored. Cognito tokens live one hour: mint right
# before the run, and re-mint rather than reuse across sessions.
#
#   load-test/scripts/mint-tokens.sh 50
#
# LOAD_TEST_PASSWORD must be the one create-test-users.sh was run with.
set -euo pipefail
source ~/.ticket-waiting-room/cognito.env
N=${1:?number of users}
PASSWORD=${LOAD_TEST_PASSWORD:?set LOAD_TEST_PASSWORD -- password of the lt-N test users, never a committed default}
REGION=${AWS_DEFAULT_REGION:-eu-central-1}
OUT="$(dirname "$0")/../.tokens.json"

ops=$(curl -sS -X POST "$TOKEN_URL" -u "${OPS_CLIENT_ID}:${OPS_CLIENT_SECRET}" \
  -d 'grant_type=client_credentials&scope=ticketing/ops' | jq -r .access_token)
[ "$ops" != null ] || { echo "operator token failed" >&2; exit 1; }

users='[]'
for i in $(seq 1 "$N"); do
  u="lt-$i@example.test"
  pair=$(aws cognito-idp initiate-auth --region "$REGION" --client-id "$WEB_CLIENT" \
    --auth-flow USER_PASSWORD_AUTH --auth-parameters "USERNAME=$u,PASSWORD=$PASSWORD" \
    --query 'AuthenticationResult.[AccessToken,IdToken]' --output json)
  users=$(jq --arg u "$u" --argjson p "$pair" '. + [{email:$u, accessToken:$p[0], idToken:$p[1]}]' <<<"$users")
done
jq -n --arg ops "$ops" --argjson users "$users" '{mintedAt: (now|todate), ops: $ops, users: $users}' > "$OUT"
chmod 600 "$OUT"
echo "minted 1 operator + $N customer token pairs into $OUT (valid ~1 h)"
