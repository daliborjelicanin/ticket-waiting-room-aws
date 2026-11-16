# Phase 1 Destroy Checklist (management account)

Slice 1's last item (`docs/phase2-plan.md`): remove what Phase 1 built by hand in the
**management account**, now that `twr-dev` runs the same system from code. The retro's rule is
*destroy, don't import* (§6.3). Nothing here is wanted: images are rebuilt by CI, test users by
`create-test-users.sh`, test data by the seeders.

**Status: done 2026-11-16.** Inventory taken read-only, reviewed by the owner, who took every
recommendation in [Owner decisions](#owner-decisions); then run step by step, each step in a
fresh shell that first confirmed it was in the management account. Every box below is ticked;
[Result](#result) is what the verification showed. The commands are kept as the record and as
the template for Phase 2's own end-of-phase teardown.

## What is there, and what it costs

The read-only sweep (`AWS_PROFILE=default ALL_REGIONS=1 ./scripts/teardown-sweep.sh`) found
**nothing billing by the hour**, and nothing outside eu-central-1 except the organization's own
resources (us-east-1 is where Organizations, budgets and IAM are indexed). The one standing
charge is ECR: 8 repositories, 2.1 GB, about $0.16/month. Everything else is free at rest. So
the reason to destroy is not money: it is that hand-built, untagged resources with console
defaults and auto-named roles sit beside the organization's control plane, in the account that
D12 says holds no workloads.

## Keep: never touched by this checklist

These belong to the organization, to Identity Center, or to AWS, not to Phase 1:

| Resource | Why it stays |
|---|---|
| Organizations, OU `workloads`, the two SCPs, the member account | `infra/org` (D12) |
| IAM Identity Center: permission sets, assignments, the `AWSReservedSSO_*` roles | `infra/org`; the only way in |
| **KMS key `mrk-…`, "IAM Identity Center customer managed key"** | Identity Center encrypts with it. **Deleting or disabling it breaks every SSO sign-in.** It is the only customer-managed key here and it is not Phase 1's |
| S3 `twr-org-tfstate-<account-id>` | `infra/org`'s state |
| Budget `twr-management-zero-spend`, cost allocation tag `project` | `infra/org` |
| Default VPC, its subnets and its `default` security group | AWS default, free |
| `AWSServiceRoleFor*` (17 service-linked roles) | owned by AWS services; deleted only through each service |
| X-Ray `Default` group and sampling rule, cost anomaly monitor `Default-Services-Monitor` | AWS defaults, free |

## Owner decisions

Answer these before running anything. The recommendation is the first option.

1. **CloudTrail `account-trail`** and its bucket `cloudtrail-<account-id>-eu-central-1`
   (multi-region, not an organization trail, 30 MB, 365-day expiry, created 2026-10-09).
   - *Recommended: keep.* The management account is where Organizations, SCP and Identity
     Center changes happen, which makes it the account whose audit trail matters most; the
     first copy of management events is free. `twr-dev` has its own trail (D24).
   - Or delete: `aws cloudtrail delete-trail --name account-trail`, then empty and remove the
     bucket.
2. **GuardDuty detector** (enabled 2026-10-09). *Recommended: keep*: the management account is
   the highest-value target in the organization, and with almost no activity its volume-based
   bill is pennies. Or `aws guardduty delete-detector --detector-id <id>`.
3. **Console budgets** `My Monthly Cost Budget` ($10/month) and `My Zero-Spend Budget` ($1).
   - *Recommended:* delete `My Zero-Spend Budget`, which `twr-management-zero-spend` (in
     `infra/org`) duplicates. Keep `My Monthly Cost Budget`: a budget in the management account
     sees the whole organization's consolidated spend, so it is the one org-wide cap. Moving it
     into `infra/org` is a later change.
4. **`lambda-api-gateway-API`**, an HTTP API from 2024, not Phase 1. Its only integration
   points at a function `lambda-api-gateway` that no longer exists, so it serves nothing.
   *Recommended: delete* (step 3).
5. **RDS subnet group `default`** (no VPC suffix). Not the Phase 1 one
   (`default-<vpc-id>`, "Created from the RDS Management Console"). Free. *Recommended: delete
   both* (step 15); RDS recreates a default when a console wizard needs one.
6. **Not part of this checklist, but found by it: the IAM user `dalibor`** (2024: console
   password with MFA, no access keys). CLAUDE.md says human access is SSO only (D12). Once SSO
   has been the only way in for a while, this user is the next thing to remove. Do it as its own
   step, from an SSO session, never in the same sitting as anything else here.

## Before anything

- [x] Point the shell at the **management account**, and check it, not just set it (a profile
  left on the wrong account is how mistakes happen; see `infra/CLAUDE.md`):

  ```bash
  export AWS_PROFILE=default AWS_REGION=eu-central-1 AWS_PAGER=""
  [ "$(aws sts get-caller-identity --query Account --output text)" = "$(aws configure get sso_account_id --profile default)" ] && echo management
  ```
- [x] Nothing billing by the hour: `./scripts/teardown-sweep.sh` ends with
  `SWEEP: nothing billing by the hour`.
- [x] **Run the commands in bash.** zsh does not split an unquoted `$var` into words, so a
  loop over `$arns` gets one argument holding the whole list (it happened here, harmlessly, in
  step 5). Every loop below reads one item per line, which works in both.
- [x] `twr-dev` is untouched by every command below. The names differ (`twr-dev-…` there,
  Phase 1's bare names here), and every command runs under `AWS_PROFILE=default`.

## The order, and why

Inputs first (schedules, mappings, APIs), so nothing calls a function or a queue that is about
to go; then compute, messaging, data, identity; then the network pieces those referenced; log
groups after everything that writes to them; IAM last, because each step before uses a role.

### 1. Stop the inputs

- [x] Schedule (already `DISABLED`): `aws scheduler delete-schedule --name hold-seat-schedule`
- [x] The two event source mappings (`qr-generation`, `ticket-queue-notifier`):

  ```bash
  for f in qr-generation ticket-queue-notifier; do
    for u in $(aws lambda list-event-source-mappings --function-name "$f" --query 'EventSourceMappings[].UUID' --output text); do
      aws lambda delete-event-source-mapping --uuid "$u" --query State --output text
    done
  done
  ```

### 2. Step Functions

- [x] `checkout-saga` (no execution is running; the sweep checks):

  ```bash
  aws stepfunctions delete-state-machine --state-machine-arn \
    "$(aws stepfunctions list-state-machines --query 'stateMachines[?name==`checkout-saga`].stateMachineArn' --output text)"
  ```

### 3. API Gateway (5 Phase 1 APIs, and decision 4)

- [x] REST `ticket-edge`, WebSocket `ticket-queue-socket`, HTTP `inventory-api`,
  `checkout-api`, `issuance-api` (+ `lambda-api-gateway-API` if decided):

  ```bash
  aws apigateway delete-rest-api --rest-api-id \
    "$(aws apigateway get-rest-apis --query 'items[?name==`ticket-edge`].id' --output text)"
  for n in ticket-queue-socket inventory-api checkout-api issuance-api; do   # + lambda-api-gateway-API
    aws apigatewayv2 delete-api --api-id "$(aws apigatewayv2 get-apis --query "Items[?Name=='$n'].ApiId" --output text)"
  done
  ```

  API Gateway allows a few deletions per minute per account; on `TooManyRequestsException`,
  wait 30 s and run the loop again.

### 4. Lambda

- [x] `hold-cleanup`, `qr-generation`, `ticket-queue-notifier`:
  `for f in hold-cleanup qr-generation ticket-queue-notifier; do aws lambda delete-function --function-name "$f"; done`

### 5. ECS

- [x] The 24 task-definition revisions (families `seat-inventory-service`,
  `waiting-room-service`, `checkout-payment-service`, `ticket-issuance-service`,
  `notification-service`, `netdiag`): deregister, then delete.

  ```bash
  aws ecs list-task-definitions --query 'taskDefinitionArns[]' --output text | tr '\t' '\n' |
    while read -r a; do aws ecs deregister-task-definition --task-definition "$a" --query taskDefinition.status --output text; done
  aws ecs list-task-definitions --status INACTIVE --query 'taskDefinitionArns[]' --output text | tr '\t' '\n' |
    xargs -n 10 aws ecs delete-task-definitions --query 'length(failures)' --output text --task-definitions
  ```
- [x] Cluster (no services, no tasks): `aws ecs delete-cluster --cluster ticket-waiting-room`

### 6. Load balancing

- [x] Target groups (their ALB is long gone):
  `checkout-tg`, `seat-inventory-tg`, `ticket-issuance-tg`, `waiting-room-tg`

  ```bash
  for n in checkout-tg seat-inventory-tg ticket-issuance-tg waiting-room-tg; do
    aws elbv2 delete-target-group --target-group-arn "$(aws elbv2 describe-target-groups --names "$n" --query 'TargetGroups[0].TargetGroupArn' --output text)"
  done
  ```

### 7. SNS

- [x] Topic `purchase-confirmed` with its two subscriptions (an email, and the
  `purchase-confirmed-notifications` queue). Deleting the topic deletes them:

  ```bash
  aws sns delete-topic --topic-arn "$(aws sns list-topics --query "Topics[?ends_with(TopicArn, ':purchase-confirmed')].TopicArn" --output text)"
  ```

### 8. SQS (8 queues)

- [x] `ticket-issuance-queue`, `qr-generation-queue`, `queue-admissions`,
  `purchase-confirmed-notifications`, and each one's `-dlq`:

  ```bash
  for q in ticket-issuance-queue ticket-issuance-dlq qr-generation-queue qr-generation-dlq \
           queue-admissions queue-admissions-dlq purchase-confirmed-notifications purchase-confirmed-notifications-dlq; do
    aws sqs delete-queue --queue-url "$(aws sqs get-queue-url --queue-name "$q" --query QueueUrl --output text)"
  done
  ```

  A deleted queue's name cannot be reused for 60 seconds; irrelevant here, since Phase 2's
  names differ.

### 9. DynamoDB

- [x] `seat-inventory` (61 test items) and `waiting-room-connections` (empty). Neither has
  deletion protection:
  `for t in seat-inventory waiting-room-connections; do aws dynamodb delete-table --table-name "$t" --query TableDescription.TableStatus --output text; done`

### 10. Cognito

- [x] `ticket-users`: 52 test users, a domain, and deletion protection **`ACTIVE`**, so three
  steps in this order:

  ```bash
  pool=$(aws cognito-idp list-user-pools --max-results 60 --query 'UserPools[?Name==`ticket-users`].Id' --output text)
  aws cognito-idp delete-user-pool-domain --user-pool-id "$pool" \
    --domain "$(aws cognito-idp describe-user-pool --user-pool-id "$pool" --query UserPool.Domain --output text)"
  aws cognito-idp update-user-pool --user-pool-id "$pool" --deletion-protection INACTIVE
  aws cognito-idp delete-user-pool --user-pool-id "$pool"
  ```

  `update-user-pool` resets every setting it is not given. That is harmless for a pool deleted
  one command later, and it is why that command must never be pointed at a pool you keep.

### 11. SES

- [x] The two address identities in this account (`twr-dev` has its own, from
  `infra/live/foundation/ses.tf`):

  ```bash
  for i in $(aws sesv2 list-email-identities --query 'EmailIdentities[].IdentityName' --output text); do
    aws sesv2 delete-email-identity --email-identity "$i"
  done
  ```

### 12. S3

- [x] `ticket-qr-<account-id>-eu-central-1` (empty; versioning off, so no hidden versions):

  ```bash
  aws s3 rb "s3://$(aws s3api list-buckets --query "Buckets[?starts_with(Name, 'ticket-qr-')].Name" --output text)"
  ```

### 13. ECR (8 repositories, 2.1 GB)

- [x] `seat-inventory-service`, `waiting-room-service`, `checkout-payment-service`,
  `ticket-issuance-service`, `notification-service`, `hold-cleanup`, `qr-generation`,
  `queue-socket`, with their images (`--force`):

  ```bash
  for r in seat-inventory-service waiting-room-service checkout-payment-service ticket-issuance-service \
           notification-service hold-cleanup qr-generation queue-socket; do
    aws ecr delete-repository --repository-name "$r" --force --query repository.repositoryName --output text
  done
  ```

### 14. Configuration and dashboards

- [x] SSM `/ticket-waiting-room/otel-collector-config`:
  `aws ssm delete-parameter --name /ticket-waiting-room/otel-collector-config`
- [x] Dashboard `ticket-waiting-room`:
  `aws cloudwatch delete-dashboards --dashboard-names ticket-waiting-room`

### 15. Network pieces in the default VPC

- [x] ElastiCache subnet group `ticket-waiting-room-cache-subnets`:
  `aws elasticache delete-cache-subnet-group --cache-subnet-group-name ticket-waiting-room-cache-subnets`
- [x] RDS subnet groups (decision 5):
  `aws rds delete-db-subnet-group --db-subnet-group-name "default-$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)"`,
  and `default` if decided
- [x] The 8 security groups: `seat-inventory-sg`, `ticket-alb-sg`, `waiting-room-cache-sg`,
  `checkout-payment-sg`, `ticket-issuance-sg`, `checkout-db-sg`, `notification-sg`,
  `waiting-room-sg`. They reference each other, and a group another group's rule names cannot
  be deleted, so revoke every ingress rule first, then delete:

  ```bash
  for n in seat-inventory-sg ticket-alb-sg waiting-room-cache-sg checkout-payment-sg ticket-issuance-sg \
           checkout-db-sg notification-sg waiting-room-sg; do
    aws ec2 describe-security-groups --filters Name=group-name,Values="$n" --query 'SecurityGroups[0].GroupId' --output text
  done > /tmp/sgs.txt
  while read -r id; do   # revoke inbound rules, and outbound rules that name another group
    in=$(aws ec2 describe-security-groups --group-ids "$id" --query 'SecurityGroups[0].IpPermissions' --output json)
    [ "$in" != "[]" ] && aws ec2 revoke-security-group-ingress --group-id "$id" --ip-permissions "$in" --query Return --output text
    out=$(aws ec2 describe-security-groups --group-ids "$id" --query 'SecurityGroups[0].IpPermissionsEgress[?length(UserIdGroupPairs)>`0`]' --output json)
    [ "$out" != "[]" ] && aws ec2 revoke-security-group-egress --group-id "$id" --ip-permissions "$out" --query Return --output text
  done < /tmp/sgs.txt
  while read -r id; do aws ec2 delete-security-group --group-id "$id"; done < /tmp/sgs.txt
  ```

  The sweep found **no network interfaces** left in the default VPC, so nothing still uses them.
  A `DependencyViolation` means one does: find it with
  `aws ec2 describe-network-interfaces --filters Name=group-id,Values=<sg-id>`.

### 16. Log groups (11), after everything that wrote to them

- [x] `/aws/lambda/hold-cleanup`, `/aws/lambda/qr-generation`, `/aws/lambda/ticket-queue-notifier`,
  `/aws/vendedlogs/states/checkout-saga`, `/ecs/checkout-payment-service`,
  `/ecs/notification-service`, `/ecs/otel-collector`, `/ecs/seat-inventory-service`,
  `/ecs/ticket-issuance-service`, `/ecs/waiting-room-service`, `/ticket-waiting-room/metrics`
  (all empty: retention already expired them):

  ```bash
  for g in /aws/lambda/hold-cleanup /aws/lambda/qr-generation /aws/lambda/ticket-queue-notifier \
           /aws/vendedlogs/states/checkout-saga /ecs/checkout-payment-service /ecs/notification-service \
           /ecs/otel-collector /ecs/seat-inventory-service /ecs/ticket-issuance-service \
           /ecs/waiting-room-service /ticket-waiting-room/metrics; do
    aws logs delete-log-group --log-group-name "$g"
  done
  ```

### 17. IAM, last

- [x] The 15 Phase 1 roles, each with its inline policies and attachments removed first:
  `Amazon_EventBridge_Scheduler_LAMBDA_374c074501`, `notification-task-role`,
  `qr-generation-role-r4yviiqj`, `rds-monitoring-role`, `seat-inventory-task-role`,
  `ticket-checkout-task-role`, `ticket-ecs-execution-role`, `ticket-edge-apigw-role`,
  `ticket-hold-cleanup-lambda-role`, `ticket-issuance-task-role`,
  `ticket-qr-generation-lambda-role`, `ticket-queue-notifier-lambda-role`,
  `ticket-queue-socket-apigw-role`, `ticket-saga-role`, `ticket-waiting-room-task-role`:

  ```bash
  for r in Amazon_EventBridge_Scheduler_LAMBDA_374c074501 notification-task-role qr-generation-role-r4yviiqj \
           rds-monitoring-role seat-inventory-task-role ticket-checkout-task-role ticket-ecs-execution-role \
           ticket-edge-apigw-role ticket-hold-cleanup-lambda-role ticket-issuance-task-role \
           ticket-qr-generation-lambda-role ticket-queue-notifier-lambda-role ticket-queue-socket-apigw-role \
           ticket-saga-role ticket-waiting-room-task-role; do
    for p in $(aws iam list-attached-role-policies --role-name "$r" --query 'AttachedPolicies[].PolicyArn' --output text); do
      aws iam detach-role-policy --role-name "$r" --policy-arn "$p"; done
    for p in $(aws iam list-role-policies --role-name "$r" --query PolicyNames --output text); do
      aws iam delete-role-policy --role-name "$r" --policy-name "$p"; done
    for ip in $(aws iam list-instance-profiles-for-role --role-name "$r" --query 'InstanceProfiles[].InstanceProfileName' --output text); do
      aws iam remove-role-from-instance-profile --instance-profile-name "$ip" --role-name "$r"; done
    aws iam delete-role --role-name "$r" && echo "deleted $r"
  done
  ```
- [x] The 4 customer-managed policies, now attached to nothing: `ticket-telemetry-export`, and
  the three the console generated (`AWSLambdaTracerAccessExecutionRole-<uuid>`,
  `Amazon-EventBridge-Scheduler-Execution-Policy-<uuid>`, `AWSLambdaBasicExecutionRole-<uuid>`).
  A policy with more than one version needs its non-default versions deleted first:

  ```bash
  for p in $(aws iam list-policies --scope Local --query 'Policies[?AttachmentCount==`0`].Arn' --output text); do
    for v in $(aws iam list-policy-versions --policy-arn "$p" --query 'Versions[?!IsDefaultVersion].VersionId' --output text); do
      aws iam delete-policy-version --policy-arn "$p" --version-id "$v"; done
    aws iam delete-policy --policy-arn "$p" && echo "deleted $p"
  done
  ```

  Read the list the first command prints before running the loop: it deletes **every**
  unattached customer-managed policy in the account, and today those are exactly these four.

### 18. Owner decisions 1–3, if any were "delete"

- [x] As decided above.

## Verify

- [x] `AWS_PROFILE=default ALL_REGIONS=1 ./scripts/teardown-sweep.sh`: BILLING clean; STANDING
  shows no ECR repositories and only the buckets kept (`twr-org-tfstate-…`, and the trail's if
  kept); TAGS lists nothing `untagged` and no API; the only customer KMS key is Identity
  Center's.
- [x] No Phase 1 role is left. This prints the roles that are neither service-linked nor SSO,
  and should print nothing named `ticket-*`, `notification-*`, `seat-inventory-*` or
  `*-role-*`:

  ```bash
  aws iam list-roles --output text \
    --query 'Roles[?!starts_with(Path, `/aws-service-role/`) && !starts_with(Path, `/aws-reserved/`)].RoleName'
  ```
- [x] SSO still works: `aws sts get-caller-identity --profile twr-dev` and
  `--profile default` both answer.
- [x] `twr-dev` untouched: `terraform plan` of `infra/live/foundation` shows **No changes**.
- [x] Tick slice 1's last checkbox in `docs/phase2-plan.md` with the date and what was kept.

## Result

Run 2026-11-16, decisions as recommended: trail, GuardDuty and `My Monthly Cost Budget` kept;
`My Zero-Spend Budget`, `lambda-api-gateway-API` and both RDS subnet groups deleted; the IAM
user left for its own step.

| Step | Deleted |
|---|---|
| 1–2 | the schedule, 2 event source mappings, `checkout-saga` (no running executions) |
| 3–4 | 6 APIs (REST, WebSocket, 4 HTTP incl. the 2024 one), 3 functions |
| 5–6 | 34 task-definition revisions (24 active + 10 already inactive), the cluster, 4 target groups |
| 7–9 | the topic with its 2 subscriptions, 8 queues, 2 tables |
| 10–12 | the user pool (domain first, then protection off), 2 SES identities, the QR bucket (empty) |
| 13–14 | 8 ECR repositories (2.1 GB), the collector parameter, the dashboard |
| 15 | the cache subnet group, 2 RDS subnet groups, 8 security groups (7 had inbound rules revoked first; no network interfaces were left) |
| 16–17 | 11 log groups, 15 roles, 4 policies (the unattached list was checked to be exactly those four before the loop ran) |
| 18 | `My Zero-Spend Budget` |

Verification:

- Sweep, all regions: BILLING clean; ECR 0 MB; S3 holds only the org state bucket and the
  trail's; the one customer KMS key is Identity Center's, `Enabled`; no task definitions.
- No IAM role is left that is neither service-linked nor SSO.
- The tag index still listed the cluster (`INACTIVE`), 15 task definitions
  (`DELETE_IN_PROGRESS`) and the user pool (gone): stale entries, which the index drops on its
  own. us-east-1's 5 tagged resources are the organization's account, OU, two SCPs and budget.
- CloudTrail `account-trail` logging, GuardDuty `ENABLED`, both SSO profiles answer.
- `twr-dev`: `infra/live/foundation` plans **No changes**.
