# Shared tflint configuration for every root and module under infra/ (D2).
# CI and local runs:  cd infra && tflint --init && tflint --recursive --config "$PWD/.tflint.hcl"

config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

# Every variable and output documents itself: terraform-docs builds module READMEs from these.
rule "terraform_documented_variables" {
  enabled = true
}

rule "terraform_documented_outputs" {
  enabled = true
}

rule "terraform_naming_convention" {
  enabled = true
}

rule "terraform_standard_module_structure" {
  enabled = true
}
