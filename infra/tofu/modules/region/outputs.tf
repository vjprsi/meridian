output "global_cluster_id" {
  description = "Aurora Global Database (switchover / failover target of the runbook)"
  value       = aws_rds_global_cluster.pg.id
}

output "primary_cell" {
  value = {
    cell              = var.primary.cell
    region            = data.aws_region.primary.region
    cluster_name      = module.primary.cluster_name
    postgres_endpoint = module.primary.postgres_endpoint
    kafka_brokers     = module.primary.kafka_bootstrap_brokers
    documents_bucket  = module.primary.documents_bucket
  }
}

output "secondary_cell" {
  value = {
    cell              = var.secondary.cell
    region            = data.aws_region.secondary.region
    cluster_name      = module.secondary.cluster_name
    postgres_endpoint = module.secondary.postgres_endpoint
    kafka_brokers     = module.secondary.kafka_bootstrap_brokers
    documents_bucket  = module.secondary.documents_bucket
  }
}

output "helm_values" {
  description = "Per-cell values for infra/helm/meridian (global.cell, postgres, kafka)"
  value = {
    primary = {
      global = {
        cell     = { id = var.primary.cell, region = data.aws_region.primary.region, role = "primary" }
        postgres = { host = module.primary.postgres_endpoint }
        kafka    = { bootstrapServers = module.primary.kafka_bootstrap_brokers }
      }
      cde = {
        keys = { mode = "aws-kms", kmsKeyId = module.primary.cde_kms_alias, serviceAccountRoleArn = module.primary.cde_vault_role_arn }
      }
    }
    secondary = {
      global = {
        cell     = { id = var.secondary.cell, region = data.aws_region.secondary.region, role = "standby" }
        postgres = { host = module.secondary.postgres_endpoint }
        kafka    = { bootstrapServers = module.secondary.kafka_bootstrap_brokers }
      }
      cde = {
        keys = { mode = "aws-kms", kmsKeyId = module.secondary.cde_kms_alias, serviceAccountRoleArn = module.secondary.cde_vault_role_arn }
      }
    }
  }
}

output "router_record" {
  description = "Failover DNS name of the cell routers"
  value       = var.dns.record_name
}

output "router_health_checks" {
  value = { for k, v in aws_route53_health_check.router : k => v.id }
}
