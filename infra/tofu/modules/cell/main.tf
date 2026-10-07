# One Meridian cell on AWS: an isolated VPC across three AZs, EKS for the services, Aurora PostgreSQL for the
# per-service databases, MSK (Kafka) for events, a customer-managed KMS key encrypting everything at rest, an S3
# bucket for documents/archives/exports, and Secrets Manager entries consumed by the Helm chart via External Secrets.
# A region hosts one or more cells; the cell router (S20) maps tenants to cells. With db_role primary / secondary the
# Aurora cluster joins an Aurora Global Database (modules/region pairs two cells across regions for DR).

locals {
  name          = "meridian-${var.cell}"
  db_standalone = var.db_role == "standalone"
  db_secondary  = var.db_role == "secondary"
  global_db_rpo = var.global_db_rpo_seconds != null && !local.db_secondary
  azs           = slice(data.aws_availability_zones.available.names, 0, 3)
  tags = merge(var.tags, {
    "meridian:cell"        = var.cell
    "meridian:environment" = var.environment
    "meridian:managed-by"  = "opentofu"
  })
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

# ---------------------------------------------------------------- encryption

resource "aws_kms_key" "cell" {
  description             = "${local.name}: encryption at rest (EKS secrets, Aurora, MSK, S3, Secrets Manager)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

resource "aws_kms_alias" "cell" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.cell.key_id
}

# ---------------------------------------------------------------- network

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name             = local.name
  cidr             = var.vpc_cidr
  azs              = local.azs
  private_subnets  = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i)]
  public_subnets   = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, 48 + i)]
  database_subnets = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, 64 + i)]

  enable_nat_gateway     = true
  one_nat_gateway_per_az = var.environment == "prod"
  single_nat_gateway     = var.environment != "prod"

  enable_flow_log                      = true
  create_flow_log_cloudwatch_log_group = true
  create_flow_log_cloudwatch_iam_role  = true
  flow_log_max_aggregation_interval    = 60

  create_database_subnet_group = true

  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }
  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  tags                = local.tags
}

# ---------------------------------------------------------------- kubernetes

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = local.name
  kubernetes_version = var.kubernetes_version
  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.private_subnets

  endpoint_public_access  = var.eks_public_endpoint
  endpoint_private_access = true
  enable_irsa             = true

  encryption_config = {
    resources        = ["secrets"]
    provider_key_arn = aws_kms_key.cell.arn
  }
  enabled_log_types = ["api", "audit", "authenticator"]

  eks_managed_node_groups = {
    services = {
      instance_types = var.node_instance_types
      min_size       = var.node_min
      max_size       = var.node_max
      desired_size   = var.node_min
      capacity_type  = "ON_DEMAND"
      labels         = { "meridian.io/pool" = "services" }
    }
  }

  tags = local.tags
}

# ---------------------------------------------------------------- postgres

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "Aurora PostgreSQL: only from EKS nodes"
  vpc_id      = module.vpc.vpc_id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "db_from_eks" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = module.eks.node_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_rds_cluster_parameter_group" "pg" {
  name   = "${local.name}-pg"
  family = "aurora-postgresql17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
  # Aurora Global Database managed RPO: commits on the primary pause when no secondary is within this lag (seconds)
  dynamic "parameter" {
    for_each = local.global_db_rpo ? [var.global_db_rpo_seconds] : []
    content {
      name  = "rds.global_db_rpo"
      value = tostring(parameter.value)
    }
  }
  tags = local.tags
}

# Master password of a global-database member (managed master passwords are not available on global clusters).
resource "random_password" "db_master" {
  count   = var.db_role == "primary" ? 1 : 0
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "db_master" {
  count      = var.db_role == "primary" ? 1 : 0
  name       = "${local.name}/meridian-db-master"
  kms_key_id = aws_kms_key.cell.arn
  tags       = local.tags

  # DR: the standby region needs the same credentials (the database users replicate with the global cluster)
  dynamic "replica" {
    for_each = var.secret_replicas
    content {
      region     = replica.value.region
      kms_key_id = replica.value.kms_key_id
    }
  }
}

resource "aws_secretsmanager_secret_version" "db_master" {
  count         = var.db_role == "primary" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.db_master[0].id
  secret_string = jsonencode({ username = "meridian_admin", password = random_password.db_master[0].result })
}

resource "aws_rds_cluster" "pg" {
  cluster_identifier        = local.name
  engine                    = "aurora-postgresql"
  engine_version            = var.postgres_version
  global_cluster_identifier = var.global_cluster_identifier
  # a secondary inherits database, users and passwords from the global cluster's primary
  database_name                       = local.db_secondary ? null : "meridian"
  master_username                     = local.db_secondary ? null : "meridian_admin"
  manage_master_user_password         = local.db_standalone ? true : null
  master_user_secret_kms_key_id       = local.db_standalone ? aws_kms_key.cell.key_id : null
  master_password                     = var.db_role == "primary" ? random_password.db_master[0].result : null
  db_subnet_group_name                = module.vpc.database_subnet_group_name
  vpc_security_group_ids              = [aws_security_group.db.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.pg.name
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.cell.arn
  backup_retention_period             = var.environment == "prod" ? 35 : 7
  preferred_backup_window             = "02:00-03:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.environment == "prod"
  skip_final_snapshot                 = var.environment != "prod"
  final_snapshot_identifier           = var.environment == "prod" ? "${local.name}-final" : null
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]
  serverlessv2_scaling_configuration {
    min_capacity = var.postgres_min_acu
    max_capacity = var.postgres_max_acu
  }
  tags = local.tags

  lifecycle {
    precondition {
      condition     = local.db_standalone || var.global_cluster_identifier != null
      error_message = "db_role primary / secondary needs global_cluster_identifier"
    }
    # managed by the global cluster (switchover / failover swap the primary and secondary roles)
    ignore_changes = [global_cluster_identifier, replication_source_identifier]
  }
}

resource "aws_rds_cluster_instance" "pg" {
  # prod: writer + 2 readers across three AZs (synchronous six-way storage replication, quorum 4/6 per write)
  count                           = var.environment == "prod" ? 3 : 1
  identifier                      = "${local.name}-${count.index}"
  cluster_identifier              = aws_rds_cluster.pg.id
  instance_class                  = "db.serverless"
  engine                          = aws_rds_cluster.pg.engine
  engine_version                  = aws_rds_cluster.pg.engine_version
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.cell.arn
  tags                            = local.tags
}

# ---------------------------------------------------------------- kafka

resource "aws_security_group" "kafka" {
  name        = "${local.name}-kafka"
  description = "MSK: only from EKS nodes"
  vpc_id      = module.vpc.vpc_id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "kafka_from_eks" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = module.eks.node_security_group_id
  from_port                    = 9094
  to_port                      = 9098
  ip_protocol                  = "tcp"
}

# MSK Replicator (DR, modules/region) attaches ENIs with this security group: brokers accept it, and it reaches them.
resource "aws_vpc_security_group_ingress_rule" "kafka_self" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  from_port                    = 9092
  to_port                      = 9098
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "kafka_self" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.kafka.id
  from_port                    = 9092
  to_port                      = 9098
  ip_protocol                  = "tcp"
}

resource "aws_msk_cluster" "events" {
  cluster_name           = local.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3
  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.kafka.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.kafka_volume_gb
      }
    }
    # multi-VPC private connectivity (IAM): needed when this cluster is the source of a cross-region MSK Replicator
    dynamic "connectivity_info" {
      for_each = var.kafka_multi_vpc ? [1] : []
      content {
        vpc_connectivity {
          client_authentication {
            sasl {
              iam = true
            }
          }
        }
      }
    }
  }
  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.cell.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
  client_authentication {
    sasl {
      iam = true
    }
  }
  tags = local.tags
}

# ---------------------------------------------------------------- object storage

resource "aws_s3_bucket" "documents" {
  bucket = "${local.name}-documents-${data.aws_caller_identity.current.account_id}"
  tags   = local.tags
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.cell.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_object_lock_configuration" "documents" {
  count  = var.object_lock_days > 0 ? 1 : 0
  bucket = aws_s3_bucket.documents.id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = var.object_lock_days
    }
  }
}

# ---------------------------------------------------------------- audit WORM storage (GAP-07)
# Sealed daily audit batches (events + manifest) written by audit-service with x-amz-object-lock-mode COMPLIANCE:
# no principal, the root account included, can delete or overwrite a locked version before its retain-until date.
# Object Lock can only be enabled when the bucket is created.

resource "aws_s3_bucket" "audit" {
  bucket              = "${local.name}-audit-${data.aws_caller_identity.current.account_id}"
  object_lock_enabled = true
  tags                = local.tags
}

resource "aws_s3_bucket_versioning" "audit" {
  bucket = aws_s3_bucket.audit.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "audit" {
  bucket = aws_s3_bucket.audit.id
  rule {
    default_retention {
      mode  = "COMPLIANCE"
      years = var.audit_retention_years
    }
  }
  depends_on = [aws_s3_bucket_versioning.audit]
}

resource "aws_s3_bucket_server_side_encryption_configuration" "audit" {
  bucket = aws_s3_bucket.audit.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.cell.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "audit" {
  bucket                  = aws_s3_bucket.audit.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "audit" {
  bucket = aws_s3_bucket.audit.id
  rule {
    id     = "seals-to-cold-storage"
    status = "Enabled"
    filter {
      prefix = "audit/"
    }
    transition {
      days          = var.audit_cold_after_days
      storage_class = "GLACIER_IR"
    }
  }
}

# ---------------------------------------------------------------- secrets for the Helm chart

resource "random_password" "db_app" {
  length  = 32
  special = false
}

resource "random_password" "db_owner" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db_app" {
  name       = "${local.name}/meridian-db-app"
  kms_key_id = aws_kms_key.cell.arn
  tags       = local.tags

  # DR: the standby region needs the same credentials (the database users replicate with the global cluster)
  dynamic "replica" {
    for_each = var.secret_replicas
    content {
      region     = replica.value.region
      kms_key_id = replica.value.kms_key_id
    }
  }
}

resource "aws_secretsmanager_secret_version" "db_app" {
  secret_id     = aws_secretsmanager_secret.db_app.id
  secret_string = jsonencode({ username = "meridian_app", password = random_password.db_app.result })
}

resource "aws_secretsmanager_secret" "db_owner" {
  name       = "${local.name}/meridian-db-owner"
  kms_key_id = aws_kms_key.cell.arn
  tags       = local.tags

  # DR: the standby region needs the same credentials (the database users replicate with the global cluster)
  dynamic "replica" {
    for_each = var.secret_replicas
    content {
      region     = replica.value.region
      kms_key_id = replica.value.kms_key_id
    }
  }
}

resource "aws_secretsmanager_secret_version" "db_owner" {
  secret_id     = aws_secretsmanager_secret.db_owner.id
  secret_string = jsonencode({ username = "meridian", password = random_password.db_owner.result })
}
