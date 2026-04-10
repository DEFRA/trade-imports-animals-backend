#!/bin/bash
export AWS_REGION=eu-west-2
export AWS_DEFAULT_REGION=eu-west-2
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# S3 buckets
awslocal s3 mb s3://cdp-uploader-quarantine
awslocal s3 mb s3://trade-imports-animals-documents

# SQS queues (required by cdp-uploader)
awslocal sqs create-queue --queue-name cdp-clamav-results
awslocal sqs create-queue --queue-name cdp-uploader-download-requests
awslocal sqs create-queue --queue-name mock-clamav
awslocal sqs create-queue --queue-name cdp-uploader-scan-results-callback.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true
