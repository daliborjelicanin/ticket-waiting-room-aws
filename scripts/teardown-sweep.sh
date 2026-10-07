#!/usr/bin/env bash
# Read-only sweep for anything left behind after a session. Changes nothing.
#
#   ./scripts/teardown-sweep.sh            # home region + us-east-1 (dashboards, IAM, CloudFront WAF)
#   ALL_REGIONS=1 ./scripts/teardown-sweep.sh   # also the tag search + compute in every enabled region
#
# Three tiers, because they need different responses:
#   BILLING  — charges by the hour or month whether used or not. Exit status 1 if any.
#   STANDING — charges by size (ECR, S3, logs). Reported, never fails the sweep.
#   TAGS     — project-named resources the tag search cannot see. A tag sweep only finds
#              what was tagged, so this is the check on the check.
#
# Deliberately makes no Cost Explorer call: each `ce` request is billed at $0.01.
set -uo pipefail

export AWS_PAGER=""
REGION="${AWS_REGION:-eu-central-1}"
TAG_KEY=project
TAG_VALUE=ticket-waiting-room
NAME_PATTERN='ticket|seat|checkout|issuance|notification|waiting-room|qr-generation|hold-cleanup|queue-|purchase-confirmed'

hits=0
a() { aws --region "$REGION" "$@" 2>/dev/null; }

# billing <label> <command...> — the command prints one finding per line, nothing when clean
billing() {
  local label=$1; shift
  local out; out=$("$@" | sed '/^None$/d;/^$/d')
  if [[ -n $out ]]; then
    printf '  [BILLING] %s\n' "$label"; sed 's/^/      /' <<<"$out"; hits=$((hits + 1))
  else
    printf '  ok        %s\n' "$label"
  fi
}

echo "== BILLING ($REGION)"
billing "ECS services with desired > 0" sh -c '
  for c in $(aws --region '"$REGION"' ecs list-clusters --query clusterArns --output text); do
    for s in $(aws --region '"$REGION"' ecs list-services --cluster "$c" --query serviceArns --output text); do
      aws --region '"$REGION"' ecs describe-services --cluster "$c" --services "$s" \
        --query "services[?desiredCount>\`0\`].[serviceName,desiredCount,runningCount]" --output text
    done
  done'
billing "ECS running tasks" sh -c '
  for c in $(aws --region '"$REGION"' ecs list-clusters --query clusterArns --output text); do
    aws --region '"$REGION"' ecs list-tasks --cluster "$c" --query taskArns --output text
  done'
billing "Load balancers (ALB \$0.027/h)" a elbv2 describe-load-balancers --query 'LoadBalancers[].[LoadBalancerName,Type,State.Code]' --output text
billing "RDS instances (stopped still bills storage)" a rds describe-db-instances --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceStatus]' --output text
billing "RDS clusters" a rds describe-db-clusters --query 'DBClusters[].[DBClusterIdentifier,Status]' --output text
billing "RDS manual snapshots" a rds describe-db-snapshots --snapshot-type manual --query 'DBSnapshots[].[DBSnapshotIdentifier,AllocatedStorage]' --output text
billing "RDS retained automated backups" a rds describe-db-instance-automated-backups --query 'DBInstanceAutomatedBackups[].DBInstanceIdentifier' --output text
billing "ElastiCache replication groups" a elasticache describe-replication-groups --query 'ReplicationGroups[].[ReplicationGroupId,Status]' --output text
billing "ElastiCache nodes" a elasticache describe-cache-clusters --query 'CacheClusters[].[CacheClusterId,CacheClusterStatus]' --output text
billing "ElastiCache serverless" a elasticache describe-serverless-caches --query 'ServerlessCaches[].ServerlessCacheName' --output text
billing "ElastiCache snapshots" a elasticache describe-snapshots --query 'Snapshots[].SnapshotName' --output text
billing "WAF web ACLs, regional (~\$5/mo + Bot Control \$10/mo)" a wafv2 list-web-acls --scope REGIONAL --query 'WebACLs[].Name' --output text
billing "WAF web ACLs, CloudFront" aws --region us-east-1 wafv2 list-web-acls --scope CLOUDFRONT --query 'WebACLs[].Name' --output text
billing "NAT gateways" a ec2 describe-nat-gateways --filter Name=state,Values=pending,available --query 'NatGateways[].NatGatewayId' --output text
billing "Elastic IPs" a ec2 describe-addresses --query 'Addresses[].[PublicIp,AssociationId]' --output text
billing "Public IPv4 on any ENI (\$0.005/h each)" a ec2 describe-network-interfaces --query 'NetworkInterfaces[?Association.PublicIp].[NetworkInterfaceId,Description]' --output text
billing "EC2 instances (running or stopped)" a ec2 describe-instances --filters Name=instance-state-name,Values=pending,running,stopping,stopped --query 'Reservations[].Instances[].[InstanceId,State.Name]' --output text
billing "Unattached EBS volumes" a ec2 describe-volumes --filters Name=status,Values=available --query 'Volumes[].[VolumeId,Size]' --output text
billing "Interface VPC endpoints" a ec2 describe-vpc-endpoints --filters Name=vpc-endpoint-type,Values=Interface --query 'VpcEndpoints[].ServiceName' --output text
billing "API Gateway VPC links" sh -c 'aws --region '"$REGION"' apigateway get-vpc-links --query "items[].name" --output text; aws --region '"$REGION"' apigatewayv2 get-vpc-links --query "Items[].Name" --output text'
billing "REST API stage caches" sh -c '
  for id in $(aws --region '"$REGION"' apigateway get-rest-apis --query "items[].id" --output text); do
    aws --region '"$REGION"' apigateway get-stages --rest-api-id "$id" --query "item[?cacheClusterEnabled].stageName" --output text
  done'
billing "EventBridge connections (each a \$0.40/mo secret)" a events list-connections --query 'Connections[].[Name,ConnectionState]' --output text
billing "Secrets Manager secrets (\$0.40/mo each)" a secretsmanager list-secrets --query 'SecretList[].Name' --output text
billing "Customer-managed KMS keys (\$1/mo each)" sh -c '
  for k in $(aws --region '"$REGION"' kms list-keys --query "Keys[].KeyId" --output text); do
    aws --region '"$REGION"' kms describe-key --key-id "$k" \
      --query "KeyMetadata[?KeyManager==\`CUSTOMER\` && KeyState!=\`PendingDeletion\`].KeyId" --output text
  done'
billing "Lambda provisioned concurrency" sh -c '
  for f in $(aws --region '"$REGION"' lambda list-functions --query "Functions[].FunctionName" --output text); do
    aws --region '"$REGION"' lambda list-provisioned-concurrency-configs --function-name "$f" \
      --query "ProvisionedConcurrencyConfigs[].[\`$f\`,RequestedProvisionedConcurrentExecutions]" --output text
  done'
billing "Enabled EventBridge schedules" a scheduler list-schedules --state ENABLED --query 'Schedules[].Name' --output text
billing "Running Step Functions executions" sh -c '
  for m in $(aws --region '"$REGION"' stepfunctions list-state-machines --query "stateMachines[].stateMachineArn" --output text); do
    aws --region '"$REGION"' stepfunctions list-executions --state-machine-arn "$m" --status-filter RUNNING --query "executions[].name" --output text
  done'
billing "DynamoDB tables not on-demand" sh -c '
  for t in $(aws --region '"$REGION"' dynamodb list-tables --query TableNames --output text); do
    aws --region '"$REGION"' dynamodb describe-table --table-name "$t" \
      --query "Table[?BillingModeSummary.BillingMode!=\`PAY_PER_REQUEST\`].TableName" --output text
  done'
billing "CloudFront distributions" aws cloudfront list-distributions --query 'DistributionList.Items[].[Id,Status]' --output text
billing "Route 53 hosted zones (\$0.50/mo each)" aws route53 list-hosted-zones --query 'HostedZones[].Name' --output text

echo
echo "== STANDING ($REGION)"
ecr_bytes=0
for r in $(a ecr describe-repositories --query 'repositories[].repositoryName' --output text); do
  read -r n b < <(a ecr describe-images --repository-name "$r" --query '[length(imageDetails), sum(imageDetails[].imageSizeInBytes)]' --output text)
  printf '  ECR %-28s %2s image(s) %6s MB\n' "$r" "$n" "$((b / 1000000))"
  ecr_bytes=$((ecr_bytes + b))
done
printf '  ECR total %s MB — free tier 500 MB, then $0.10/GB-month (~$%s/mo)\n' \
  "$((ecr_bytes / 1000000))" "$(awk -v b="$ecr_bytes" 'BEGIN { x = (b - 5e8) / 1e9 * 0.10; printf "%.2f", x < 0 ? 0 : x }')"
for bkt in $(aws s3api list-buckets --query 'Buckets[].Name' --output text); do
  printf '  S3  %-40s %s\n' "$bkt" "$(aws s3 ls "s3://$bkt" --recursive --summarize 2>/dev/null | awk '/Total Size/ { print $3 " bytes" }')"
done
a logs describe-log-groups --query 'logGroups[?retentionInDays==null].logGroupName' --output text \
  | tr '\t' '\n' | sed '/^$/d;s/^/  Log group never expires: /'
a ecs list-task-definitions --status ACTIVE --query 'length(taskDefinitionArns)' --output text \
  | sed 's/^/  Active task-definition revisions (free, but each pins an image and a role ARN): /'

echo
echo "== TAGS"
tagged=$(a resourcegroupstaggingapi get-resources --tag-filters "Key=$TAG_KEY,Values=$TAG_VALUE" \
  --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n')
echo "  $(grep -c . <<<"$tagged") resources tagged $TAG_KEY=$TAG_VALUE in $REGION"
untagged() {   # untagged <kind> <arn-infix> <name>... — project-named, absent from the tag index
  local kind=$1 infix=$2; shift 2
  for n in "$@"; do
    [[ $n =~ $NAME_PATTERN ]] || continue
    grep -qE -- "${infix}${n}\$" <<<"$tagged" || printf '  untagged  %-14s %s\n' "$kind" "$n"
  done
}
untagged lambda   :function: $(a lambda list-functions --query 'Functions[].FunctionName' --output text)
untagged ecr      :repository/ $(a ecr describe-repositories --query 'repositories[].repositoryName' --output text)
untagged sqs      :sqs:[^:]+:[0-9]+: $(a sqs list-queues --query 'QueueUrls' --output text | tr '\t' '\n' | sed 's|.*/||')
untagged dynamodb :table/ $(a dynamodb list-tables --query TableNames --output text)
untagged log      :log-group: $(a logs describe-log-groups --query 'logGroups[].logGroupName' --output text)
# Security groups are indexed by id, not name — ask EC2 for the tag directly.
untagged sg       '' $(a ec2 describe-security-groups --query "SecurityGroups[?!(Tags[?Key=='$TAG_KEY'])].GroupName" --output text)
untagged schedule /default/ $(a scheduler list-schedules --query 'Schedules[].Name' --output text)
# The tag index is eventually consistent and keeps entries for deleted API Gateway APIs, so
# list the real APIs beside it: an API whose integration points at a task IP is dead.
a apigatewayv2 get-apis --query 'Items[].[ApiId,Name,ProtocolType]' --output text | sed 's/^/  api       /'
a apigateway get-rest-apis --query 'items[].[id,name]' --output text | sed 's/^/  rest-api  /'

if [[ ${ALL_REGIONS:-0} == 1 ]]; then
  echo
  echo "== OTHER REGIONS (tag index, EC2, load balancers, RDS)"
  for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do
    [[ $r == "$REGION" ]] && continue
    t=$(aws --region "$r" resourcegroupstaggingapi get-resources --tag-filters "Key=$TAG_KEY,Values=$TAG_VALUE" --query 'length(ResourceTagMappingList)' --output text 2>/dev/null)
    e=$(aws --region "$r" ec2 describe-instances --filters Name=instance-state-name,Values=running,stopped --query 'length(Reservations)' --output text 2>/dev/null)
    l=$(aws --region "$r" elbv2 describe-load-balancers --query 'length(LoadBalancers)' --output text 2>/dev/null)
    d=$(aws --region "$r" rds describe-db-instances --query 'length(DBInstances)' --output text 2>/dev/null)
    flag=""; [[ "$e$l$d" != 000 ]] && { flag="  <-- BILLING"; hits=$((hits + 1)); }
    printf '  %-16s tagged=%s ec2=%s elb=%s rds=%s%s\n' "$r" "$t" "$e" "$l" "$d" "$flag"
  done
fi

echo
if ((hits)); then echo "SWEEP: $hits billing finding(s)"; exit 1; fi
echo "SWEEP: nothing billing by the hour"
