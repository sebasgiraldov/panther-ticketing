# ── CloudWatch Log Group ───────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/panther-ticketing-${var.environment}"
  retention_in_days = 30 # balance between cost and debuggability

  tags = { Name = "panther-ticketing-logs-${var.environment}" }
}

# ── ECS Cluster ────────────────────────────────────────────────────────────────
resource "aws_ecs_cluster" "main" {
  name = "panther-ticketing-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled" # CloudWatch Container Insights for CPU/memory metrics
  }

  tags = { Name = "panther-ticketing-${var.environment}" }
}

# ── Task Definition ────────────────────────────────────────────────────────────
# Fargate: serverless containers — no EC2 instances to manage or patch.
# The app has no endpoint override for DynamoDB/SQS — it uses the real AWS
# endpoints resolved via the SDK's default region-based endpoint resolution.
# Credentials come from the task role (no access keys in env vars).

resource "aws_ecs_task_definition" "app" {
  family                   = "panther-ticketing-${var.environment}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # required for Fargate; each task gets its own ENI
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = var.task_execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([{
    name      = "panther-ticketing"
    image     = var.app_image
    essential = true

    portMappings = [{
      containerPort = var.app_port
      protocol      = "tcp"
    }]

    environment = [
      { name = "SERVER_PORT",                        value = tostring(var.app_port) },
      { name = "AWS_REGION",                         value = var.aws_region },
      { name = "DYNAMODB_TABLE_EVENTS",              value = var.dynamodb_tables["events"] },
      { name = "DYNAMODB_TABLE_TICKET_TYPES",        value = var.dynamodb_tables["ticket_types"] },
      { name = "DYNAMODB_TABLE_TICKETS",             value = var.dynamodb_tables["tickets"] },
      { name = "DYNAMODB_TABLE_RESERVATIONS",        value = var.dynamodb_tables["reservations"] },
      { name = "DYNAMODB_TABLE_ORDERS",              value = var.dynamodb_tables["orders"] },
      { name = "DYNAMODB_TABLE_INVENTORY",           value = var.dynamodb_tables["inventory"] },
      { name = "DYNAMODB_TABLE_AUDIT",               value = var.dynamodb_tables["audit"] },
      { name = "SQS_QUEUE_URL",                      value = var.sqs_queue_url },
      { name = "RESERVATION_MINUTES",                value = tostring(var.reservation_minutes) },
      { name = "LOG_LEVEL",                          value = var.log_level },
    ]

    # No DYNAMODB_ENDPOINT or SQS_ENDPOINT — uses real AWS endpoints.
    # No AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY — uses task role via IMDS.

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:${var.app_port}/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60 # give Spring Boot time to start before health checks begin
    }
  }])

  tags = { Name = "panther-ticketing-${var.environment}" }
}

# ── ECS Service ────────────────────────────────────────────────────────────────
# desired_count > 1 gives HA across AZs.
# deployment_circuit_breaker: rolls back automatically if new tasks fail health checks.

resource "aws_ecs_service" "app" {
  name            = "panther-ticketing-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.ecs_security_group_id]
    assign_public_ip = false # tasks are in private subnets, reach internet via NAT
  }

  load_balancer {
    target_group_arn = var.alb_target_group_arn
    container_name   = "panther-ticketing"
    container_port   = var.app_port
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Graceful shutdown: ECS waits up to 30s for in-flight requests to complete
  # before stopping the task (matches Spring Boot lifecycle.timeout-per-shutdown-phase)
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200

  depends_on = [var.alb_target_group_arn]

  tags = { Name = "panther-ticketing-${var.environment}" }
}

# ── Auto Scaling ───────────────────────────────────────────────────────────────
# Scale out when CPU > 70% for 2 consecutive minutes.
# Scale in when CPU < 30% for 5 minutes (conservative to avoid flapping).

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = 10
  min_capacity       = var.desired_count
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu_scale_out" {
  name               = "panther-cpu-scale-out-${var.environment}"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 70.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
