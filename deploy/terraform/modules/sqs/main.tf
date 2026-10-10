# ── Dead Letter Queue ──────────────────────────────────────────────────────────
# Messages that fail maxReceiveCount times are moved here automatically.
# Allows inspection and replay of failed orders without data loss.

resource "aws_sqs_queue" "orders_dlq" {
  name                      = "panther-orders-dlq-${var.environment}"
  message_retention_seconds = 1209600 # 14 days — max allowed by SQS

  tags = { Name = "panther-orders-dlq-${var.environment}" }
}

# ── Orders Queue ───────────────────────────────────────────────────────────────
# Standard queue (not FIFO): higher throughput, at-least-once delivery.
# Order doesn't matter — each order is independent and the consumer is idempotent.
#
# visibility_timeout_seconds: must be >= the max processing time of one message.
# Set to 60s — ProcessOrderUseCase involves ~6 DynamoDB calls, well under 60s.
#
# receive_wait_time_seconds: enables long polling (20s max).
# Reduces empty receives and lowers cost vs short polling.

resource "aws_sqs_queue" "orders" {
  name                       = "panther-orders-${var.environment}"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20     # long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.orders_dlq.arn
    maxReceiveCount     = 3 # after 3 failed attempts → DLQ
  })

  tags = { Name = "panther-orders-${var.environment}" }
}

# ── Queue Policy ───────────────────────────────────────────────────────────────
# Restricts who can send/receive messages.
# Only the ECS task role (via IAM, not resource policy) needs access —
# the resource policy here denies non-HTTPS requests as an extra layer.

resource "aws_sqs_queue_policy" "orders" {
  queue_url = aws_sqs_queue.orders.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyNonHTTPS"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = aws_sqs_queue.orders.arn
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}
