#!/usr/bin/env bash
# Prints the sub claim of this GitHub Actions job's OIDC token (D14): the exact string the
# role trust policies in infra/bootstrap/oidc.tf match, printed before anything relies on it.
# Runs only in a job with `permissions: id-token: write`.
set -euo pipefail
: "${ACTIONS_ID_TOKEN_REQUEST_URL:?no OIDC token here: the job needs id-token: write}"

token=$(curl -sSf -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=sts.amazonaws.com" | jq -r .value)

# The payload is base64url without padding.
payload=$(cut -d. -f2 <<<"$token" | tr '_-' '/+')
while ((${#payload} % 4)); do payload+='='; done
base64 -d <<<"$payload" | jq -r '"OIDC sub: " + .sub'
