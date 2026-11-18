# shellcheck shell=bash
# Resolves the Phase 2 Cognito settings the load-test scripts need, into this shell only.
# Sourced by create-test-users.sh, mint-tokens.sh and delete-test-users.sh; not run directly.
#
#   POOL_ID, WEB_CLIENT, TOKEN_URL   foundation's SSM parameters, /twr/<env>/foundation/cognito/*
#   OPS_CLIENT_ID                    the same
#   OPS_CLIENT_SECRET                read from Cognito itself (DescribeUserPoolClient)
#
# Nothing is written to disk. The secret exists in Terraform state and in Cognito, and is
# never published to SSM or a file (D13). Phase 1 kept all five in ~/.ticket-waiting-room/
# cognito.env; that pool is gone.
#
# Needs a twr-dev session: `source infra/scripts/tf-env.sh` first.

if [[ "${AWS_PROFILE:-}" != twr-dev ]]; then
  echo "cognito-env: AWS_PROFILE is '${AWS_PROFILE:-}', not twr-dev -- source infra/scripts/tf-env.sh first" >&2
  return 1 2>/dev/null || exit 1
fi

_twr_ssm() {
  aws ssm get-parameter --name "/twr/${TWR_ENV:-dev}/foundation/cognito/$1" --query Parameter.Value --output text
}

POOL_ID="$(_twr_ssm user_pool_id)"
WEB_CLIENT="$(_twr_ssm web_client_id)"
TOKEN_URL="$(_twr_ssm token_url)"
OPS_CLIENT_ID="$(_twr_ssm ops_client_id)"
OPS_CLIENT_SECRET="$(aws cognito-idp describe-user-pool-client --user-pool-id "$POOL_ID" \
  --client-id "$OPS_CLIENT_ID" --query UserPoolClient.ClientSecret --output text)"
unset -f _twr_ssm

if [[ -z "$POOL_ID" || -z "$WEB_CLIENT" || -z "$TOKEN_URL" || -z "$OPS_CLIENT_ID" || -z "$OPS_CLIENT_SECRET" ]]; then
  echo "cognito-env: could not resolve every Cognito setting (SSO session? foundation applied?)" >&2
  return 1 2>/dev/null || exit 1
fi
