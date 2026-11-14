terraform {
  # Partial configuration: the bucket (created by this root) carries the account id, so
  # infra/scripts/tf-init.sh passes it at init time.
  backend "s3" {
    key          = "bootstrap.tfstate"
    region       = "eu-central-1"
    use_lockfile = true
    encrypt      = true
  }
}
