output "table_arns" {
  description = "List of all DynamoDB table ARNs (used by IAM policy)"
  value = [
    aws_dynamodb_table.events.arn,
    aws_dynamodb_table.ticket_types.arn,
    aws_dynamodb_table.tickets.arn,
    aws_dynamodb_table.reservations.arn,
    aws_dynamodb_table.orders.arn,
    aws_dynamodb_table.inventory.arn,
    aws_dynamodb_table.audit.arn,
  ]
}

output "table_names" {
  description = "Map of logical name → actual table name (injected as env vars into ECS)"
  value = {
    events       = aws_dynamodb_table.events.name
    ticket_types = aws_dynamodb_table.ticket_types.name
    tickets      = aws_dynamodb_table.tickets.name
    reservations = aws_dynamodb_table.reservations.name
    orders       = aws_dynamodb_table.orders.name
    inventory    = aws_dynamodb_table.inventory.name
    audit        = aws_dynamodb_table.audit.name
  }
}
