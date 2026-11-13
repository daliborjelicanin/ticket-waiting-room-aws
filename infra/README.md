# infra — Phase 2 Terraform

The plan, with every decision this code follows, is [`docs/phase2-plan.md`](../docs/phase2-plan.md).

## Roots

| Root | Account | State | Applied by |
|---|---|---|---|
| `org/` | management | own bucket in the management account (local until it exists) | the owner, by hand |
| `bootstrap/` | `twr-dev` | local, then migrated to the bucket it creates | the owner, by hand, once |
| `live/foundation/` | `twr-dev` | `foundation.tfstate` | the owner (slices 0–3), then `tf-apply-foundation` from CI |
| `live/session/` | `twr-dev` | `session.tfstate` | the owner or `tf-apply-session`; destroyed after every session |

`modules/` holds the project's own modules (D1). Only the VPC comes from
`terraform-aws-modules`, version-pinned, wrapped by `modules/network`.

## Running a root

```bash
aws sso login
source infra/scripts/tf-env.sh                     # twr-dev (use "default" for org/)
infra/scripts/tf-init.sh infra/live/foundation     # roots with the S3 backend
terraform -chdir=infra/live/foundation plan
```

`tf-env.sh` exports `AWS_PROFILE` and `TF_VAR_account_id`. Every root pins
`allowed_account_ids` to that id, so a shell left on the wrong profile fails at `plan`.
The id is read from `~/.aws/config` and is never written to a file in this repository.

## Conventions

- Terraform version: [`.terraform-version`](../.terraform-version). AWS provider `~> 6.73`.
  Roots commit `.terraform.lock.hcl` for linux and macOS on amd64 and arm64. Modules commit
  none, because the calling root pins the versions.
- Every root has `versions.tf`, `providers.tf`, `variables.tf`, then `main.tf` and
  `outputs.tf` once it creates something. Modules add `README.md` (terraform-docs) and
  `tests/*.tftest.hcl` (`mock_provider`).
- Names are `twr-<env>-<thing>` (D25). Tags come from the provider's `default_tags`
  (`project`, `phase`, `env`, `stack`, `managed-by`) and are not repeated on resources.
- No literal account ids, ARNs with an account id, or bucket names that contain one. They
  come from `data.aws_caller_identity`, resource attributes or `var.account_id`
  (CLAUDE.md, *Secrets and Identifiers*).
- Gates: `terraform fmt` (pre-commit and CI), `validate`, and `tflint` with
  [`.tflint.hcl`](.tflint.hcl) (CI). Locally:

  ```bash
  cd infra && tflint --init && tflint --recursive --config "$PWD/.tflint.hcl"
  ```
