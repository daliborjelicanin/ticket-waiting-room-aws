terraform {
  # Partial configuration: the bucket name carries the management account id, so it is passed
  # at init time (README.md), never committed.
  backend "s3" {
    key          = "org.tfstate"
    region       = "eu-central-1"
    use_lockfile = true
    encrypt      = true
  }
}
