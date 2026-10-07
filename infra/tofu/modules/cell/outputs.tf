output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "postgres_endpoint" {
  description = "global.postgres.host for the Helm chart"
  value       = aws_rds_cluster.pg.endpoint
}

output "kafka_bootstrap_brokers" {
  description = "global.kafka.bootstrapServers for the Helm chart (SASL/IAM over TLS)"
  value       = aws_msk_cluster.events.bootstrap_brokers_sasl_iam
}

output "documents_bucket" {
  value = aws_s3_bucket.documents.bucket
}

output "audit_bucket" {
  description = "Object Lock bucket of sealed audit batches: MERIDIAN_AUDIT_WORM_BUCKET of audit-service"
  value       = aws_s3_bucket.audit.bucket
}

output "audit_bucket_arn" {
  value = aws_s3_bucket.audit.arn
}

output "documents_bucket_arn" {
  value = aws_s3_bucket.documents.arn
}

output "postgres_cluster_arn" {
  value = aws_rds_cluster.pg.arn
}

output "postgres_reader_endpoint" {
  value = aws_rds_cluster.pg.reader_endpoint
}

output "kafka_cluster_arn" {
  value = aws_msk_cluster.events.arn
}

output "kafka_security_group_id" {
  value = aws_security_group.kafka.id
}

output "private_subnet_ids" {
  value = module.vpc.private_subnets
}

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "kms_key_arn" {
  value = aws_kms_key.cell.arn
}

output "secret_arns" {
  description = "Secrets Manager entries to sync into the namespace (External Secrets)"
  value = merge({
    meridian-db-app   = aws_secretsmanager_secret.db_app.arn
    meridian-db-owner = aws_secretsmanager_secret.db_owner.arn
    }, var.cde_dedicated_db ? {
    meridian-cde-db-app   = aws_secretsmanager_secret.cde_db_app[0].arn
    meridian-cde-db-owner = aws_secretsmanager_secret.cde_db_owner[0].arn
  } : {})
}

output "cde_kms_key_arn" {
  description = "Helm cde.keys.kmsKeyId (card-vault-service key-management=aws-kms); the alias works too"
  value       = local.cde_key_arn
}

output "cde_kms_alias" {
  value = aws_kms_alias.cde.name
}

output "cde_vault_role_arn" {
  description = "Helm cde.keys.serviceAccountRoleArn (IRSA role of card-vault-service)"
  value       = aws_iam_role.cde_vault.arn
}

output "cde_db_endpoint" {
  description = "Helm cde.postgres.host (null: the CDE shares the cell cluster)"
  value       = var.cde_dedicated_db ? aws_rds_cluster.cde[0].endpoint : null
}
