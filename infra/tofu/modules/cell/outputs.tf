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

output "kms_key_arn" {
  value = aws_kms_key.cell.arn
}

output "secret_arns" {
  description = "Secrets Manager entries to sync into the namespace (External Secrets)"
  value = {
    meridian-db-app   = aws_secretsmanager_secret.db_app.arn
    meridian-db-owner = aws_secretsmanager_secret.db_owner.arn
  }
}
