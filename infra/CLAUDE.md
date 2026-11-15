# CLAUDE.md — infra/

Rules for working on the Phase 2 Terraform. They add to the root `CLAUDE.md`, which still
applies in full (above all *Secrets and Identifiers*). How to run a root, the conventions and
the gates are in [`README.md`](README.md); the decisions every resource follows are D1–D27 in
[`docs/phase2-plan.md`](../docs/phase2-plan.md).

## Definition of done for any `.tf` change

A change is not finished, and is not reported as finished, until **every** step below has run
and passed, for the whole `infra/` tree rather than only the files touched:

1. `terraform fmt -recursive infra`
2. `terraform -chdir=<root> validate` for each root the change reaches
3. `tflint --recursive --config "$PWD/.tflint.hcl"` from `infra/`
4. `trivy config --exit-code 1 --ignorefile .trivyignore.yaml .` from `infra/` (exact image
   in `.github/workflows/ci.yml`). A finding is fixed in code; it is suppressed only with the
   D-entry it implements
5. **IntelliJ shows no problems.** Run the IDE's inspections (`get_file_problems`, errors and
   warnings) on every `.tf` file under `infra/`, not only the open or edited ones, and fix
   everything until each file reports none. Terraform accepting the code is not enough: the
   owner works in IntelliJ, and red files read as broken code (see the next section)
6. terraform-docs regenerated for every module whose variables or outputs changed
7. `plan` of each affected root, read for what it really does. A refactor must plan **No
   changes**, and nothing may be replaced unless that is the point of the change
8. The slice checkbox in `docs/phase2-plan.md` updated in the same change

`apply` runs only when the owner says "apply" for that root (memory: AWS steps are the
owner's), never as part of "done".

## Writing HCL that IntelliJ's Terraform plugin can resolve

The plugin is stricter than Terraform. Code that validates, plans and applies can still show
red "Unresolved reference" or parse errors. These are the patterns found so far, each with
the form that resolves:

| Shows as an error in IntelliJ | Write instead |
|---|---|
| An operator directly inside a `for` body: `[for q in xs : q.a >= 1 && q.b <= 9]` | Parenthesise the body: `[for q in xs : (q.a >= 1 && q.b <= 9)]` |
| An object attribute named `type` (`consumer = object({ type = string })`) | Another name (`kind`), or a flat field |
| Nested object attributes two levels deep in a variable (`q.consumer.kind`) | Flatten the variable: `q.consumer`, `q.batch_size` |
| `each.value.<attr>` when `for_each` is a map built by `merge()` or a `for` expression | Flat maps of plain values keyed alike (`role_policy`, `role_principal`), read as `local.x[each.key]`; build them with `merge()` of `for` expressions over **typed** sources |
| `local.map[each.key].<attr>`, even on a literal map | A flat map of that attribute: `local.rule_description[each.key]` |
| `<iterator>.value.<attr>` in a `dynamic` block | Iterate a flat map and use `<iterator>.value` / `local.x[<iterator>.key]` |

What does resolve: `each.value.<attr>` over a **literal** map of objects or over a typed
`variable`; any attribute of a resource or data source; `local.x[each.key]` when the map's
values are plain strings or numbers.

When a new pattern turns up, fix it and add a row here, so the table stays the complete list.
Leave a one-line comment at the fix pointing here, because the plain-map form looks odd
without the reason.
