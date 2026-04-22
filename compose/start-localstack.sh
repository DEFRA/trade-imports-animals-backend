#!/bin/bash
export AWS_REGION=eu-west-2
export AWS_DEFAULT_REGION=eu-west-2
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# S3 buckets (|| true makes creation idempotent on restart with a persisted volume)
awslocal s3 mb s3://cdp-uploader-quarantine || true
awslocal s3 mb s3://trade-imports-animals-documents || true

# SQS queues (|| true makes creation idempotent on restart with a persisted volume)
awslocal sqs create-queue --queue-name cdp-clamav-results || true
awslocal sqs create-queue --queue-name cdp-uploader-download-requests || true
awslocal sqs create-queue --queue-name mock-clamav || true
awslocal sqs create-queue --queue-name cdp-uploader-scan-results-callback.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true || true
