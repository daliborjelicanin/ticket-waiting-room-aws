# infra/bootstrap — twr-dev, applied once

What CI needs before it can apply anything (D14 in `docs/phase2-plan.md`):

| File | What |
|---|---|
| `state.tf` | state bucket for bootstrap, foundation and session: versioned, SSE-S3, TLS-only, readable only by the Terraform roles and the owner's admin role (D11) |
| `oidc.tf` | GitHub OIDC provider; trust per role pinned to the workflow file and ref |
| `roles.tf` | `twr-dev-ci-images`, `twr-dev-tf-plan`, `twr-dev-tf-apply-foundation`, `twr-dev-tf-apply-session` |
| `boundary.tf` | `twr-dev-boundary`, required on every role foundation creates |

## Prerequisites (done 2026-11-13, by `gh api`)

- Ruleset on `main` (D14); default `GITHUB_TOKEN` read-only.
- OIDC subject customised to `repo`, `context`, `job_workflow_ref`
  (`PUT /repos/{repo}/actions/oidc/customization/sub`). GitHub returns the **immutable**
  prefix with numeric owner and repository ids; it is `var.github_oidc_sub_prefix`.
- Environments `foundation` (required reviewer: the owner) and `session`, both limited to
  protected branches.

## First apply

```bash
source infra/scripts/tf-env.sh
terraform -chdir=infra/bootstrap init          # local state: backend.tf not yet present
terraform -chdir=infra/bootstrap apply
```

Then add `backend.tf` (key `bootstrap.tfstate`, same shape as `infra/live/foundation`), run
`infra/scripts/tf-init.sh infra/bootstrap -migrate-state`, delete the local
`terraform.tfstate*`, and check that a second `plan` shows no changes.
