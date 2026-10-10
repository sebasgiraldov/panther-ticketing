#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# Local infrastructure bootstrap (DEV ONLY).
#
# Creates the DynamoDB tables and the SQS queue(s) the application expects,
# against DynamoDB Local and LocalStack. This script is idempotent: it skips
# any resource that already exists, so re-running it is safe.
#
# ARCHITECTURAL NOTE:
#   The application NEVER provisions its own infrastructure. In production,
#   these tables and queues would be created by Terraform (IaC) with a
#   dedicated provisioning role. This script is the LOCAL equivalent of that
#   IaC step and runs as a one-shot init container, separate from the app's
#   runtime. The app's runtime role should only have data-plane permissions
#   (GetItem/PutItem/UpdateItem/Query, SendMessage/ReceiveMessage/DeleteMessage),
#   never control-plane permissions (CreateTable/CreateQueue).
# ---------------------------------------------------------------------------
set -eu

DYNAMODB_ENDPOINT="${DYNAMODB_ENDPOINT:-http://dynamodb-local:8000}"
SQS_ENDPOINT="${SQS_ENDPOINT:-http://sqs:9324}"
AWS_REGION="${AWS_REGION:-us-east-1}"

# Dummy creds — DynamoDB Local and LocalStack do not validate them.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-dummy}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-dummy}"
export AWS_DEFAULT_REGION="${AWS_REGION}"

DDB="aws dynamodb --endpoint-url ${DYNAMODB_ENDPOINT} --region ${AWS_REGION}"
SQS="aws sqs --endpoint-url ${SQS_ENDPOINT} --region ${AWS_REGION}"

echo "==> Bootstrapping local infrastructure"
echo "    DynamoDB endpoint: ${DYNAMODB_ENDPOINT}"
echo "    SQS endpoint:      ${SQS_ENDPOINT}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
table_exists() {
  $DDB describe-table --table-name "$1" >/dev/null 2>&1
}

# create_simple_table <name>  -> PK: id (S)
create_simple_table() {
  name="$1"
  if table_exists "$name"; then
    echo "    [skip] table ${name} already exists"
    return 0
  fi
  echo "    [create] table ${name} (PK: id)"
  $DDB create-table \
    --table-name "$name" \
    --attribute-definitions AttributeName=id,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
}

# ---------------------------------------------------------------------------
# DynamoDB tables
# ---------------------------------------------------------------------------
create_simple_table "panther-events"
create_simple_table "panther-reservations"
create_simple_table "panther-audit"

# tickets: PK id (S) + GSI "eventId-ticketTypeId-status-index" (hash eventId,
# range ticketTypeId — status is applied as a filter expression, not a key) +
# GSI "reservationId-index" (hash reservationId).
if table_exists "panther-tickets"; then
  echo "    [skip] table panther-tickets already exists"
else
  echo "    [create] table panther-tickets (PK: id, GSIs: eventId-ticketTypeId-status-index, reservationId-index)"
  $DDB create-table \
    --table-name "panther-tickets" \
    --attribute-definitions \
        AttributeName=id,AttributeType=S \
        AttributeName=eventId,AttributeType=S \
        AttributeName=ticketTypeId,AttributeType=S \
        AttributeName=reservationId,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --global-secondary-indexes \
        'IndexName=eventId-ticketTypeId-status-index,KeySchema=[{AttributeName=eventId,KeyType=HASH},{AttributeName=ticketTypeId,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        'IndexName=reservationId-index,KeySchema=[{AttributeName=reservationId,KeyType=HASH}],Projection={ProjectionType=ALL}' \
    --billing-mode PAY_PER_REQUEST >/dev/null
fi

# orders: PK id (S) + GSI "idempotencyKey-index" (hash idempotencyKey).
if table_exists "panther-orders"; then
  echo "    [skip] table panther-orders already exists"
else
  echo "    [create] table panther-orders (PK: id, GSI: idempotencyKey-index)"
  $DDB create-table \
    --table-name "panther-orders" \
    --attribute-definitions \
        AttributeName=id,AttributeType=S \
        AttributeName=idempotencyKey,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --global-secondary-indexes \
        'IndexName=idempotencyKey-index,KeySchema=[{AttributeName=idempotencyKey,KeyType=HASH}],Projection={ProjectionType=ALL}' \
    --billing-mode PAY_PER_REQUEST >/dev/null
fi

# ticket-types: PK id (S) + GSI "eventId-index" on eventId (S)
if table_exists "panther-ticket-types"; then
  echo "    [skip] table panther-ticket-types already exists"
else
  echo "    [create] table panther-ticket-types (PK: id, GSI: eventId-index)"
  $DDB create-table \
    --table-name "panther-ticket-types" \
    --attribute-definitions \
        AttributeName=id,AttributeType=S \
        AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --global-secondary-indexes \
        'IndexName=eventId-index,KeySchema=[{AttributeName=eventId,KeyType=HASH}],Projection={ProjectionType=ALL}' \
    --billing-mode PAY_PER_REQUEST >/dev/null
fi

# inventory: PK pk (S)  (pk = "<eventId>#<ticketTypeId>")
if table_exists "panther-inventory"; then
  echo "    [skip] table panther-inventory already exists"
else
  echo "    [create] table panther-inventory (PK: pk)"
  $DDB create-table \
    --table-name "panther-inventory" \
    --attribute-definitions AttributeName=pk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST >/dev/null
fi

# ---------------------------------------------------------------------------
# SQS queues
# ---------------------------------------------------------------------------
# Queues (panther-orders + panther-orders-dlq with redrive) are declared
# statically in local-infra/elasticmq.conf and created by ElasticMQ on startup,
# so there is nothing to provision here. We just verify the queue is reachable.
echo "==> Verifying SQS queue is reachable"
if $SQS get-queue-url --queue-name "panther-orders" >/dev/null 2>&1; then
  echo "    [ok] queue panther-orders is available"
else
  echo "    [warn] queue panther-orders not reachable yet (ElasticMQ may still be warming up)"
fi

echo "==> Local infrastructure ready"
