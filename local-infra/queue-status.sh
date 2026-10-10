#!/usr/bin/env sh
# Quick SQS (ElasticMQ) inspection: lists each queue and its message counts.
# Usage: sh local-infra/queue-status.sh
set -eu

SQS_ENDPOINT="${SQS_ENDPOINT:-http://localhost:9324}"
AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-dummy}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-dummy}"

echo "SQS endpoint: ${SQS_ENDPOINT}"
echo "---------------------------------------------"

QUEUE_URLS=$(aws sqs list-queues --endpoint-url "$SQS_ENDPOINT" --region "$AWS_REGION" \
  --query 'QueueUrls' --output text 2>/dev/null || true)

if [ -z "${QUEUE_URLS}" ] || [ "${QUEUE_URLS}" = "None" ]; then
  echo "No queues found (is ElasticMQ running on ${SQS_ENDPOINT}?)"
  exit 0
fi

for url in $QUEUE_URLS; do
  name=$(basename "$url")
  attrs=$(aws sqs get-queue-attributes --queue-url "$url" \
            --endpoint-url "$SQS_ENDPOINT" --region "$AWS_REGION" \
            --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
            --query 'Attributes' --output json 2>/dev/null || echo '{}')
  visible=$(echo "$attrs" | sed -n 's/.*"ApproximateNumberOfMessages": *"\([0-9]*\)".*/\1/p')
  inflight=$(echo "$attrs" | sed -n 's/.*"ApproximateNumberOfMessagesNotVisible": *"\([0-9]*\)".*/\1/p')
  printf "Queue: %-28s available=%s in-flight=%s\n" "$name" "${visible:-0}" "${inflight:-0}"
done
