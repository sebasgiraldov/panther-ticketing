data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ── Task Execution Role ────────────────────────────────────────────────────────
# Used by the ECS agent (not the app) to:
#   - Pull the container image from ECR
#   - Write logs to CloudWatch
#   - Read secrets from Secrets Manager (injected as env vars)
# The app itself never uses this role.

resource "aws_iam_role" "task_execution" {
  name = "panther-ecs-execution-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "execution_basic" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Allow reading secrets from Secrets Manager (DB passwords, API keys, etc.)
resource "aws_iam_role_policy" "execution_secrets" {
  name = "panther-secrets-read-${var.environment}"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:panther-ticketing/${var.environment}/*"
    }]
  })
}

# ── Task Role ──────────────────────────────────────────────────────────────────
# Used by the application code at runtime.
# Follows least-privilege: only the exact DynamoDB actions the app needs,
# scoped to the specific tables. No wildcard resources.

resource "aws_iam_role" "task" {
  name = "panther-ecs-task-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "task_dynamodb" {
  name = "panther-dynamodb-${var.environment}"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        # Data-plane only — no CreateTable, DeleteTable, etc.
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ]
      Resource = concat(
        var.dynamodb_tables,
        # Allow queries on GSIs (index ARNs are table ARN + /index/*)
        [for arn in var.dynamodb_tables : "${arn}/index/*"]
      )
    }]
  })
}

resource "aws_iam_role_policy" "task_sqs" {
  name = "panther-sqs-${var.environment}"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ]
      # Scoped to the orders queue and its DLQ only
      Resource = [var.sqs_queue_arn, var.sqs_dlq_arn]
    }]
  })
}
