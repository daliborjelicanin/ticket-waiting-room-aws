# Runbook: Session Edge (Phase 2, slice 2)

The REST stage → VPC Link v2 → internal ALB path (D5) and the WAF on that stage (D6), as the
session stack builds them. This page starts with the two spikes that open slice 2; the rest of
the slice (NAT, the services, the full rule set) extends it.

What is billed while it is up (eu-central-1, §6): the ALB (~$0.03/h with its LCUs) and the web
ACL (~$6/month prorated hourly for the ACL and its one rule). The VPC link has no charge of its own.
Nothing here needs the NAT.

## 0. Shell

```bash
aws sso login --profile twr-dev
source infra/scripts/tf-env.sh         # on its own, never into a pipe
[ "$AWS_PROFILE" = twr-dev ] && echo ok
```

## 1. Foundation: the WAF log bucket

Adds the `aws-waf-logs-twr-dev-<account-id>` bucket (7-day expiry, TLS-only, log delivery
grant) and two SSM parameters, `waf/acl_name` and `waf/log_bucket_arn`.

```bash
terraform -chdir=infra/live/foundation plan -out=foundation.tfplan
# read it: only the module.waf_log_bucket resources and two aws_ssm_parameter.published adds
terraform -chdir=infra/live/foundation apply foundation.tfplan
terraform -chdir=infra/live/foundation plan            # second plan: No changes
```

## 2. Session: ALB, link, stage and ACL

```bash
infra/scripts/tf-init.sh infra/live/session
terraform -chdir=infra/live/session plan -var enable_alb=true -var enable_waf=true -out=session.tfplan
terraform -chdir=infra/live/session apply session.tfplan
```

Expected: the ALB and its listener, the VPC link, the integration, the deployment and the
`dev` stage, the web ACL, its association and logging configuration. The VPC link can take a
few minutes to reach `AVAILABLE`, and the apply waits for it.

## 3. Spike D5: does the stage reach the internal ALB?

```bash
URL=$(terraform -chdir=infra/live/session output -raw invoke_url)
curl -sS -i "$URL/spike/d5"
```

- **Pass:** `HTTP/2 404` with the body `{"error":"NO_ROUTE"}`. That body exists only in the ALB
  listener's default action, so the request crossed the VPC link to the internal ALB. The
  access log in `/twr/dev/apigw/edge` shows `status` 404 and `integrationStatus` 404.
- **A `500` in the first minute or two after the apply is not a failure** (seen 2026-11-17): the
  access log shows `integrationStatus` `-`, so the request never left API Gateway. The new
  stage had not yet picked up the new VPC link. `aws apigateway test-invoke-method` on the
  `/{proxy+}` resource already returned the ALB's 404 at that moment. Retry before debugging.
- **Fail, `500 Internal server error` that persists:** read `integrationError` in the access log, and the
  link's state (`aws apigatewayv2 get-vpc-links --query 'Items[].[Name,VpcLinkStatus,VpcLinkStatusMessage]'`).
  Check the SG pair next: vpclink → alb on 80 (`modules/network/security_groups.tf`).
- **Fail at apply** (`integration_target` refused, or the feature not offered in eu-central-1):
  this is D5's fallback, an internet-facing ALB plus the edge header in SSM SecureString.
  Record it in D5 before building anything else.

The ALB is internal, so its DNS name resolves to private addresses only. The stage is the only
way in.

## 4. Spike D6: is JA4 computed for a REST-stage ACL?

Send traffic from two different TLS clients, so two different fingerprints are expected:

```bash
for i in $(seq 20); do curl -sS -o /dev/null "$URL/spike/d6"; done
python3 -c "import urllib.request,sys
for _ in range(20):
    try: urllib.request.urlopen(sys.argv[1] + '/spike/d6-python')
    except Exception: pass" "$URL"
```

WAF delivers to S3 in batches about every 5 minutes. Then:

```bash
B=$(aws ssm get-parameter --name /twr/dev/foundation/waf/log_bucket_arn --query Parameter.Value --output text | sed 's|.*:::||')
KEY=$(aws s3api list-objects-v2 --bucket "$B" --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)
aws s3 cp "s3://$B/$KEY" - | gunzip -c | jq -r '[.httpRequest.uri, .ja4Fingerprint // "MISSING", (.ruleGroupList|length|tostring)] | @tsv' | sort | uniq -c
```

- **Pass:** every line carries a `ja4Fingerprint` (shaped `t13d…_…_…`), and the curl and python
  requests show **different** fingerprints. JA4 is computed for a REST-stage ACL, so the fleet
  guard stands.
- **Fail:** the field is absent or empty. In that case the fleet guard waits for CloudFront (not
  built), as D6 says, and the other four buyer rules still stand. Remove the rule from
  `modules/waf` and record the result in D6.
- Also check, in the same records, that `httpRequest.headers` holds no `authorization` value
  (redacted fields show as `REDACTED`). Send one request with `-H 'Authorization: Bearer x'` to see it.

## 5. Record, destroy, sweep

Write the result into the slice 2 spike line in `docs/phase2-plan.md`, and, for a failure, into
D5 or D6 with the date. Then:

```bash
terraform -chdir=infra/live/session destroy -var enable_alb=true -var enable_waf=true
./scripts/teardown-sweep.sh
```

The WAF logs stay in foundation's bucket for 7 days.

## 6. The seat race through the edge (slice 2 verify)

One session, about 45 minutes at ≈ $0.13/h, proves four things together:
- **The race:** `k6/seat-race.js` from the laptop through the stage, the VPC link and the ALB to seat-inventory, with exactly one winner per seat.
- **The flow logs:** DynamoDB and the image layers take the gateway endpoints, not the NAT.
- **The collector:** its image comes through the pull-through cache.
- **The 401:** no longer names the internal ALB.

No `loadtest_allow_cidr`: the seat paths are not buyer entry, so no buyer rule and no method
throttle applies to them (D6).

**6.0 Image** (before the session; the `security-starter` fix ships with it). arm64 on an x86
laptop needs QEMU registered once per boot, in your own terminal (it runs privileged):
`docker run --privileged --rm tonistiigi/binfmt:qemu-v9.2.2 --install arm64`. Then:

```bash
./gradlew :seat-inventory-service:bootJar
TAG=$(git rev-parse --short HEAD)
REPO=$(aws ssm get-parameter --name /twr/dev/foundation/ecr/seat-inventory-service --query Parameter.Value --output text)
docker buildx build --platform linux/arm64 --load -t "$REPO:$TAG" seat-inventory-service
aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
docker push "$REPO:$TAG" && docker logout "${REPO%%/*}"
DIGEST=$(aws ecr describe-images --repository-name twr-dev-seat-inventory-service --image-ids imageTag=$TAG \
  --query 'imageDetails[0].imageDigest' --output text)
aws ssm put-parameter --name /twr/dev/images/seat-inventory-service --type String --value "$DIGEST" --overwrite
source infra/scripts/tf-env.sh             # re-resolve TF_VAR_image_digests
```

This is what `images.yml` will do from slice 4 (D15).

**6.1 Apply** (≈ 6 min; the service waits for steady state):

```bash
terraform -chdir=infra/live/session apply \
  -var enable_nat=true -var enable_alb=true -var enable_waf=true -var 'services=["seat-inventory"]'
```

**6.2 Before the race**

```bash
API=$(terraform -chdir=infra/live/session output -raw invoke_url)
# The 401 names no host: exactly "Bearer", no resource_metadata.
curl -s -D - -o /dev/null -X POST -A 'Mozilla/5.0' "$API/events/e1/seats/A1/hold" | grep -i www-authenticate
# The collector came through the cache: v0.50.0 now sits in the mirror, imported by the task's role.
aws ecr describe-images --repository-name mirror-twr-dev/aws-observability/aws-otel-collector \
  --query 'imageDetails[].{tags:imageTags,pushed:imagePushedAt}'
```

**6.3 Race** (from the repository root):

```bash
alias k6='docker run --rm -i --user "$(id -u):$(id -g)" -v "$PWD/load-test:/work" -w /work grafana/k6:0.55.0'
load-test/scripts/mint-tokens.sh 0                       # the operator token alone
EVENT=race-$(date +%s)
k6 run -e API=$API -e EVENT=$EVENT -e VUS=200 -e SEATS=10 k6/seat-race.js
load-test/scripts/seat-holds.sh $EVENT 10                 # within the 5-minute hold
```

Expected: k6 `won` 10, `lost` 190, `unexpected` 0, and `seat-holds.sh` 10 HELD, each with
its holdId. Record p95/p99 of won and lost, and the task count (target tracking at 600 requests per
target per minute is not expected to fire on 200 requests).

**6.4 Flow logs**: about 10 minutes after the race, while the session is still up or after
the destroy:

```bash
infra/scripts/nat-traffic.sh $(date -u +%F)
```

Expected, under *through the gateway endpoints*: DynamoDB above zero both ways (the holds)
and S3 (the image layers). Under *through the NAT*: no S3 and no DynamoDB rows at all, and
CLOUDFRONT only for the collector's one first import (≈ 50 MB), where the last session had 262 MB.
The report covers the whole day, earlier sessions included.

**6.5 Record, destroy, sweep**: the numbers into the slice 2 verify line of
`docs/phase2-plan.md`; then

```bash
terraform -chdir=infra/live/session destroy \
  -var enable_nat=true -var enable_alb=true -var enable_waf=true -var 'services=["seat-inventory"]'
./scripts/teardown-sweep.sh
rm -f load-test/.tokens.json
```
