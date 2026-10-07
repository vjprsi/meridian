# Production region pair eu1: home cell aws-euw1-c01 in eu-west-1 (Ireland) with its warm standby aws-euw2-c01 in
# eu-west-2 (London), Aurora Global Database, MSK Replicator, S3 CRR and Route 53 failover for the cell routers (S20).
# Validate without credentials: tofu init -backend=false && tofu validate
terraform {
  required_version = ">= 1.8"
  backend "s3" {
    bucket       = "meridian-tofu-state"
    key          = "regions/prod-eu1.tfstate"
    region       = "eu-west-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  alias  = "primary"
  region = "eu-west-1"
  default_tags {
    tags = { "meridian:owner" = "platform" }
  }
}

provider "aws" {
  alias  = "secondary"
  region = "eu-west-2"
  default_tags {
    tags = { "meridian:owner" = "platform" }
  }
}

variable "dns_zone_id" {
  description = "Route 53 hosted zone of the API domain"
  type        = string
  default     = "Z0000000000000000000"
}

variable "router_lbs" {
  description = "Load balancers of the cell routers (from the meridian-router Helm releases' Service / ingress)"
  type = object({
    primary_dns       = string
    primary_zone_id   = string
    secondary_dns     = string
    secondary_zone_id = string
  })
  default = {
    primary_dns       = "router-euw1.elb.eu-west-1.amazonaws.com"
    primary_zone_id   = "Z2IFOLAFXWLO4F"
    secondary_dns     = "router-euw2.elb.eu-west-2.amazonaws.com"
    secondary_zone_id = "ZD4D7Y8KGAS4G"
  }
}

module "region" {
  source = "../../modules/region"
  providers = {
    aws.primary   = aws.primary
    aws.secondary = aws.secondary
  }

  name        = "eu1"
  environment = "prod"
  primary     = { cell = "euw1c01", vpc_cidr = "10.50.0.0/16", node_min = 6, node_max = 40 }
  secondary   = { cell = "euw2c01", vpc_cidr = "10.60.0.0/16", node_min = 3, node_max = 40 }

  # commits on the primary pause rather than let the standby fall further than this behind (Aurora managed RPO)
  global_db_rpo_seconds = 20

  dns = {
    zone_id                     = var.dns_zone_id
    record_name                 = "api.eu.meridian.example"
    primary_router_lb_dns       = var.router_lbs.primary_dns
    primary_router_lb_zone_id   = var.router_lbs.primary_zone_id
    secondary_router_lb_dns     = var.router_lbs.secondary_dns
    secondary_router_lb_zone_id = var.router_lbs.secondary_zone_id
  }
}

output "helm_values" {
  description = "values-<cell>.yaml inputs for infra/helm/meridian (primary and standby cell)"
  value       = module.region.helm_values
}

output "global_cluster_id" {
  value = module.region.global_cluster_id
}
