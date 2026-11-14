# Gateway endpoints (D3), free: ECR layer pulls, QR writes and every seat write skip the NAT.
# Their policies are the data-exfiltration control: through our endpoint a compromised task can
# reach only the project's buckets and tables, never someone else's.

locals {
  # Every route table that carries task traffic. db subnets reach no AWS API.
  endpoint_route_table_ids = module.vpc.private_route_table_ids
}

data "aws_iam_policy_document" "s3_endpoint" {
  statement {
    sid     = "ProjectBuckets"
    actions = ["s3:*"]
    resources = [
      "arn:aws:s3:::${var.name_prefix}-*",
      "arn:aws:s3:::${var.name_prefix}-*/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceAccount"
      values   = [local.account_id]
    }
  }

  # ECR stores image layers in an AWS-owned bucket. Without this statement every image pull
  # through the endpoint fails: the classic way an endpoint policy breaks a cluster (D3).
  statement {
    sid       = "EcrImageLayers"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::prod-${local.region}-starport-layer-bucket/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.endpoint_route_table_ids
  policy            = data.aws_iam_policy_document.s3_endpoint.json

  tags = {
    Name = "${var.name_prefix}-s3"
  }
}

data "aws_iam_policy_document" "dynamodb_endpoint" {
  statement {
    sid     = "ProjectTables"
    actions = ["dynamodb:*"]
    resources = [
      "arn:aws:dynamodb:${local.region}:${local.account_id}:table/${var.name_prefix}-*",
      "arn:aws:dynamodb:${local.region}:${local.account_id}:table/${var.name_prefix}-*/index/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${local.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.endpoint_route_table_ids
  policy            = data.aws_iam_policy_document.dynamodb_endpoint.json

  tags = {
    Name = "${var.name_prefix}-dynamodb"
  }
}
