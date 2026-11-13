# infra/org — management account

What the management account owns for the organization (D12 in `docs/phase2-plan.md`):

| File | What |
|---|---|
| `sso.tf` | permission sets `AdministratorAccess` (adopted from the console, `prevent_destroy`) and `twr-readonly` (read + ECS Exec), assigned to the owner |
| `scp.tf` | SCPs on OU `workloads`: allowed regions; no leaving the org; no member root; no disabling CloudTrail/GuardDuty except break-glass |
| `budget.tf` | $1 budget on the management account's own spend |
| `state.tf` | this root's state bucket |

Applied by hand only, with the admin permission set. No CI role reaches this account.

## Running it

```bash
source infra/scripts/tf-env.sh default
terraform -chdir=infra/org init -backend-config="bucket=twr-org-tfstate-$TF_VAR_account_id"
terraform -chdir=infra/org plan
```

## How it was first applied (2026-11-13, kept for a rebuild)

1. The SCP policy type was enabled on the root by CLI. Terraform could only do it by adopting
   the whole organization (`aws_organizations_organization`), whose destroy deletes the org:
   `aws organizations enable-policy-type --root-id <root-id> --policy-type SERVICE_CONTROL_POLICY`.
2. Applied with local state and a one-time `imports.tf` that adopted the console-made
   `AdministratorAccess` permission set, its policy attachment and its two assignments.
3. `backend.tf` added, state moved with `init -migrate-state`, local state files deleted,
   `imports.tf` deleted. A second `plan` showed no changes.

## Checks (all passed 2026-11-13)

- A call from `twr-dev` outside the allowed regions is denied by the SCP:
  `aws ec2 describe-vpcs --region us-west-2 --profile twr-dev`.
- A centralised root session into `twr-dev` still works with the root-deny SCP attached
  (the statement exempts `aws:AssumedRoot`; without that it was denied):
  `aws sts assume-root --target-principal <twr-dev-id> --task-policy-arn arn=arn:aws:iam::aws:policy/root-task/IAMAuditRootUserCredentials`,
  then `aws iam get-login-profile` with those credentials → `NoSuchEntity` (no root password).
- `--profile twr-dev-readonly` reads and is refused a write.
