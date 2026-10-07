variable "cell" {
  description = "Cell id, unique per region (e.g. eu1a)"
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9]{2,12}$", var.cell))
    error_message = "cell must be 2-12 lowercase letters or digits"
  }
}

variable "environment" {
  description = "dev | staging | prod (prod enables multi-AZ NAT, 3 DB instances, deletion protection, 35-day backups)"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod"
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.33"
}

variable "eks_public_endpoint" {
  description = "Expose the EKS API publicly (false in prod: reach it through VPN / bastion)"
  type        = bool
  default     = false
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m7i.2xlarge"]
}

variable "node_min" {
  type    = number
  default = 3
}

variable "node_max" {
  type    = number
  default = 30
}

variable "postgres_version" {
  type    = string
  default = "17.5"
}

variable "db_role" {
  description = "standalone (own Aurora cluster) | primary / secondary member of an Aurora Global Database (DR pair)"
  type        = string
  default     = "standalone"
  validation {
    condition     = contains(["standalone", "primary", "secondary"], var.db_role)
    error_message = "db_role must be standalone, primary or secondary"
  }
}

variable "global_cluster_identifier" {
  description = "Aurora Global Database the cell's cluster joins (db_role primary / secondary)"
  type        = string
  default     = null
}

variable "global_db_rpo_seconds" {
  description = "Aurora PostgreSQL managed RPO (rds.global_db_rpo, >= 20s): primary commits wait when every secondary lags more"
  type        = number
  default     = null
  validation {
    condition     = var.global_db_rpo_seconds == null || try(var.global_db_rpo_seconds >= 20, false)
    error_message = "global_db_rpo_seconds must be at least 20"
  }
}

variable "secret_replicas" {
  description = "Regions (and KMS keys there) the database secrets are replicated to, for the DR standby cell"
  type = list(object({
    region     = string
    kms_key_id = string
  }))
  default = []
}

variable "postgres_min_acu" {
  type    = number
  default = 2
}

variable "postgres_max_acu" {
  type    = number
  default = 64
}

variable "kafka_version" {
  type    = string
  default = "3.9.x"
}

variable "kafka_instance_type" {
  type    = string
  default = "kafka.m7g.large"
}

variable "kafka_multi_vpc" {
  description = "Enable MSK multi-VPC private connectivity (source cluster of a cross-region MSK Replicator)"
  type        = bool
  default     = false
}

variable "kafka_volume_gb" {
  type    = number
  default = 500
}

variable "object_lock_days" {
  description = "Default WORM retention for documents/archives (0 disables object lock)"
  type        = number
  default     = 0
}

variable "audit_retention_years" {
  description = "Default COMPLIANCE-mode Object Lock retention of sealed audit batches (audit-service also sets a per-object retain-until)"
  type        = number
  default     = 10
}

variable "audit_cold_after_days" {
  description = "Days after which sealed audit batches move to S3 Glacier Instant Retrieval"
  type        = number
  default     = 90
}

variable "tags" {
  type    = map(string)
  default = {}
}

# ---------------------------------------------------------------- cardholder data environment (GAP-01 / GAP-02)

variable "cde_dedicated_db" {
  description = "Run card_vault_db on its own Aurora cluster encrypted with the CDE KMS key (recommended for PCI DSS)"
  type        = bool
  default     = false
}

variable "cde_postgres_min_acu" {
  type    = number
  default = 0.5
}

variable "cde_postgres_max_acu" {
  type    = number
  default = 8
}

variable "cde_kms_multi_region" {
  description = "Create the CDE KMS key as a multi-region key (primary cell of a cross-region DR pair)"
  type        = bool
  default     = false
}

variable "cde_kms_replica_of" {
  description = "ARN of the primary cell's multi-region CDE key: this (standby) cell replicates it instead of creating its own"
  type        = string
  default     = null
}

variable "cde_namespaces" {
  description = "Kubernetes namespaces whose card-vault-service service account may assume the CDE KMS role (Helm release namespace and/or cde.namespace)"
  type        = list(string)
  default     = ["meridian", "meridian-cde"]
}

variable "kafka_iam_enabled" {
  description = "GAP-16: one IAM role per service as its MSK principal (IRSA), scoped by kafka-acls.json (tools/kafka_acls.py)"
  type        = bool
  default     = true
}

variable "k8s_namespace" {
  description = "Namespace of the Meridian Helm release in this cell (service accounts trusted by the Kafka IAM roles)"
  type        = string
  default     = "meridian"
}
