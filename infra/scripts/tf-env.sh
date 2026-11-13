# shellcheck shell=bash
# Points this shell at one account for Terraform. Source it, do not run it:
#
#   source infra/scripts/tf-env.sh            # twr-dev: bootstrap, foundation, session
#   source infra/scripts/tf-env.sh default    # management account: infra/org only
#
# Exports AWS_PROFILE and TF_VAR_account_id. The account id is read from ~/.aws/config, not
# from the credentials, so a mismatch between the two is caught by the provider's
# allowed_account_ids (D12) instead of being silently agreed with. It is never printed.

_twr_profile="${1:-twr-dev}"
_twr_id="$(aws configure get sso_account_id --profile "$_twr_profile" 2>/dev/null)"
if [[ ! "$_twr_id" =~ ^[0-9]{12}$ ]]; then
  echo "tf-env: profile '$_twr_profile' has no sso_account_id in ~/.aws/config" >&2
  unset _twr_profile _twr_id
  return 1 2>/dev/null || exit 1
fi
export AWS_PROFILE="$_twr_profile"
export TF_VAR_account_id="$_twr_id"
echo "tf-env: AWS_PROFILE=$AWS_PROFILE (account id exported as TF_VAR_account_id)"
unset _twr_profile _twr_id
