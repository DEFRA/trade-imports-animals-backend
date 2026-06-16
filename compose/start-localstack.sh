#!/bin/bash
# Canonical copy — the workspace stack (DEFRA/trade-imports-animals-workspace,
# docker/stack) stages and runs this in its localstack-init container.
# Endpoint-driven so it works both from a sidecar container (LOCALSTACK_URL
# set) and inside a localstack container ready.d hook (defaults to localhost).
set -euo pipefail

ENDPOINT="${LOCALSTACK_URL:-http://localhost:4566}"
export AWS_REGION="${AWS_REGION:-eu-west-2}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-$AWS_REGION}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"

aws() { command aws --endpoint-url="$ENDPOINT" --region "$AWS_REGION" "$@"; }

# S3 buckets (|| true makes creation idempotent on restart)
aws s3 mb s3://cdp-uploader-quarantine || true
aws s3 mb s3://trade-imports-animals-documents || true

# SQS queues (|| true makes creation idempotent on restart). Assumes queue
# attributes haven't changed between runs — if they have (e.g. after a local
# config change), the existing queue will be silently reused with its stale
# attributes. Recreate the container to force a clean queue in that case.
aws sqs create-queue --queue-name cdp-clamav-results || true
aws sqs create-queue --queue-name cdp-uploader-download-requests || true
aws sqs create-queue --queue-name mock-clamav || true
aws sqs create-queue --queue-name cdp-uploader-scan-results-callback.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true || true

# S3 event notifications — trigger mock virus scanner when files land in quarantine
MOCK_CLAMAV_ARN="arn:aws:sqs:${AWS_REGION}:000000000000:mock-clamav"
aws s3api put-bucket-notification-configuration \
  --bucket cdp-uploader-quarantine \
  --notification-configuration "{\"QueueConfigurations\":[{\"Id\":\"mock-virus-scan\",\"QueueArn\":\"${MOCK_CLAMAV_ARN}\",\"Events\":[\"s3:ObjectCreated:*\"]}]}"
