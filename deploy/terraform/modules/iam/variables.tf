variable "environment"     { type = string }
variable "dynamodb_tables" { type = list(string) }
variable "sqs_queue_arn"   { type = string }
variable "sqs_dlq_arn"     { type = string }
