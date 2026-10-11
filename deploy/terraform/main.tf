terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Remote state in S3 — prevents concurrent applies from corrupting state.
  # The bucket and DynamoDB lock table must exist before the first `terraform init`.
  # Create them once manually or with a bootstrap script.
  backend "s3" {
    bucket         = "panther-ticketing-tfstate"
    key            = "panther-ticketing/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "panther-ticketing-tfstate-lock"
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "panther-ticketing"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# ── Modules ────────────────────────────────────────────────────────────────────

module "networking" {
  source      = "./modules/networking"
  environment = var.environment
  app_port    = var.app_port
}

module "iam" {
  source           = "./modules/iam"
  environment      = var.environment
  dynamodb_tables  = module.dynamodb.table_arns
  sqs_queue_arn    = module.sqs.queue_arn
  sqs_dlq_arn      = module.sqs.dlq_arn
}

module "dynamodb" {
  source      = "./modules/dynamodb"
  environment = var.environment
}

module "sqs" {
  source      = "./modules/sqs"
  environment = var.environment
}

module "ecs" {
  source                    = "./modules/ecs"
  environment               = var.environment
  aws_region                = var.aws_region
  app_port                  = var.app_port
  app_image                 = var.app_image
  task_cpu                  = var.task_cpu
  task_memory               = var.task_memory
  desired_count             = var.desired_count
  vpc_id                    = module.networking.vpc_id
  private_subnet_ids        = module.networking.private_subnet_ids
  alb_target_group_arn      = module.networking.alb_target_group_arn
  ecs_security_group_id     = module.networking.ecs_security_group_id
  task_execution_role_arn   = module.iam.task_execution_role_arn
  task_role_arn             = module.iam.task_role_arn
  dynamodb_tables           = module.dynamodb.table_names
  sqs_queue_url             = module.sqs.queue_url
  reservation_minutes       = var.reservation_minutes
  log_level                 = var.log_level
}
