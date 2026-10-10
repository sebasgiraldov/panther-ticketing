# Terraform — panther-ticketing

Infrastructure as Code for deploying panther-ticketing to AWS.
Targets real AWS (not LocalStack). Local development uses `podman compose` instead.

## Structure

```
terraform/
├── main.tf                        # root module — wires all modules together
├── variables.tf                   # input variables with descriptions
├── outputs.tf                     # useful values after apply (ALB DNS, etc.)
├── modules/
│   ├── networking/                # VPC, subnets, IGW, NAT, ALB, security groups
│   ├── iam/                       # task execution role + task role (least-privilege)
│   ├── dynamodb/                  # 7 tables with GSIs and PITR
│   ├── sqs/                       # orders queue + DLQ
│   └── ecs/                       # Fargate cluster, task definition, service, autoscaling
└── environments/
    ├── dev/terraform.tfvars       # dev: 1 task, 256 CPU, DEBUG logs
    └── prod/terraform.tfvars      # prod: 2 tasks (HA), 512 CPU, INFO logs
```

## Prerequisites

- Terraform >= 1.7
- AWS CLI configured (`aws configure`)
- An S3 bucket and DynamoDB table for remote state (see below)
- An ECR repository with the app image pushed
- An ACM certificate for HTTPS (optional for dev)

## Bootstrap remote state (one-time)

```bash
aws s3api create-bucket --bucket panther-ticketing-tfstate --region us-east-1
aws s3api put-bucket-versioning \
  --bucket panther-ticketing-tfstate \
  --versioning-configuration Status=Enabled
aws dynamodb create-table \
  --table-name panther-ticketing-tfstate-lock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

## Deploy

```bash
cd deploy/terraform

# Initialize (downloads providers, configures backend)
terraform init

# Plan for dev
terraform plan -var-file=environments/dev/terraform.tfvars

# Apply for dev
terraform apply -var-file=environments/dev/terraform.tfvars

# Apply for prod
terraform apply -var-file=environments/prod/terraform.tfvars
```

## Key architectural decisions

### Networking
- **Public subnets**: ALB only. No app or data resources are publicly reachable.
- **Private subnets**: ECS tasks. Outbound via NAT Gateway (ECR pulls, DynamoDB, SQS).
- **Security groups**: ECS tasks only accept traffic from the ALB SG — no direct internet access.
- **TLS termination at ALB**: ECS tasks communicate over plain HTTP internally; TLS is handled at the edge.

### IAM — least privilege
- **Task execution role**: only ECR pull + CloudWatch logs + Secrets Manager read.
- **Task role** (app runtime): only the exact DynamoDB actions needed (`GetItem`, `PutItem`, `UpdateItem`, `Query`, `Scan`) scoped to the specific table ARNs. No `CreateTable`, no `*`.
- No access keys anywhere — credentials come from the task role via IMDS.

### DynamoDB
- `PAY_PER_REQUEST` billing: no capacity planning, scales automatically with traffic spikes.
- PITR enabled on all tables: point-in-time recovery for accidental deletes.
- TTL on audit table: auto-expires records after 90 days to control storage costs.
- GSIs match exactly the query patterns used by the use cases.

### SQS
- Standard queue (not FIFO): higher throughput; order doesn't matter since each order is independent and the consumer is idempotent.
- DLQ with `maxReceiveCount=3`: failed messages are preserved for inspection and replay.
- `DenyNonHTTPS` resource policy: rejects unencrypted requests to the queue.

### ECS Fargate
- `deployment_circuit_breaker`: automatic rollback if new tasks fail health checks.
- `startPeriod=60s`: gives Spring Boot time to start before health checks begin.
- Auto scaling on CPU 70%: scales out in 60s, scales in conservatively in 300s to avoid flapping.
- `desired_count=2` in prod: two tasks across two AZs for high availability.
