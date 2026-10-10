environment         = "prod"
aws_region          = "us-east-1"
app_image           = "123456789012.dkr.ecr.us-east-1.amazonaws.com/panther-ticketing:stable"
task_cpu            = 512
task_memory         = 1024
desired_count       = 2   # two tasks across two AZs for HA
reservation_minutes = 10
log_level           = "INFO"
