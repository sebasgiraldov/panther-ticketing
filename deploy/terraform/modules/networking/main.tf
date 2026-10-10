# ── VPC ────────────────────────────────────────────────────────────────────────
# A dedicated VPC isolates the workload from other accounts/environments.
# Using /16 gives 65 536 IPs — plenty of room to add subnets without re-addressing.

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "panther-ticketing-${var.environment}" }
}

# ── Subnets ────────────────────────────────────────────────────────────────────
# Public subnets: only the ALB lives here. No app or data resources are public.
# Private subnets: ECS tasks run here. They reach the internet via NAT Gateway
# (for ECR image pulls, Secrets Manager, etc.) but are never directly reachable.

data "aws_availability_zones" "available" { state = "available" }

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.${count.index}.0/24"
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = { Name = "panther-public-${count.index}-${var.environment}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.${count.index + 10}.0/24"
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = { Name = "panther-private-${count.index}-${var.environment}" }
}

# ── Internet Gateway + NAT ─────────────────────────────────────────────────────
# IGW: allows the ALB (public subnet) to receive traffic from the internet.
# NAT Gateway: allows ECS tasks (private subnet) to make outbound calls
# (ECR, DynamoDB, SQS endpoints) without being directly exposed.
# One NAT per AZ would give HA but costs more — single NAT is fine for dev.

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "panther-igw-${var.environment}" }
}

resource "aws_eip" "nat" {
  domain = "vpc"
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "panther-nat-${var.environment}" }
}

# ── Route Tables ───────────────────────────────────────────────────────────────

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "panther-rt-public-${var.environment}" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }
  tags = { Name = "panther-rt-private-${var.environment}" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ── Security Groups ────────────────────────────────────────────────────────────
# ALB SG: accepts HTTPS (443) from anywhere. HTTP (80) only to redirect to HTTPS.
# ECS SG: only accepts traffic from the ALB SG on the app port. No direct internet.
# This enforces that all traffic goes through the ALB — no bypassing TLS termination.

resource "aws_security_group" "alb" {
  name        = "panther-alb-${var.environment}"
  description = "ALB: inbound HTTPS from internet, outbound to ECS"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTPS from internet"
  }

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTP — redirected to HTTPS by ALB listener rule"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "panther-alb-sg-${var.environment}" }
}

resource "aws_security_group" "ecs" {
  name        = "panther-ecs-${var.environment}"
  description = "ECS tasks: inbound only from ALB on app port"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = var.app_port
    to_port         = var.app_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
    description     = "Traffic from ALB only"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Outbound to DynamoDB, SQS, ECR via NAT"
  }

  tags = { Name = "panther-ecs-sg-${var.environment}" }
}

# ── Application Load Balancer ──────────────────────────────────────────────────
# ALB terminates TLS so ECS tasks never handle certificates.
# Health check hits /actuator/health — Spring Boot Actuator endpoint.

resource "aws_lb" "main" {
  name               = "panther-alb-${var.environment}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  # Access logs help with debugging and security audits
  # enable_deletion_protection = true  # uncomment for prod

  tags = { Name = "panther-alb-${var.environment}" }
}

resource "aws_lb_target_group" "app" {
  name        = "panther-tg-${var.environment}"
  port        = var.app_port
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # required for Fargate

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  # Graceful shutdown: ALB stops sending new requests before ECS drains the task
  deregistration_delay = 30

  tags = { Name = "panther-tg-${var.environment}" }
}

# HTTP → HTTPS redirect
resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# HTTPS listener — certificate must exist in ACM before applying
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06" # TLS 1.3 preferred
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
