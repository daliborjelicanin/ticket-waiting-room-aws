# shellcheck shell=bash
# Points this shell at one account for Terraform. Source it, do not run it:
#
#   source infra/scripts/tf-env.sh            # twr-dev: bootstrap, foundation, session
#   source infra/scripts/tf-env.sh default    # management account: infra/org only
#
# Exports AWS_PROFILE and TF_VAR_account_id. The account id is read from ~/.aws/config, not
# from the credentials, so a mismatch between the two is caught by the provider's
# allowed_account_ids (D12) instead of being silently agreed with. It is never printed.
#
# For twr-dev it also exports TF_VAR_image_digests: the deployed image digests from SSM
# /twr/<env>/images/* (written by images.yml, D15), resolved here, at "dispatch", so Terraform
# takes digests only as a variable and the plan shows exactly which image runs. CI does the
# same resolution in its workflow. Needs a valid SSO session; without one the variable is left
# unset and a plan that needs it stops with "No value for required variable".

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

if [[ "$_twr_profile" != "default" ]]; then
  _twr_digests="$(aws ssm get-parameters-by-path --path /twr/dev/images \
    --query 'Parameters[].{k:Name,v:Value}' --output json 2>/dev/null |
    python3 -c 'import json,sys; print(json.dumps({p["k"].rsplit("/",1)[1]: p["v"] for p in json.load(sys.stdin)}))' 2>/dev/null)"
  if [[ -n "$_twr_digests" ]]; then
    export TF_VAR_image_digests="$_twr_digests"
    echo "tf-env: TF_VAR_image_digests set for $(python3 -c 'import json,sys; print(", ".join(sorted(json.loads(sys.argv[1]))) or "no images")' "$_twr_digests")"
  else
    unset TF_VAR_image_digests
    echo "tf-env: could not read /twr/dev/images (SSO session?); TF_VAR_image_digests unset" >&2
  fi
fi
unset _twr_profile _twr_id _twr_digests
