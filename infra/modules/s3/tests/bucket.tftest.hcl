# The bucket baseline (D11, D24): public access blocked, ACLs off, TLS only, the CMK with a
# bucket key when a key is given, and optional expiry. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  name            = "twr-test-qr"
  kms_key_arn     = "arn:aws:kms:eu-central-1:aws:key/test"
  expiration_days = 7
}

run "private_and_owner_enforced" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_public_access_block.this.block_public_acls
      && aws_s3_bucket_public_access_block.this.block_public_policy
      && aws_s3_bucket_public_access_block.this.ignore_public_acls
      && aws_s3_bucket_public_access_block.this.restrict_public_buckets
    )
    error_message = "All four public-access blocks are on (D24)."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.this.rule[0].object_ownership == "BucketOwnerEnforced"
    error_message = "ACLs off: BucketOwnerEnforced (D24)."
  }

  assert {
    condition     = !aws_s3_bucket.this.force_destroy
    error_message = "force_destroy is off unless asked for."
  }
}

run "tls_only" {
  command = plan

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.this.statement : (
      s.sid == "DenyInsecureTransport" && s.effect == "Deny"
      && anytrue([for c in s.condition : (c.variable == "aws:SecureTransport" && contains(c.values, "false"))])
    )])
    error_message = "The bucket policy denies non-TLS requests."
  }
}

run "cmk_with_bucket_key_and_expiry" {
  command = plan

  assert {
    condition = anytrue([for r in aws_s3_bucket_server_side_encryption_configuration.this.rule : (
      r.bucket_key_enabled
      && anytrue([for d in r.apply_server_side_encryption_by_default : (d.sse_algorithm == "aws:kms" && d.kms_master_key_id == var.kms_key_arn)])
    )])
    error_message = "With a key: SSE-KMS on the CMK, with a bucket key (~99% fewer KMS calls, D11)."
  }

  assert {
    condition     = length(aws_s3_bucket_lifecycle_configuration.this) == 1 && aws_s3_bucket_lifecycle_configuration.this[0].rule[0].expiration[0].days == 7
    error_message = "Objects expire after expiration_days."
  }
}

run "sse_s3_without_a_key_and_no_expiry" {
  command = plan

  variables {
    kms_key_arn     = null
    expiration_days = null
  }

  assert {
    condition = anytrue([for r in aws_s3_bucket_server_side_encryption_configuration.this.rule : (
      !r.bucket_key_enabled
      && anytrue([for d in r.apply_server_side_encryption_by_default : (d.sse_algorithm == "AES256")])
    )])
    error_message = "Without a key: SSE-S3."
  }

  assert {
    condition     = length(aws_s3_bucket_lifecycle_configuration.this) == 0
    error_message = "No lifecycle without expiration_days."
  }
}

run "long_name_is_refused" {
  command = plan

  variables {
    name = "twr-test-a-name-that-is-far-too-long-for-a-bucket-x"
  }

  expect_failures = [var.name]
}
