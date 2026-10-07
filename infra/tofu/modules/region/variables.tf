variable "name" {
  description = "Id of the region pair, e.g. eu1 (names the global database, replication and DNS resources)"
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9]{2,12}$", var.name))
    error_message = "name must be 2-12 lowercase letters or digits"
  }
}

variable "environment" {
  description = "dev | staging | prod"
  type        = string
}

variable "primary" {
  description = "Primary cell (home region): cell id, VPC CIDR and node pool sizes"
  type = object({
    cell     = string
    vpc_cidr = string
    node_min = optional(number, 3)
    node_max = optional(number, 30)
  })
}

variable "secondary" {
  description = "Standby cell in the DR region (warm standby: READ_ONLY in the tenant registry until promoted)"
  type = object({
    cell     = string
    vpc_cidr = string
    node_min = optional(number, 3)
    node_max = optional(number, 30)
  })
}

variable "postgres_version" {
  type    = string
  default = "17.5"
}

variable "global_db_rpo_seconds" {
  description = "Aurora managed RPO (rds.global_db_rpo): primary commits pause when the secondary lags more (>= 20s; null = off)"
  type        = number
  default     = null
}

variable "documents_replication_minutes" {
  description = "S3 Replication Time Control target (15 minutes, the only value S3 RTC supports today)"
  type        = number
  default     = 15
}

variable "dns" {
  description = <<-EOT
    Route 53 failover for the cell routers: zone, record name (e.g. api.eu.meridian.example) and each region's router
    load balancer (alias DNS name and its hosted zone id). The health checks call https://<lb>/healthz.
  EOT
  type = object({
    zone_id                     = string
    record_name                 = string
    primary_router_lb_dns       = string
    primary_router_lb_zone_id   = string
    secondary_router_lb_dns     = string
    secondary_router_lb_zone_id = string
    health_check_path           = optional(string, "/healthz")
    health_check_interval       = optional(number, 10)
    health_check_failures       = optional(number, 3)
  })
}

variable "replicate_kafka" {
  description = "Create an MSK Replicator primary -> secondary (topics and consumer group offsets)"
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
