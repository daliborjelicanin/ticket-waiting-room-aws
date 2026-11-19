#!/usr/bin/env bash
# What went through the session NAT on one day, by the AWS service at the far end (D3, D4):
# the slice 2 check that ECR layers and DynamoDB take the gateway endpoints, and that the
# collector image comes through the pull-through cache rather than CloudFront.
#
#   source infra/scripts/tf-env.sh
#   infra/scripts/nat-traffic.sh 2026-11-18          # the whole day
#   infra/scripts/nat-traffic.sh 2026-11-18 20:25    # from 20:25 UTC on: one session
#
# Read-only. Downloads that day's flow logs from foundation's bucket into a temporary
# directory, with the VPC's subnets, the S3 and DynamoDB prefix lists and AWS's published
# ip-ranges.json, and runs nat-traffic.py in a throwaway python container (pyarrow reads
# the Parquet). Flow logs land in S3 about 10 minutes after the traffic; they expire after
# 7 days. Nothing printed names the account or an address.
set -euo pipefail
DAY=${1:?day as YYYY-MM-DD}
SINCE=0
[[ -n "${2:-}" ]] && SINCE=$(date -u -d "${DAY}T${2}:00Z" +%s)
[[ "${AWS_PROFILE:-}" == twr-dev ]] || { echo "AWS_PROFILE is not twr-dev -- source infra/scripts/tf-env.sh first" >&2; exit 1; }
REGION=${AWS_REGION:-eu-central-1}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/logs" "$WORK/in" "$WORK/script"

bucket=$(aws s3api list-buckets --query "Buckets[?starts_with(Name,'twr-dev-flow-logs-')].Name|[0]" --output text)
aws s3 cp "s3://$bucket/" "$WORK/logs" --recursive --quiet --exclude '*' --include "*/${DAY//-//}/*"

vpc=$(aws ssm get-parameter --name /twr/dev/foundation/network/vpc_id --query Parameter.Value --output text)
aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" \
  --query 'Subnets[].[CidrBlock,Tags[?Key==`Name`].Value|[0]]' --output text > "$WORK/in/subnets.txt"
for service in s3 dynamodb; do
  list=$(aws ec2 describe-managed-prefix-lists --filters "Name=prefix-list-name,Values=com.amazonaws.$REGION.$service" \
    --query 'PrefixLists[0].PrefixListId' --output text)
  aws ec2 get-managed-prefix-list-entries --prefix-list-id "$list" --query 'Entries[].Cidr' --output text \
    | tr '\t' '\n' > "$WORK/in/$service.cidrs"
done
curl -sSf -o "$WORK/in/ip-ranges.json" https://ip-ranges.amazonaws.com/ip-ranges.json
cp "$HERE/nat-traffic.py" "$WORK/script/"

DOCKER_HOST=${DOCKER_HOST:-unix:///var/run/docker.sock} docker run --rm --network bridge -e SINCE="$SINCE" \
  -v "$WORK/logs:/logs:ro" -v "$WORK/in:/in:ro" -v "$WORK/script:/script:ro" python:3.12-slim \
  sh -c 'pip install -q --root-user-action=ignore pyarrow==22.0.0 2>/dev/null && python -I /script/nat-traffic.py'
