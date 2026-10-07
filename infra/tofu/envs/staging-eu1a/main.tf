# Staging cell eu1a. State in S3 with locking; apply with:
#   tofu init && tofu plan -out plan && tofu apply plan
terraform {
  required_version = ">= 1.8"
  backend "s3" {
    bucket       = "meridian-tofu-state"
    key          = "cells/staging-eu1a.tfstate"
    region       = "eu-west-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "eu-west-1"
  default_tags {
    tags = { "meridian:owner" = "platform" }
  }
}

module "cell" {
  source      = "../../modules/cell"
  cell        = "eu1a"
  environment = "staging"
  vpc_cidr    = "10.40.0.0/16"
  node_min    = 3
  node_max    = 12
}

output "helm_values" {
  description = "Paste into values-staging.yaml"
  value = {
    global = {
      postgres = { host = module.cell.postgres_endpoint }
      kafka    = { bootstrapServers = module.cell.kafka_bootstrap_brokers }
    }
    cde = {
      keys = { mode = "aws-kms", kmsKeyId = module.cell.cde_kms_alias, serviceAccountRoleArn = module.cell.cde_vault_role_arn }
    }
  }
}
