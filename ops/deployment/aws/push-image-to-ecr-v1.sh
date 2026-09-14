#!/usr/bin/env bash
set -euo pipefail

required_env() {
  local key="$1"
  if [ -z "${!key:-}" ]; then
    echo "Missing required environment variable: ${key}" >&2
    exit 1
  fi
}

required_env AWS_REGION
required_env ECR_REPOSITORY

IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD)}"
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE_URI="${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"

if ! repository_check_output="$(aws ecr describe-repositories \
  --region "${AWS_REGION}" \
  --repository-names "${ECR_REPOSITORY}" 2>&1)"; then
  echo "${repository_check_output}" >&2
  echo "ECR repository '${ECR_REPOSITORY}' does not exist or cannot be described in region '${AWS_REGION}'." >&2
  echo "Create the ECR repository before pushing images. This script intentionally does not create repositories automatically." >&2
  exit 1
fi

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}" >/dev/null

docker build -t "${IMAGE_URI}" .
docker push "${IMAGE_URI}"

printf '%s\n' "${IMAGE_URI}"
