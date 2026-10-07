# ---------------------------------------------------------------- cardholder data environment (GAP-01 / GAP-02)
# The card vault's own key hierarchy and (optionally) its own database cluster, separate from the cell key and the
# shared Aurora cluster:
#   - aws_kms_key.cde: the master key of the vault's envelope encryption (card-vault-service key-management=aws-kms).
#     Only the vault's service account (IRSA role below) may use it, and only with the vault's encryption context
#     (tenant, purpose, key), so every data key use is attributable in CloudTrail. Automatic yearly rotation; a manual
#     rotation re-points the alias and the vault's re-wrap job moves every data key (RUNBOOKS section 22).
#   - optional dedicated Aurora PostgreSQL cluster for card_vault_db (cde_dedicated_db), encrypted with the CDE key.
#   - with the dedicated cluster, separate database credentials for the CDE services (Helm cde.postgres.*).

# DR: a standby cell must unwrap the data keys replicated with card_vault_db, so with a cross-region pair the primary
# cell's key is a multi-region key (cde_kms_multi_region) and the standby cell holds its replica (cde_kms_replica_of);
# both share key material and the mrk- key id the vault records as master_key_ref.

locals {
  cde_name    = "${local.name}-cde"
  cde_key_arn = one(concat(aws_kms_key.cde[*].arn, aws_kms_replica_key.cde[*].arn))
  cde_key_id  = one(concat(aws_kms_key.cde[*].key_id, aws_kms_replica_key.cde[*].key_id))
}

resource "aws_kms_key" "cde" {
  count                   = var.cde_kms_replica_of == null ? 1 : 0
  description             = "${local.cde_name}: card vault master key (envelope encryption of card data keys)"
  enable_key_rotation     = true
  multi_region            = var.cde_kms_multi_region
  deletion_window_in_days = 30
  tags                    = merge(local.tags, { "meridian:zone" = "cde", "pci:scope" = "cde" })
}

resource "aws_kms_replica_key" "cde" {
  count                   = var.cde_kms_replica_of == null ? 0 : 1
  description             = "${local.cde_name}: replica of the card vault master key (DR standby)"
  primary_key_arn         = var.cde_kms_replica_of
  deletion_window_in_days = 30
  tags                    = merge(local.tags, { "meridian:zone" = "cde", "pci:scope" = "cde" })
}

resource "aws_kms_alias" "cde" {
  name          = "alias/${local.cde_name}"
  target_key_id = local.cde_key_id
}

# IRSA role of card-vault-service: the only principal that may use the CDE key, with the vault's encryption context.
data "aws_iam_policy_document" "cde_vault_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(module.eks.cluster_oidc_issuer_url, "https://", "")}:sub"
      values   = [for ns in var.cde_namespaces : "system:serviceaccount:${ns}:card-vault-service"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(module.eks.cluster_oidc_issuer_url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cde_vault" {
  name               = "${local.cde_name}-card-vault"
  assume_role_policy = data.aws_iam_policy_document.cde_vault_assume.json
  tags               = merge(local.tags, { "meridian:zone" = "cde" })
}

data "aws_iam_policy_document" "cde_vault_kms" {
  statement {
    sid       = "EnvelopeEncryptionWithVaultContext"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt", "kms:Encrypt", "kms:ReEncryptFrom", "kms:ReEncryptTo"]
    resources = [local.cde_key_arn]
    condition {
      test     = "ForAllValues:StringEquals"
      variable = "kms:EncryptionContextKeys"
      values   = ["tenant", "purpose", "key"]
    }
    condition {
      test     = "Null"
      variable = "kms:EncryptionContext:tenant"
      values   = ["false"]
    }
  }
  statement {
    sid       = "ResolveAlias"
    actions   = ["kms:DescribeKey"]
    resources = [local.cde_key_arn]
  }
}

resource "aws_iam_role_policy" "cde_vault_kms" {
  name   = "cde-kms"
  role   = aws_iam_role.cde_vault.id
  policy = data.aws_iam_policy_document.cde_vault_kms.json
}

# ---------------------------------------------------------------- optional dedicated CDE database cluster

resource "aws_security_group" "cde_db" {
  count       = var.cde_dedicated_db ? 1 : 0
  name        = "${local.cde_name}-db"
  description = "CDE Aurora PostgreSQL (card_vault_db): only from EKS nodes"
  vpc_id      = module.vpc.vpc_id
  tags        = merge(local.tags, { "meridian:zone" = "cde" })
}

resource "aws_vpc_security_group_ingress_rule" "cde_db_from_eks" {
  count                        = var.cde_dedicated_db ? 1 : 0
  security_group_id            = aws_security_group.cde_db[0].id
  referenced_security_group_id = module.eks.node_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_rds_cluster" "cde" {
  count                               = var.cde_dedicated_db ? 1 : 0
  cluster_identifier                  = local.cde_name
  engine                              = "aurora-postgresql"
  engine_version                      = var.postgres_version
  database_name                       = "meridian"
  master_username                     = "meridian_admin"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = local.cde_key_id
  db_subnet_group_name                = module.vpc.database_subnet_group_name
  vpc_security_group_ids              = [aws_security_group.cde_db[0].id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.pg.name
  storage_encrypted                   = true
  kms_key_id                          = local.cde_key_arn
  backup_retention_period             = var.environment == "prod" ? 35 : 7
  preferred_backup_window             = "02:00-03:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.environment == "prod"
  skip_final_snapshot                 = var.environment != "prod"
  final_snapshot_identifier           = var.environment == "prod" ? "${local.cde_name}-final" : null
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]
  serverlessv2_scaling_configuration {
    min_capacity = var.cde_postgres_min_acu
    max_capacity = var.cde_postgres_max_acu
  }
  tags = merge(local.tags, { "meridian:zone" = "cde", "pci:scope" = "cde" })
}

resource "aws_rds_cluster_instance" "cde" {
  count                           = var.cde_dedicated_db ? (var.environment == "prod" ? 2 : 1) : 0
  identifier                      = "${local.cde_name}-${count.index}"
  cluster_identifier              = aws_rds_cluster.cde[0].id
  instance_class                  = "db.serverless"
  engine                          = aws_rds_cluster.cde[0].engine
  engine_version                  = aws_rds_cluster.cde[0].engine_version
  performance_insights_enabled    = true
  performance_insights_kms_key_id = local.cde_key_arn
  tags                            = merge(local.tags, { "meridian:zone" = "cde" })
}

# ---------------------------------------------------------------- CDE database credentials (Helm cde.postgres.*)
# Roles of the dedicated cluster (same role names as the shared cluster, different cluster, different passwords). With
# the shared cluster, point Helm cde.postgres.appSecret / migrationSecret at meridian-db-app / meridian-db-owner.

resource "random_password" "cde_db_app" {
  count   = var.cde_dedicated_db ? 1 : 0
  length  = 32
  special = false
}

resource "random_password" "cde_db_owner" {
  count   = var.cde_dedicated_db ? 1 : 0
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "cde_db_app" {
  count      = var.cde_dedicated_db ? 1 : 0
  name       = "${local.cde_name}/meridian-cde-db-app"
  kms_key_id = local.cde_key_arn
  tags       = merge(local.tags, { "meridian:zone" = "cde" })
}

resource "aws_secretsmanager_secret_version" "cde_db_app" {
  count         = var.cde_dedicated_db ? 1 : 0
  secret_id     = aws_secretsmanager_secret.cde_db_app[0].id
  secret_string = jsonencode({ username = "meridian_app", password = random_password.cde_db_app[0].result })
}

resource "aws_secretsmanager_secret" "cde_db_owner" {
  count      = var.cde_dedicated_db ? 1 : 0
  name       = "${local.cde_name}/meridian-cde-db-owner"
  kms_key_id = local.cde_key_arn
  tags       = merge(local.tags, { "meridian:zone" = "cde" })
}

resource "aws_secretsmanager_secret_version" "cde_db_owner" {
  count         = var.cde_dedicated_db ? 1 : 0
  secret_id     = aws_secretsmanager_secret.cde_db_owner[0].id
  secret_string = jsonencode({ username = "meridian", password = random_password.cde_db_owner[0].result })
}
