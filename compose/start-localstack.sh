#!/bin/bash
export AWS_REGION=eu-west-2
export AWS_DEFAULT_REGION=eu-west-2
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# S3 buckets
aws --endpoint-url=http://localhost:4566 s3 mb s3://trade-imports-animals-documents
aws --endpoint-url=http://localhost:4566 s3 mb s3://cdp-uploader-quarantine

# SQS queues (required by cdp-uploader)
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name cdp-clamav-results
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name cdp-uploader-scan-results-callback.fifo --attributes FifoQueue=true
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name cdp-uploader-download-requests
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name mock-clamav
