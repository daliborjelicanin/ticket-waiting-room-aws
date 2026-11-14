output "vpc_id" {
  description = "VPC id."
  value       = module.vpc.vpc_id
}

output "vpc_cidr" {
  description = "VPC CIDR block."
  value       = module.vpc.vpc_cidr_block
}

output "public_subnet_ids" {
  description = "Public subnets (NAT only), in AZ order."
  value       = module.vpc.public_subnets
}

output "app_subnet_ids" {
  description = "App subnets (ALB, VPC link, tasks), in AZ order."
  value       = module.vpc.private_subnets
}

output "db_subnet_ids" {
  description = "Isolated db subnets, in AZ order."
  value       = module.vpc.database_subnets
}

output "db_subnet_group_name" {
  description = "RDS subnet group over the db subnets."
  value       = module.vpc.database_subnet_group_name
}

output "app_route_table_ids" {
  description = "Route tables of the app subnets: the session NAT adds its default route here (D4)."
  value       = module.vpc.private_route_table_ids
}

output "security_group_ids" {
  description = "Security group id per workload and per tier (vpclink, alb, cache, db)."
  value = merge(
    { for k, sg in aws_security_group.workload : k => sg.id },
    {
      vpclink = aws_security_group.vpclink.id
      alb     = aws_security_group.alb.id
      cache   = aws_security_group.cache.id
      db      = aws_security_group.db.id
    },
  )
}

output "flow_logs_bucket" {
  description = "Bucket holding the VPC flow logs."
  value       = aws_s3_bucket.flow_logs.bucket
}
