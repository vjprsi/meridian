# A Meridian region pair (S20): a primary cell and its warm-standby cell in a second region, wired for disaster
# recovery.
#
#   - PostgreSQL: one Aurora Global Database. Inside each region the cluster is multi-AZ (storage replicated six ways
#     across three AZs, a write is durable at a 4/6 quorum: RPO 0 for an AZ loss). Across regions, storage-level
#     replication (typically < 1 s); a planned switchover (managed, RPO 0) or an unplanned failover swaps the roles.
#     Optional managed RPO (rds.global_db_rpo) bounds the lag the primary accepts.
#   - Kafka: MSK Replicator copies the event topics and consumer group offsets to the standby region (identical topic
#     names); consumers are idempotent (ce_id) and the outbox replays what was not yet relayed.
#   - Documents: S3 Cross-Region Replication both ways (Replication Time Control, KMS re-encryption per region).
#   - Secrets: database credentials replicated to the standby region (same users as the replicated database).
#   - DNS: Route 53 failover records for the regional cell routers, with HTTPS health checks on /healthz.
#
# The tenant registry decides where tenants live (home cell, standby cell); the cell router fails tenants over when an
# operator marks their home cell OFFLINE. See docs/MULTI_REGION.md for the runbook and RTO budget.

locals {
  name = "meridian-${var.name}"
  prod = var.environment == "prod"
  tags = merge(var.tags, {
    "meridian:region-pair" = var.name
    "meridian:environment" = var.environment
    "meridian:managed-by"  = "opentofu"
  })
}

data "aws_region" "primary" {
  provider = aws.primary
}

data "aws_region" "secondary" {
  provider = aws.secondary
}

# ---------------------------------------------------------------- database: Aurora Global Database

resource "aws_rds_global_cluster" "pg" {
  provider                  = aws.primary
  global_cluster_identifier = local.name
  engine                    = "aurora-postgresql"
  engine_version            = var.postgres_version
  storage_encrypted         = true
  deletion_protection       = local.prod
}

# Key for the database secrets replicated into the standby region (independent of the standby cell's own key).
resource "aws_kms_key" "dr_secrets" {
  provider                = aws.secondary
  description             = "${local.name}: replicas of the primary cell's database secrets"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

module "primary" {
  source    = "../cell"
  providers = { aws = aws.primary }

  cell                      = var.primary.cell
  environment               = var.environment
  vpc_cidr                  = var.primary.vpc_cidr
  node_min                  = var.primary.node_min
  node_max                  = var.primary.node_max
  postgres_version          = var.postgres_version
  db_role                   = "primary"
  global_cluster_identifier = aws_rds_global_cluster.pg.id
  global_db_rpo_seconds     = var.global_db_rpo_seconds
  kafka_multi_vpc           = var.replicate_kafka
  secret_replicas           = [{ region = data.aws_region.secondary.region, kms_key_id = aws_kms_key.dr_secrets.arn }]
  tags                      = local.tags
}

module "secondary" {
  source    = "../cell"
  providers = { aws = aws.secondary }

  cell                      = var.secondary.cell
  environment               = var.environment
  vpc_cidr                  = var.secondary.vpc_cidr
  node_min                  = var.secondary.node_min
  node_max                  = var.secondary.node_max
  postgres_version          = var.postgres_version
  db_role                   = "secondary"
  global_cluster_identifier = aws_rds_global_cluster.pg.id
  kafka_multi_vpc           = false
  tags                      = local.tags

  # the secondary cluster can only join once the primary cluster is in the global database
  depends_on = [module.primary]
}

# Replication lag of the standby region: the input of the game-day RPO check and of the unplanned-failover decision.
resource "aws_cloudwatch_metric_alarm" "global_db_lag" {
  provider            = aws.secondary
  alarm_name          = "${local.name}-aurora-global-replication-lag"
  alarm_description   = "Aurora Global Database replication lag to ${data.aws_region.secondary.region} above 1s"
  namespace           = "AWS/RDS"
  metric_name         = "AuroraGlobalDBReplicationLag"
  dimensions          = { DBClusterIdentifier = "meridian-${var.secondary.cell}" }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  tags                = local.tags
  depends_on          = [module.secondary]
}

# ---------------------------------------------------------------- documents: S3 Cross-Region Replication (both ways)

data "aws_iam_policy_document" "s3_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "s3_replication" {
  provider           = aws.primary
  name               = "${local.name}-s3-replication"
  assume_role_policy = data.aws_iam_policy_document.s3_assume.json
  tags               = local.tags
}

data "aws_iam_policy_document" "s3_replication" {
  statement {
    actions   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
    resources = [module.primary.documents_bucket_arn, module.secondary.documents_bucket_arn]
  }
  statement {
    actions = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging",
    "s3:GetObjectRetention", "s3:GetObjectLegalHold"]
    resources = ["${module.primary.documents_bucket_arn}/*", "${module.secondary.documents_bucket_arn}/*"]
  }
  statement {
    actions   = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
    resources = ["${module.primary.documents_bucket_arn}/*", "${module.secondary.documents_bucket_arn}/*"]
  }
  statement {
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [module.primary.kms_key_arn, module.secondary.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "s3_replication" {
  provider = aws.primary
  name     = "replicate-documents"
  role     = aws_iam_role.s3_replication.id
  policy   = data.aws_iam_policy_document.s3_replication.json
}

resource "aws_s3_bucket_replication_configuration" "to_secondary" {
  provider = aws.primary
  role     = aws_iam_role.s3_replication.arn
  bucket   = module.primary.documents_bucket

  rule {
    id     = "dr-to-${data.aws_region.secondary.region}"
    status = "Enabled"
    filter {}
    delete_marker_replication {
      status = "Enabled"
    }
    source_selection_criteria {
      sse_kms_encrypted_objects {
        status = "Enabled"
      }
      replica_modifications {
        status = "Enabled"
      }
    }
    destination {
      bucket        = module.secondary.documents_bucket_arn
      storage_class = "STANDARD"
      encryption_configuration {
        replica_kms_key_id = module.secondary.kms_key_arn
      }
      replication_time {
        status = "Enabled"
        time {
          minutes = var.documents_replication_minutes
        }
      }
      metrics {
        status = "Enabled"
        event_threshold {
          minutes = var.documents_replication_minutes
        }
      }
    }
  }

  depends_on = [module.primary, module.secondary]
}

# After a failover the standby region writes documents; they flow back for the failback.
resource "aws_s3_bucket_replication_configuration" "to_primary" {
  provider = aws.secondary
  role     = aws_iam_role.s3_replication.arn
  bucket   = module.secondary.documents_bucket

  rule {
    id     = "failback-to-${data.aws_region.primary.region}"
    status = "Enabled"
    filter {}
    delete_marker_replication {
      status = "Enabled"
    }
    source_selection_criteria {
      sse_kms_encrypted_objects {
        status = "Enabled"
      }
      replica_modifications {
        status = "Enabled"
      }
    }
    destination {
      bucket        = module.primary.documents_bucket_arn
      storage_class = "STANDARD"
      encryption_configuration {
        replica_kms_key_id = module.primary.kms_key_arn
      }
      replication_time {
        status = "Enabled"
        time {
          minutes = var.documents_replication_minutes
        }
      }
      metrics {
        status = "Enabled"
        event_threshold {
          minutes = var.documents_replication_minutes
        }
      }
    }
  }

  depends_on = [aws_s3_bucket_replication_configuration.to_secondary]
}

# ---------------------------------------------------------------- events: MSK Replicator (primary -> standby)

data "aws_iam_policy_document" "msk_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["kafka.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "msk_replicator" {
  count              = var.replicate_kafka ? 1 : 0
  provider           = aws.secondary
  name               = "${local.name}-msk-replicator"
  assume_role_policy = data.aws_iam_policy_document.msk_assume.json
  tags               = local.tags
}

data "aws_iam_policy_document" "msk_replicator" {
  statement {
    actions = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:AlterCluster",
      "kafka-cluster:DescribeTopic", "kafka-cluster:CreateTopic", "kafka-cluster:AlterTopic",
      "kafka-cluster:DescribeTopicDynamicConfiguration", "kafka-cluster:AlterTopicDynamicConfiguration",
      "kafka-cluster:ReadData", "kafka-cluster:WriteData", "kafka-cluster:WriteDataIdempotently",
    "kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
    resources = [
      module.primary.kafka_cluster_arn, module.secondary.kafka_cluster_arn,
      "${replace(module.primary.kafka_cluster_arn, ":cluster/", ":topic/")}/*",
      "${replace(module.secondary.kafka_cluster_arn, ":cluster/", ":topic/")}/*",
      "${replace(module.primary.kafka_cluster_arn, ":cluster/", ":group/")}/*",
      "${replace(module.secondary.kafka_cluster_arn, ":cluster/", ":group/")}/*",
    ]
  }
}

resource "aws_iam_role_policy" "msk_replicator" {
  count    = var.replicate_kafka ? 1 : 0
  provider = aws.secondary
  name     = "replicate-events"
  role     = aws_iam_role.msk_replicator[0].id
  policy   = data.aws_iam_policy_document.msk_replicator.json
}

# The replicator connects to the source cluster through MSK multi-VPC connectivity: allow the service.
resource "aws_msk_cluster_policy" "primary" {
  count       = var.replicate_kafka ? 1 : 0
  provider    = aws.primary
  cluster_arn = module.primary.kafka_cluster_arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "kafka.amazonaws.com" }
      Action    = ["kafka:CreateVpcConnection", "kafka:GetBootstrapBrokers", "kafka:DescribeClusterV2"]
      Resource  = module.primary.kafka_cluster_arn
    }]
  })
}

resource "aws_msk_replicator" "events" {
  count                      = var.replicate_kafka ? 1 : 0
  provider                   = aws.secondary
  replicator_name            = "${local.name}-events"
  description                = "Meridian events ${data.aws_region.primary.region} -> ${data.aws_region.secondary.region}"
  service_execution_role_arn = aws_iam_role.msk_replicator[0].arn

  kafka_cluster {
    amazon_msk_cluster {
      msk_cluster_arn = module.primary.kafka_cluster_arn
    }
    vpc_config {
      subnet_ids          = module.primary.private_subnet_ids
      security_groups_ids = [module.primary.kafka_security_group_id]
    }
  }

  kafka_cluster {
    amazon_msk_cluster {
      msk_cluster_arn = module.secondary.kafka_cluster_arn
    }
    vpc_config {
      subnet_ids          = module.secondary.private_subnet_ids
      security_groups_ids = [module.secondary.kafka_security_group_id]
    }
  }

  replication_info_list {
    source_kafka_cluster_arn = module.primary.kafka_cluster_arn
    target_kafka_cluster_arn = module.secondary.kafka_cluster_arn
    target_compression_type  = "NONE"

    topic_replication {
      topics_to_replicate                  = ["meridian\\..*"]
      copy_topic_configurations            = true
      copy_access_control_lists_for_topics = true
      detect_and_copy_new_topics           = true
      starting_position {
        type = "EARLIEST"
      }
      # identical names: consumers in the standby region read the same topics after a failover
      topic_name_configuration {
        type = "IDENTICAL"
      }
    }

    consumer_group_replication {
      consumer_groups_to_replicate        = [".*"]
      consumer_groups_to_exclude          = ["cell-router-.*"] # routers read from the latest offset by design
      synchronise_consumer_group_offsets  = true
      detect_and_copy_new_consumer_groups = true
    }
  }

  depends_on = [aws_msk_cluster_policy.primary, aws_iam_role_policy.msk_replicator]
}

# ---------------------------------------------------------------- DNS: Route 53 failover for the cell routers

locals {
  routers = {
    primary   = { dns = var.dns.primary_router_lb_dns, zone_id = var.dns.primary_router_lb_zone_id }
    secondary = { dns = var.dns.secondary_router_lb_dns, zone_id = var.dns.secondary_router_lb_zone_id }
  }
}

resource "aws_route53_health_check" "router" {
  for_each          = local.routers
  provider          = aws.primary
  fqdn              = each.value.dns
  port              = 443
  type              = "HTTPS"
  resource_path     = var.dns.health_check_path
  request_interval  = var.dns.health_check_interval
  failure_threshold = var.dns.health_check_failures
  measure_latency   = true
  tags              = merge(local.tags, { Name = "${local.name}-router-${each.key}" })
}

resource "aws_route53_record" "router" {
  for_each        = local.routers
  provider        = aws.primary
  zone_id         = var.dns.zone_id
  name            = var.dns.record_name
  type            = "A"
  set_identifier  = "${local.name}-${each.key}"
  health_check_id = aws_route53_health_check.router[each.key].id

  failover_routing_policy {
    type = upper(each.key)
  }

  alias {
    name                   = each.value.dns
    zone_id                = each.value.zone_id
    evaluate_target_health = true
  }
}
