# One Meridian cell on AWS: an isolated VPC across three AZs, EKS for the services, Aurora PostgreSQL for the
# per-service databases, MSK (Kafka) for events, a customer-managed KMS key encrypting everything at rest, an S3
# bucket for documents/archives/exports, and Secrets Manager entries consumed by the Helm chart via External Secrets.
# A region hosts one or more cells; the cell router (S20) maps tenants to cells.

locals {
  name = "meridian-${var.cell}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 3)
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
  tags = local.tags
}

resource "aws_rds_cluster" "pg" {
  cluster_identifier                  = local.name
  engine                              = "aurora-postgresql"
  engine_version                      = var.postgres_version
  database_name                       = "meridian"
  master_username                     = "meridian_admin"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = aws_kms_key.cell.key_id
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
}

resource "aws_rds_cluster_instance" "pg" {
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
}

resource "aws_secretsmanager_secret_version" "db_app" {
  secret_id     = aws_secretsmanager_secret.db_app.id
  secret_string = jsonencode({ username = "meridian_app", password = random_password.db_app.result })
}

resource "aws_secretsmanager_secret" "db_owner" {
  name       = "${local.name}/meridian-db-owner"
  kms_key_id = aws_kms_key.cell.arn
  tags       = local.tags
}

resource "aws_secretsmanager_secret_version" "db_owner" {
  secret_id     = aws_secretsmanager_secret.db_owner.id
  secret_string = jsonencode({ username = "meridian", password = random_password.db_owner.result })
}
