#!/usr/bin/env bash
set -euo pipefail

required_env() {
  local key="$1"
  if [ -z "${!key:-}" ]; then
    echo "Missing required environment variable: ${key}" >&2
    exit 1
  fi
}

require_commands() {
  local missing_commands=()
  local command_name

  for command_name in "$@"; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
      missing_commands+=("${command_name}")
    fi
  done

  if [ "${#missing_commands[@]}" -gt 0 ]; then
    echo "Missing required commands on EC2:" >&2
    printf '  - %s\n' "${missing_commands[@]}" >&2
    exit 1
  fi
}

fetch_parameter() {
  local parameter_name="$1"
  aws ssm get-parameter \
    --region "${AWS_REGION}" \
    --name "${PARAMETER_PREFIX}/${parameter_name}" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

declare -A REQUIRED_PARAMETER_VALUES=()

validate_required_parameters() {
  local missing_parameters=()
  local item
  local parameter_name
  local parameter_value

  for item in "$@"; do
    parameter_name="${item#*:}"
    if ! parameter_value="$(fetch_parameter "${parameter_name}" 2>/dev/null)" \
        || [ -z "${parameter_value}" ] \
        || [ "${parameter_value}" = "None" ]; then
      missing_parameters+=("${PARAMETER_PREFIX}/${parameter_name}")
    else
      REQUIRED_PARAMETER_VALUES["${parameter_name}"]="${parameter_value}"
    fi
  done

  if [ "${#missing_parameters[@]}" -gt 0 ]; then
    echo "Missing required Parameter Store values:" >&2
    printf '  - %s\n' "${missing_parameters[@]}" >&2
    exit 1
  fi
}

append_env_value() {
  local env_key="$1"
  local value="$2"
  printf '%s=%s\n' "${env_key}" "${value}" >> "${APP_ENV_FILE}"
}

append_parameter() {
  local env_key="$1"
  local parameter_name="$2"
  local required="$3"
  local value

  if [ -n "${!env_key:-}" ]; then
    append_env_value "${env_key}" "${!env_key}"
    return
  fi

  if [ "${required}" = "true" ] && [[ -v "REQUIRED_PARAMETER_VALUES[${parameter_name}]" ]]; then
    append_env_value "${env_key}" "${REQUIRED_PARAMETER_VALUES[${parameter_name}]}"
    return
  fi

  if value="$(fetch_parameter "${parameter_name}" 2>/dev/null)"; then
    append_env_value "${env_key}" "${value}"
    return
  fi

  if [ "${required}" = "true" ]; then
    echo "Missing required Parameter Store value: ${PARAMETER_PREFIX}/${parameter_name}" >&2
    exit 1
  fi
}

container_exists() {
  local container_name="$1"
  docker container inspect "${container_name}" >/dev/null 2>&1
}

container_running() {
  local container_name="$1"
  [ "$(docker inspect --format='{{.State.Running}}' "${container_name}" 2>/dev/null || true)" = "true" ]
}

container_state() {
  local container_name="$1"
  docker inspect --format='{{.State.Status}}' "${container_name}" 2>/dev/null || printf 'missing'
}

print_container_logs() {
  local container_name="$1"
  local lines="${2:-200}"

  if container_exists "${container_name}"; then
    echo "----- docker logs --tail ${lines} ${container_name} -----" >&2
    docker logs --tail "${lines}" "${container_name}" >&2 || true
    echo "----- end docker logs ${container_name} -----" >&2
  else
    echo "Container '${container_name}' does not exist; no logs available." >&2
  fi
}

print_docker_ps_all() {
  echo "----- docker ps -a -----" >&2
  docker ps -a --no-trunc >&2 || true
  echo "----- end docker ps -a -----" >&2
}

ensure_docker_awslogs_driver() {
  local log_plugins

  if ! log_plugins="$(docker info --format '{{json .Plugins.Log}}' 2>/dev/null)"; then
    echo "Failed to inspect Docker log driver plugins." >&2
    exit 1
  fi

  if ! printf '%s\n' "${log_plugins}" | grep -q 'awslogs'; then
    echo "Docker awslogs log driver is required but is not available on this EC2 Docker daemon." >&2
    echo "Docker log plugins: ${log_plugins}" >&2
    exit 1
  fi
}

ensure_cloudwatch_log_group() {
  local create_output

  if create_output="$(aws logs create-log-group \
    --region "${AWS_REGION}" \
    --log-group-name "${CLOUDWATCH_LOG_GROUP}" 2>&1)"; then
    echo "CloudWatch log group '${CLOUDWATCH_LOG_GROUP}' created or already usable."
    return
  fi

  if printf '%s\n' "${create_output}" | grep -q 'ResourceAlreadyExistsException'; then
    echo "CloudWatch log group '${CLOUDWATCH_LOG_GROUP}' already exists."
    return
  fi

  echo "${create_output}" >&2
  echo "Failed to create or verify CloudWatch log group '${CLOUDWATCH_LOG_GROUP}'." >&2
  exit 1
}

ensure_docker_network() {
  if docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1; then
    echo "Docker network '${DOCKER_NETWORK}' exists."
    return
  fi

  if ! docker network create "${DOCKER_NETWORK}" >/dev/null; then
    echo "Failed to create Docker network '${DOCKER_NETWORK}'." >&2
    exit 1
  fi

  echo "Docker network '${DOCKER_NETWORK}' created."
}

ensure_docker_volume() {
  if docker volume inspect "${REDIS_VOLUME}" >/dev/null 2>&1; then
    echo "Docker volume '${REDIS_VOLUME}' exists."
    return
  fi

  if ! docker volume create "${REDIS_VOLUME}" >/dev/null; then
    echo "Failed to create Docker volume '${REDIS_VOLUME}'." >&2
    exit 1
  fi

  echo "Docker volume '${REDIS_VOLUME}' created."
}

ensure_container_network() {
  local container_name="$1"
  local network_name="$2"
  local network_alias="$3"

  if docker inspect --format='{{json .NetworkSettings.Networks}}' "${container_name}" | grep -q "\"${network_name}\""; then
    return
  fi

  if ! docker network connect --alias "${network_alias}" "${network_name}" "${container_name}"; then
    echo "Failed to connect container '${container_name}' to Docker network '${network_name}'." >&2
    print_container_logs "${container_name}" 100
    exit 1
  fi
}

cleanup() {
  if [ -n "${health_response_file:-}" ]; then
    rm -f "${health_response_file}" || true
  fi
}

trap cleanup EXIT

required_env AWS_REGION
required_env ECR_IMAGE_URI
required_env PARAMETER_PREFIX

CONTAINER_NAME="${CONTAINER_NAME:-bobfull-backend}"
HOST_PORT="${HOST_PORT:-8080}"
CONTAINER_PORT="${CONTAINER_PORT:-8080}"
APP_ENV_FILE="${APP_ENV_FILE:-/opt/bobfull/backend.env}"
CLOUDWATCH_LOG_GROUP="${CLOUDWATCH_LOG_GROUP:-/bobfull/backend}"
CLOUDWATCH_LOG_STREAM="${CLOUDWATCH_LOG_STREAM:-${CONTAINER_NAME}}"
DOCKER_NETWORK="${DOCKER_NETWORK:-bobfull-network}"
REDIS_CONTAINER_NAME="${REDIS_CONTAINER_NAME:-bobfull-redis}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7-alpine}"
REDIS_VOLUME="${REDIS_VOLUME:-bobfull-redis-data}"
PARAMETER_PREFIX="${PARAMETER_PREFIX%/}"

if [ -z "${PARAMETER_PREFIX}" ]; then
  echo "PARAMETER_PREFIX must not be empty after trimming trailing slash." >&2
  exit 1
fi

require_commands aws docker curl awk grep seq

required_parameters=(
  "DB_URL:db-url"
  "DB_USERNAME:db-username"
  "DB_PASSWORD:db-password"
  "REDIS_HOST:redis-host"
  "KAFKA_BOOTSTRAP_SERVERS:kafka-bootstrap-servers"
  "OPENAI_API_KEY:openai-api-key"
  "JWT_SECRET:jwt-secret"
  "PORTONE_API_SECRET:portone-api-secret"
  "PORTONE_STORE_ID:portone-store-id"
  "PORTONE_WEBHOOK_SECRET:portone-webhook-secret"
  "S3_IMAGE_BUCKET:s3-image-bucket"
  "MAIL_HOST:mail-host"
  "MAIL_USERNAME:mail-username"
  "MAIL_PASSWORD:mail-password"
)

optional_parameters=(
  "REDIS_PORT:redis-port"
  "REDIS_SSL_ENABLED:redis-ssl-enabled"
  "DB_POOL_MAX_SIZE:db-pool-max-size"
  "RESTAURANT_INSIGHT_AI_ENABLED:restaurant-insight-ai-enabled"
  "KAFKA_RESTAURANT_INSIGHT_CONSUMER_ENABLED:kafka-restaurant-insight-consumer-enabled"
  "AUTH_REFRESH_TOKEN_EXPIRATION_SECONDS:auth-refresh-token-expiration-seconds"
  "JWT_ACCESS_TOKEN_EXPIRATION_SECONDS:jwt-access-token-expiration-seconds"
  "JPA_DDL_AUTO:jpa-ddl-auto"
  "CORS_ALLOWED_ORIGINS:cors-allowed-origins"
  "PORTONE_CHANNEL_KEY:portone-channel-key"
  "MAIL_PORT:mail-port"
  "MAIL_SMTP_AUTH:mail-smtp-auth"
  "MAIL_SMTP_STARTTLS:mail-smtp-starttls"
  "NOTIFICATION_EMAIL_FROM_ADDRESS:notification-email-from-address"
  "PAYMENT_EXPIRATION_ENABLED:payment-expiration-enabled"
  "PAYMENT_EXPIRATION_FIXED_DELAY:payment-expiration-fixed-delay"
  "PAYMENT_EXPIRATION_BATCH_SIZE:payment-expiration-batch-size"
  "PAYMENT_REFUND_RECONCILIATION_ENABLED:payment-refund-reconciliation-enabled"
  "PAYMENT_REFUND_RECONCILIATION_FIXED_DELAY:payment-refund-reconciliation-fixed-delay"
  "PAYMENT_REFUND_RECONCILIATION_MINIMUM_AGE:payment-refund-reconciliation-minimum-age"
  "PAYMENT_REFUND_RECONCILIATION_RECHECK_DELAY:payment-refund-reconciliation-recheck-delay"
  "PAYMENT_REFUND_RECONCILIATION_BATCH_SIZE:payment-refund-reconciliation-batch-size"
  "S3_IMAGE_UPLOAD_URL_EXPIRATION:s3-image-upload-url-expiration"
  "S3_IMAGE_GET_URL_EXPIRATION:s3-image-get-url-expiration"
)

validate_required_parameters "${required_parameters[@]}"

app_env_dir="${APP_ENV_FILE%/*}"
if [ "${app_env_dir}" = "${APP_ENV_FILE}" ]; then
  app_env_dir="."
fi

mkdir -p "${app_env_dir}"
chmod 700 "${app_env_dir}"
umask 077
: > "${APP_ENV_FILE}"
chmod 600 "${APP_ENV_FILE}"

append_env_value SPRING_PROFILES_ACTIVE prod
append_env_value AWS_REGION "${AWS_REGION}"

for item in "${required_parameters[@]}"; do
  append_parameter "${item%%:*}" "${item#*:}" true
done

for item in "${optional_parameters[@]}"; do
  append_parameter "${item%%:*}" "${item#*:}" false
done

if ! grep -q '^JPA_DDL_AUTO=' "${APP_ENV_FILE}"; then
  append_env_value JPA_DDL_AUTO validate
fi

if ! grep -q '^JWT_ACCESS_TOKEN_EXPIRATION_SECONDS=' "${APP_ENV_FILE}"; then
  append_env_value JWT_ACCESS_TOKEN_EXPIRATION_SECONDS 3600
fi

if ! grep -q '^REDIS_PORT=' "${APP_ENV_FILE}"; then
  append_env_value REDIS_PORT 6379
fi

if ! grep -q '^REDIS_SSL_ENABLED=' "${APP_ENV_FILE}"; then
  append_env_value REDIS_SSL_ENABLED true
fi

if ! grep -q '^AUTH_REFRESH_TOKEN_EXPIRATION_SECONDS=' "${APP_ENV_FILE}"; then
  append_env_value AUTH_REFRESH_TOKEN_EXPIRATION_SECONDS 1209600
fi

s3_bucket="$(awk -F= '$1 == "S3_IMAGE_BUCKET" { print $2 }' "${APP_ENV_FILE}" | tail -n 1)"
if [ -z "${s3_bucket}" ]; then
  echo "S3_IMAGE_BUCKET is empty after Parameter Store env-file generation." >&2
  exit 1
fi

if ! aws s3api head-bucket --region "${AWS_REGION}" --bucket "${s3_bucket}" >/dev/null; then
  echo "S3 head-bucket failed for bucket '${s3_bucket}' in region '${AWS_REGION}'." >&2
  exit 1
fi

ensure_cloudwatch_log_group
ensure_docker_awslogs_driver

ecr_registry="${ECR_IMAGE_URI%%/*}"
if ! aws ecr get-login-password --region "${AWS_REGION}" \
    | docker login --username AWS --password-stdin "${ecr_registry}" >/dev/null; then
  echo "ECR login failed for registry '${ecr_registry}' while deploying image '${ECR_IMAGE_URI}'." >&2
  exit 1
fi

if ! docker pull "${ECR_IMAGE_URI}"; then
  echo "Docker pull failed for image '${ECR_IMAGE_URI}'." >&2
  exit 1
fi

redis_host="$(awk -F= '$1 == "REDIS_HOST" { print $2 }' "${APP_ENV_FILE}" | tail -n 1)"
redis_port="$(awk -F= '$1 == "REDIS_PORT" { print $2 }' "${APP_ENV_FILE}" | tail -n 1)"
redis_ssl_enabled="$(awk -F= '$1 == "REDIS_SSL_ENABLED" { print tolower($2) }' "${APP_ENV_FILE}" | tail -n 1)"

if [ -z "${redis_host}" ] || [ -z "${redis_port}" ]; then
  echo "Redis host or port is empty after Parameter Store env-file generation." >&2
  exit 1
fi

if [ "${redis_ssl_enabled}" != "true" ] && [ "${redis_ssl_enabled}" != "false" ]; then
  echo "REDIS_SSL_ENABLED must be true or false, but was '${redis_ssl_enabled}'." >&2
  exit 1
fi

ensure_docker_network

if [ "${redis_host}" = "redis" ]; then
  ensure_docker_volume

  if container_running "${REDIS_CONTAINER_NAME}"; then
    echo "Redis container '${REDIS_CONTAINER_NAME}' is already running; keeping existing container."
    ensure_container_network "${REDIS_CONTAINER_NAME}" "${DOCKER_NETWORK}" "${redis_host}"
  else
    if container_exists "${REDIS_CONTAINER_NAME}"; then
      docker rm "${REDIS_CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi

    if ! docker run -d \
        --name "${REDIS_CONTAINER_NAME}" \
        --restart unless-stopped \
        --network "${DOCKER_NETWORK}" \
        --network-alias "${redis_host}" \
        -v "${REDIS_VOLUME}:/data" \
        "${REDIS_IMAGE}" \
        redis-server --port "${redis_port}" --appendonly yes; then
      echo "Failed to run Redis container '${REDIS_CONTAINER_NAME}' with image '${REDIS_IMAGE}'." >&2
      print_container_logs "${REDIS_CONTAINER_NAME}" 200
      print_docker_ps_all
      exit 1
    fi

    sleep 2
  fi

  if ! redis_ping_output="$(docker exec "${REDIS_CONTAINER_NAME}" redis-cli -p "${redis_port}" ping 2>&1)"; then
    echo "Redis PING command failed for container '${REDIS_CONTAINER_NAME}' on port '${redis_port}'." >&2
    echo "${redis_ping_output}" >&2
    print_container_logs "${REDIS_CONTAINER_NAME}" 200
    exit 1
  fi

  if ! printf '%s\n' "${redis_ping_output}" | grep -Fx PONG >/dev/null; then
    echo "Redis PING returned unexpected output for container '${REDIS_CONTAINER_NAME}':" >&2
    echo "${redis_ping_output}" >&2
    print_container_logs "${REDIS_CONTAINER_NAME}" 200
    exit 1
  fi
else
  echo "Redis host '${redis_host}' is external; skipping EC2-local Redis container bootstrap."
fi

if container_exists "${CONTAINER_NAME}"; then
  docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || true
fi

if ! docker run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    --env-file "${APP_ENV_FILE}" \
    -p "${HOST_PORT}:${CONTAINER_PORT}" \
    --log-driver=awslogs \
    --log-opt awslogs-region="${AWS_REGION}" \
    --log-opt awslogs-group="${CLOUDWATCH_LOG_GROUP}" \
    --log-opt awslogs-stream="${CLOUDWATCH_LOG_STREAM}" \
    --log-opt awslogs-create-group=true \
    "${ECR_IMAGE_URI}"; then
  echo "Failed to run backend container '${CONTAINER_NAME}' with image '${ECR_IMAGE_URI}'." >&2
  print_container_logs "${CONTAINER_NAME}" 200
  print_docker_ps_all
  exit 1
fi

if ! container_running "${CONTAINER_NAME}"; then
  echo "Backend container '${CONTAINER_NAME}' exited immediately after start." >&2
  print_container_logs "${CONTAINER_NAME}" 200
  print_docker_ps_all
  exit 1
fi

deployed_image="$(docker inspect --format='{{ index .Config.Image }}' "${CONTAINER_NAME}")"
if [ "${deployed_image}" != "${ECR_IMAGE_URI}" ]; then
  echo "Container image mismatch. expected=${ECR_IMAGE_URI} actual=${deployed_image}" >&2
  print_container_logs "${CONTAINER_NAME}" 200
  exit 1
fi

health_check_url="${HEALTH_CHECK_URL:-http://127.0.0.1:${HOST_PORT}/actuator/health/readiness}"
health_check_attempts="${HEALTH_CHECK_ATTEMPTS:-12}"
health_check_delay_seconds="${HEALTH_CHECK_DELAY_SECONDS:-5}"
health_response_file="/tmp/bobfull-local-health-response.$$"
health_check_result="FAILED"
last_health_http_code="000"
last_health_body=""
last_curl_error=""

for attempt in $(seq 1 "${health_check_attempts}"); do
  : > "${health_response_file}"

  if curl_output="$(curl --silent --show-error \
      --output "${health_response_file}" \
      --write-out '%{http_code}' \
      "${health_check_url}" 2>&1)"; then
    last_health_http_code="${curl_output}"
    last_curl_error=""
  else
    last_health_http_code="000"
    last_curl_error="${curl_output}"
  fi

  if [ -f "${health_response_file}" ]; then
    last_health_body="$(<"${health_response_file}")"
  else
    last_health_body=""
  fi

  echo "Local health check attempt ${attempt}/${health_check_attempts}: HTTP ${last_health_http_code}"
  if [ -n "${last_health_body}" ]; then
    printf 'Local health response body:\n%s\n' "${last_health_body}"
  fi
  if [ -n "${last_curl_error}" ]; then
    printf 'Local health curl error:\n%s\n' "${last_curl_error}" >&2
  fi

  if [[ "${last_health_http_code}" =~ ^[0-9]{3}$ ]] \
      && [ "${last_health_http_code}" -ge 200 ] \
      && [ "${last_health_http_code}" -lt 400 ] \
      && [ -s "${health_response_file}" ]; then
    health_check_result="PASS HTTP ${last_health_http_code}"
    break
  fi

  if [ "${attempt}" -eq "${health_check_attempts}" ]; then
    echo "Local health check failed after ${health_check_attempts} attempts: ${health_check_url}" >&2
    echo "Last HTTP code: ${last_health_http_code}" >&2
    if [ -n "${last_health_body}" ]; then
      printf 'Last response body:\n%s\n' "${last_health_body}" >&2
    fi
    print_container_logs "${CONTAINER_NAME}" 200
    exit 1
  fi

  sleep "${health_check_delay_seconds}"
done

echo "Deployment image URI: ${ECR_IMAGE_URI}"
echo "Backend container state: $(container_state "${CONTAINER_NAME}")"
if [ "${redis_host}" = "redis" ]; then
  echo "Redis container state: $(container_state "${REDIS_CONTAINER_NAME}")"
else
  echo "Redis connection mode: external endpoint"
fi
echo "Health check result: ${health_check_result}"
