# Architecture decision records

MADR records for the Phase 2 decisions that are expensive to reverse or likely to be
questioned later (D26 in [`../phase2-plan.md`](../phase2-plan.md)). Every other decision stays
in the plan, which is the phase's decision log. Each record is written when its slice
**builds** the decision, so it describes what was built, not what was intended.

Copy [`0000-template.md`](0000-template.md) to `NNNN-short-title.md`.

| ADR | Decision | Plan | Slice | Status |
|---|---|---|---|---|
| 0001 | Hybrid modules | D1 | 1 | not yet written |
| 0002 | Two stacks, foundation and session | retro §1, D2 | 1 | not yet written |
| 0003 | Private ALB behind REST + VPC Link v2 | D5 | 2 | not yet written |
| 0004 | One database role per service, migrations as a deploy step | D9, D10 | 3 | not yet written |
| 0005 | Four CI roles, with IAM writes only behind approval | D14 | 4 | not yet written |
| 0006 | Terraform deploys the services, in stages | D23 | 3–4 | not yet written |
| 0007 | WebSocket connect tickets | D18 | 6 | not yet written |
| 0008 | Phase 3 seams | D21 | 7 | not yet written |
