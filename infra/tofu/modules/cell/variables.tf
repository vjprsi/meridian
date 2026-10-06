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

variable "kafka_volume_gb" {
  type    = number
  default = 500
}

variable "object_lock_days" {
  description = "Default WORM retention for documents/archives (0 disables object lock)"
  type        = number
  default     = 0
}

variable "tags" {
  type    = map(string)
  default = {}
}
