#!/usr/bin/env bash
set -euo pipefail

required_env() {
  local key="$1"
  if [ -z "${!key:-}" ]; then
    echo "Missing required environment variable: ${key}" >&2
    exit 1
  fi
}

fetch_parameter_if_available() {
  local parameter_name="$1"
  if command -v aws >/dev/null 2>&1 && [ -n "${AWS_REGION:-}" ] && [ -n "${PARAMETER_PREFIX:-}" ]; then
    aws ssm get-parameter \
      --region "${AWS_REGION}" \
      --name "${PARAMETER_PREFIX%/}/${parameter_name}" \
      --with-decryption \
      --query 'Parameter.Value' \
      --output text 2>/dev/null || true
  fi
}

if command -v docker >/dev/null 2>&1; then
  container_name="${CONTAINER_NAME:-bobfull-backend}"
  if docker ps --filter "name=${container_name}" --filter "status=running" --format '{{.Names}}' \
      | grep -Fx "${container_name}" >/dev/null; then
    echo "Docker container ${container_name}: PASS"
  fi
fi

if command -v aws >/dev/null 2>&1 && [ -n "${AWS_REGION:-}" ]; then
  aws sts get-caller-identity --query Account --output text >/dev/null
  echo "AWS caller identity: PASS"

  if [ -n "${PARAMETER_PREFIX:-}" ]; then
    aws ssm get-parameters-by-path \
      --region "${AWS_REGION}" \
      --path "${PARAMETER_PREFIX%/}" \
      --recursive \
      --query 'Parameters[].Name' \
      --output text >/dev/null
    echo "Parameter Store parameter names: PASS"
  fi

  s3_image_bucket="${S3_IMAGE_BUCKET:-$(fetch_parameter_if_available s3-image-bucket)}"
  if [ -n "${s3_image_bucket}" ]; then
    aws s3api head-bucket --region "${AWS_REGION}" --bucket "${s3_image_bucket}" >/dev/null
    echo "S3 image bucket access: PASS"
  fi

  if [ -n "${CLOUDWATCH_LOG_GROUP:-}" ]; then
    aws logs describe-log-streams \
      --region "${AWS_REGION}" \
      --log-group-name "${CLOUDWATCH_LOG_GROUP}" \
      --max-items 1 >/dev/null
    echo "CloudWatch log group access: PASS"
  fi

  if [ -n "${IMAGE_URI:-}" ]; then
    image_path="${IMAGE_URI#*/}"
    ecr_repository="${image_path%%:*}"
    image_tag="${image_path##*:}"

    aws ecr describe-images \
      --region "${AWS_REGION}" \
      --repository-name "${ecr_repository}" \
      --image-ids imageTag="${image_tag}" \
      >/dev/null
    echo "ECR image ${ecr_repository}:${image_tag}: PASS"
  fi
fi
