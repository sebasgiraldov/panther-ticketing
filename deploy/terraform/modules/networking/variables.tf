variable "environment" { type = string }
variable "app_port"    { type = number }

variable "acm_certificate_arn" {
  description = "ARN of the ACM certificate for HTTPS. Must exist before applying."
  type        = string
  default     = ""
}
