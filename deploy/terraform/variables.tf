variable "aws_region" {
  description = "AWS region where all resources are deployed"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment (dev | prod)"
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be 'dev' or 'prod'."
  }
}

variable "app_port" {
  description = "Port the application container listens on"
  type        = number
  default     = 8080
}

variable "app_image" {
  description = "Full ECR image URI including tag (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/panther-ticketing:latest)"
  type        = string
}

variable "task_cpu" {
  description = "Fargate task CPU units (256 | 512 | 1024 | 2048 | 4096)"
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate task memory in MiB"
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "Number of ECS task replicas to run"
  type        = number
  default     = 1
}

variable "reservation_minutes" {
  description = "Ticket reservation hold time in minutes"
  type        = number
  default     = 10
}

variable "log_level" {
  description = "Application log level (INFO | DEBUG | WARN)"
  type        = string
  default     = "INFO"
}
