output "alb_dns_name" {
  description = "Public DNS of the Application Load Balancer"
  value       = module.networking.alb_dns_name
}

output "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  value       = module.ecs.cluster_name
}

output "dynamodb_table_names" {
  description = "Map of logical name → DynamoDB table name"
  value       = module.dynamodb.table_names
}

output "sqs_queue_url" {
  description = "URL of the orders SQS queue"
  value       = module.sqs.queue_url
}

output "sqs_dlq_url" {
  description = "URL of the dead-letter queue"
  value       = module.sqs.dlq_url
}
