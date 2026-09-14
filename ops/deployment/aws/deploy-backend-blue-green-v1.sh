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
    echo "Missing required commands on the GitHub Actions runner:" >&2
    printf '  - %s\n' "${missing_commands[@]}" >&2
    exit 1
  fi
}

validate_non_negative_integer() {
  local key="$1"
  local value="$2"

  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "${key} must be a non-negative integer. actual=${value}" >&2
    exit 1
  fi
}

validate_positive_integer() {
  local key="$1"
  local value="$2"

  validate_non_negative_integer "${key}" "${value}"
  if [ "${value}" -eq 0 ]; then
    echo "${key} must be greater than 0. actual=${value}" >&2
    exit 1
  fi
}

load_listener_actions() {
  local output_file="$1"

  aws elbv2 describe-listeners \
    --region "${AWS_REGION}" \
    --listener-arns "${BACKEND_ALB_LISTENER_ARN}" \
    --query 'Listeners[0].DefaultActions' \
    --output json > "${output_file}"
}

read_blue_green_state() {
  python3 - "${listener_actions_file}" "${BACKEND_BLUE_TARGET_GROUP_ARN}" "${BACKEND_GREEN_TARGET_GROUP_ARN}" <<'PY'
import json
import sys

actions_path, blue_arn, green_arn = sys.argv[1:4]
with open(actions_path, encoding="utf-8") as source:
    actions = json.load(source)

forward_actions = [action for action in actions if action.get("Type") == "forward"]
if len(forward_actions) != 1:
    raise SystemExit("Listener must have exactly one forward default action.")

forward_config = forward_actions[0].get("ForwardConfig")
if not forward_config:
    raise SystemExit("Listener forward action must use ForwardConfig with blue and green target groups.")

target_groups = forward_config.get("TargetGroups", [])
if len(target_groups) != 2:
    raise SystemExit(f"Listener ForwardConfig must contain exactly 2 target groups. actual={len(target_groups)}")

weights = {}
for target_group in target_groups:
    arn = target_group.get("TargetGroupArn")
    if arn in (blue_arn, green_arn):
        weights[arn] = int(target_group.get("Weight", 1))

missing = [arn for arn in (blue_arn, green_arn) if arn not in weights]
if missing:
    raise SystemExit("Listener ForwardConfig must include both blue and green target groups.")

blue_weight = weights[blue_arn]
green_weight = weights[green_arn]
if (blue_weight, green_weight) == (100, 0):
    print(f"blue|{blue_arn}|green|{green_arn}|{blue_weight}|{green_weight}")
elif (blue_weight, green_weight) == (0, 100):
    print(f"green|{green_arn}|blue|{blue_arn}|{blue_weight}|{green_weight}")
else:
    raise SystemExit(
        f"Listener weights must be exactly 100/0 or 0/100. actual blue={blue_weight} green={green_weight}"
    )
PY
}

extract_target_instance_ids() {
  local target_group_arn="$1"

  aws elbv2 describe-target-health \
    --region "${AWS_REGION}" \
    --target-group-arn "${target_group_arn}" \
    --output json > "${target_health_file}"

  python3 - "${target_health_file}" "${EXPECTED_TARGET_COUNT}" "${BACKEND_TARGET_PORT}" <<'PY'
import json
import sys

health_path, expected_count, expected_port = sys.argv[1:4]
expected_count = int(expected_count)
expected_port = int(expected_port)
with open(health_path, encoding="utf-8") as source:
    payload = json.load(source)

descriptions = payload.get("TargetHealthDescriptions", [])
if len(descriptions) != expected_count:
    raise SystemExit(f"Target group must have exactly {expected_count} targets. actual={len(descriptions)}")

instance_ids = []
for description in descriptions:
    target = description.get("Target", {})
    instance_id = target.get("Id", "")
    port = int(target.get("Port", -1))
    if not instance_id.startswith("i-"):
        raise SystemExit(f"Target group must use EC2 instance targets. invalid target id={instance_id}")
    if port != expected_port:
        raise SystemExit(f"Target {instance_id} must use port {expected_port}. actual={port}")
    instance_ids.append(instance_id)

if len(set(instance_ids)) != expected_count:
    raise SystemExit("Target group contains duplicate instance targets.")

for instance_id in sorted(instance_ids):
    print(instance_id)
PY
}

join_by_comma() {
  local IFS=,
  printf '%s' "$*"
}

extract_instance_private_ips() {
  aws ec2 describe-instances \
    --region "${AWS_REGION}" \
    --instance-ids "$@" \
    --output json > "${ec2_instance_details_file}"

  python3 - "${ec2_instance_details_file}" "$@" <<'PY'
import json
import sys

details_path = sys.argv[1]
expected_ids = sys.argv[2:]
with open(details_path, encoding="utf-8") as source:
    payload = json.load(source)

private_ips = {}
for reservation in payload.get("Reservations", []):
    for instance in reservation.get("Instances", []):
        instance_id = instance.get("InstanceId")
        private_ip = instance.get("PrivateIpAddress")
        if instance_id:
            private_ips[instance_id] = private_ip

missing = [instance_id for instance_id in expected_ids if instance_id not in private_ips]
without_private_ip = [
    instance_id
    for instance_id in expected_ids
    if instance_id in private_ips and not private_ips.get(instance_id)
]

if missing:
    raise SystemExit("Missing EC2 instance details: " + ", ".join(sorted(missing)))
if without_private_ip:
    raise SystemExit("EC2 instances without PrivateIpAddress: " + ", ".join(sorted(without_private_ip)))

for instance_id in sorted(expected_ids):
    print(private_ips[instance_id])
PY
}

build_prometheus_target_yaml() {
  local output_file="$1"
  shift
  local target

  {
    printf -- "- targets:\n"
    for target in "$@"; do
      printf "  - '%s'\n" "${target}"
    done
  } > "${output_file}"
}

build_prometheus_update_payload() {
  local payload_file="$1"
  local targets_csv="$2"
  local target_yaml_file="$3"

  python3 - "${payload_file}" "${targets_csv}" "${target_yaml_file}" \
      "${BACKEND_MONITORING_COMPOSE_DIR}" "${BACKEND_MONITORING_ENV_FILE}" \
      "${BACKEND_PROMETHEUS_CONTAINER_NAME}" \
      "${BACKEND_PROMETHEUS_TARGET_FILE}" "${BACKEND_PROMETHEUS_PORT}" \
      "${BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS}" \
      "${BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS}" <<'PY'
import json
import shlex
import sys

(
    payload_path,
    targets_csv,
    target_yaml_path,
    compose_dir,
    env_file,
    container_name,
    prometheus_target_file,
    prometheus_port,
    target_up_timeout_seconds,
    target_up_poll_interval_seconds,
) = sys.argv[1:11]

with open(target_yaml_path, encoding="utf-8") as source:
    target_yaml = source.read().rstrip()

remote_script = """#!/usr/bin/env bash
set -euo pipefail

required_remote_env() {
  local key="$1"
  if [ -z "${!key:-}" ]; then
    echo "Missing required remote environment variable: ${key}" >&2
    exit 1
  fi
}

required_remote_env BACKEND_MONITORING_COMPOSE_DIR
required_remote_env BACKEND_MONITORING_ENV_FILE
required_remote_env BACKEND_PROMETHEUS_CONTAINER_NAME
required_remote_env BACKEND_PROMETHEUS_TARGET_FILE
required_remote_env BACKEND_PROMETHEUS_PORT
required_remote_env BACKEND_PROMETHEUS_TARGETS
required_remote_env BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS
required_remote_env BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS

if ! command -v docker >/dev/null 2>&1; then
  echo "docker command is required on Monitoring EC2." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl command is required on Monitoring EC2." >&2
  exit 1
fi

if [ ! -d "${BACKEND_MONITORING_COMPOSE_DIR}" ]; then
  echo "Monitoring compose directory not found: ${BACKEND_MONITORING_COMPOSE_DIR}" >&2
  exit 1
fi

if [ ! -f "${BACKEND_MONITORING_COMPOSE_DIR}/docker-compose.yml" ]; then
  echo "Monitoring docker-compose.yml not found: ${BACKEND_MONITORING_COMPOSE_DIR}/docker-compose.yml" >&2
  exit 1
fi

if [ ! -f "${BACKEND_MONITORING_ENV_FILE}" ]; then
  echo "Monitoring env file not found: ${BACKEND_MONITORING_ENV_FILE}" >&2
  exit 1
fi

cd "${BACKEND_MONITORING_COMPOSE_DIR}"

target_file_staging="/tmp/bobfull-backend-prometheus-targets.yml"
tmp_env=""
cleanup_remote_tmp() {
  if [ -n "${target_file_staging:-}" ]; then
    rm -f "${target_file_staging}"
  fi
  if [ -n "${tmp_env:-}" ]; then
    rm -f "${tmp_env}"
  fi
}
trap cleanup_remote_tmp EXIT

cat > "${target_file_staging}" <<'BOBFULL_PROMETHEUS_TARGETS_YAML'
__TARGET_YAML__
BOBFULL_PROMETHEUS_TARGETS_YAML

echo "New active backend metrics targets: ${BACKEND_PROMETHEUS_TARGETS}"
echo "Prometheus target file preview:"
sed 's/^/  /' "${target_file_staging}"

env_dir="${BACKEND_MONITORING_ENV_FILE%/*}"
if [ "${env_dir}" = "${BACKEND_MONITORING_ENV_FILE}" ]; then
  env_dir="."
fi
env_base="${BACKEND_MONITORING_ENV_FILE##*/}"
tmp_env="$(mktemp "${env_dir}/.${env_base}.tmp.XXXXXX")"

if grep -q '^BOBFULL_BACKEND_METRICS_TARGETS=' "${BACKEND_MONITORING_ENV_FILE}"; then
  sed "s|^BOBFULL_BACKEND_METRICS_TARGETS=.*|BOBFULL_BACKEND_METRICS_TARGETS=${BACKEND_PROMETHEUS_TARGETS}|" \
    "${BACKEND_MONITORING_ENV_FILE}" > "${tmp_env}"
else
  cat "${BACKEND_MONITORING_ENV_FILE}" > "${tmp_env}"
  printf '\\nBOBFULL_BACKEND_METRICS_TARGETS=%s\\n' "${BACKEND_PROMETHEUS_TARGETS}" >> "${tmp_env}"
fi
chmod --reference="${BACKEND_MONITORING_ENV_FILE}" "${tmp_env}" 2>/dev/null || true
chown --reference="${BACKEND_MONITORING_ENV_FILE}" "${tmp_env}" 2>/dev/null || true
mv "${tmp_env}" "${BACKEND_MONITORING_ENV_FILE}"
tmp_env=""
echo "Monitoring env file updated: ${BACKEND_MONITORING_ENV_FILE}"
echo "Monitoring env target updated: BOBFULL_BACKEND_METRICS_TARGETS=${BACKEND_PROMETHEUS_TARGETS}"

if ! docker ps --format '{{.Names}}' | grep -Fx "${BACKEND_PROMETHEUS_CONTAINER_NAME}" >/dev/null; then
  echo "Prometheus container is not running: ${BACKEND_PROMETHEUS_CONTAINER_NAME}" >&2
  exit 1
fi

docker exec -i "${BACKEND_PROMETHEUS_CONTAINER_NAME}" \
  sh -c 'tmp_file="${1}.tmp"; cat > "${tmp_file}"; mv "${tmp_file}" "$1"' sh "${BACKEND_PROMETHEUS_TARGET_FILE}" < "${target_file_staging}"
echo "Prometheus target file updated in container: ${BACKEND_PROMETHEUS_TARGET_FILE}"

curl --fail --silent --show-error \
  -X POST "http://127.0.0.1:${BACKEND_PROMETHEUS_PORT}/-/reload" >/tmp/bobfull-prometheus-reload.out
echo "Prometheus reload succeeded via /-/reload."

old_ifs="${IFS}"
IFS=','
read -r -a expected_targets <<< "${BACKEND_PROMETHEUS_TARGETS}"
IFS="${old_ifs}"

deadline=$((SECONDS + BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS))
while true; do
  all_up=true
  checked_count=0

  for raw_target in "${expected_targets[@]}"; do
    target="$(printf '%s' "${raw_target}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    if [ -z "${target}" ]; then
      continue
    fi

    checked_count=$((checked_count + 1))
    query="up{job=\\"bobfull-backend\\",instance=\\"${target}\\"}"
    if response="$(curl --fail --silent --show-error --get \
        --data-urlencode "query=${query}" \
        "http://127.0.0.1:${BACKEND_PROMETHEUS_PORT}/api/v1/query" 2>&1)" \
        && printf '%s' "${response}" | grep -F '"status":"success"' >/dev/null \
        && printf '%s' "${response}" | grep -F "\\"instance\\":\\"${target}\\"" >/dev/null \
        && printf '%s' "${response}" | grep -F '"value":[' >/dev/null \
        && printf '%s' "${response}" | grep -F '"1"' >/dev/null; then
      echo "Prometheus target state target=${target} state=UP response=${response}"
    else
      all_up=false
      echo "Prometheus target state target=${target} state=DOWN response=${response:-curl/query failed}"
    fi
  done

  if [ "${checked_count}" -eq 0 ]; then
    echo "No Prometheus targets were provided for UP verification." >&2
    exit 1
  fi

  if [ "${all_up}" = "true" ]; then
    echo "Prometheus target update completed: ${BACKEND_PROMETHEUS_TARGETS}"
    exit 0
  fi

  if [ "${SECONDS}" -ge "${deadline}" ]; then
    echo "Prometheus targets did not all become UP before timeout." >&2
    exit 1
  fi

  sleep "${BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS}"
done
""".replace("__TARGET_YAML__", target_yaml)

command_env = {
    "BACKEND_MONITORING_COMPOSE_DIR": compose_dir,
    "BACKEND_MONITORING_ENV_FILE": env_file,
    "BACKEND_PROMETHEUS_CONTAINER_NAME": container_name,
    "BACKEND_PROMETHEUS_TARGET_FILE": prometheus_target_file,
    "BACKEND_PROMETHEUS_PORT": prometheus_port,
    "BACKEND_PROMETHEUS_TARGETS": targets_csv,
    "BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS": target_up_timeout_seconds,
    "BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS": target_up_poll_interval_seconds,
}
env_prefix = " ".join(f"{key}={shlex.quote(value)}" for key, value in command_env.items())

commands = [
    "trap 'rm -f /tmp/bobfull-prometheus-target-update.sh' EXIT",
    "cat > /tmp/bobfull-prometheus-target-update.sh <<'BOBFULL_PROMETHEUS_UPDATE_SCRIPT'\n"
    f"{remote_script}\n"
    "BOBFULL_PROMETHEUS_UPDATE_SCRIPT",
    "chmod 700 /tmp/bobfull-prometheus-target-update.sh",
    f"{env_prefix} bash /tmp/bobfull-prometheus-target-update.sh",
]

with open(payload_path, "w", encoding="utf-8") as output:
    json.dump({"commands": commands}, output)
PY
}

wait_prometheus_update_command() {
  local command_id="$1"
  local deadline=$((SECONDS + BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS))
  local status

  while true; do
    status="$(
      aws ssm get-command-invocation \
        --region "${AWS_REGION}" \
        --command-id "${command_id}" \
        --instance-id "${BACKEND_MONITORING_EC2_INSTANCE_ID}" \
        --query 'Status' \
        --output text 2>/dev/null || true
    )"

    case "${status}" in
      Success)
        echo "Prometheus target update SSM command status: Success"
        return 0
        ;;
      Pending|InProgress|Delayed|"")
        echo "Prometheus target update SSM command status: waiting"
        ;;
      *)
        echo "Prometheus target update SSM command status: ${status}" >&2
        return 1
        ;;
    esac

    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "Prometheus target update SSM command timed out." >&2
      return 1
    fi

    sleep "${BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS}"
  done
}

print_prometheus_update_command_output() {
  local command_id="$1"
  local standard_error

  echo "----- Prometheus target update SSM stdout ${BACKEND_MONITORING_EC2_INSTANCE_ID} -----"
  aws ssm get-command-invocation \
    --region "${AWS_REGION}" \
    --command-id "${command_id}" \
    --instance-id "${BACKEND_MONITORING_EC2_INSTANCE_ID}" \
    --query 'StandardOutputContent' \
    --output text || true

  echo "----- Prometheus target update SSM stderr ${BACKEND_MONITORING_EC2_INSTANCE_ID} -----" >&2
  standard_error="$(
    aws ssm get-command-invocation \
      --region "${AWS_REGION}" \
      --command-id "${command_id}" \
      --instance-id "${BACKEND_MONITORING_EC2_INSTANCE_ID}" \
      --query 'StandardErrorContent' \
      --output text 2>/dev/null || true
  )"

  if [ "${standard_error}" != "None" ] && [ -n "${standard_error}" ]; then
    echo "${standard_error}" >&2
  fi
}

update_prometheus_targets() {
  local targets_csv="$1"
  shift
  local metric_targets=("$@")
  local command_id

  echo "Updating Prometheus bobfull-backend targets on Monitoring EC2: ${BACKEND_MONITORING_EC2_INSTANCE_ID}"
  echo "New active EC2 metrics targets: ${targets_csv}"
  build_prometheus_target_yaml "${prometheus_target_yaml_file}" "${metric_targets[@]}"
  build_prometheus_update_payload "${prometheus_command_payload_file}" "${targets_csv}" "${prometheus_target_yaml_file}"

  wait_ssm_online "${BACKEND_MONITORING_EC2_INSTANCE_ID}"

  command_id="$(
    aws ssm send-command \
      --region "${AWS_REGION}" \
      --instance-ids "${BACKEND_MONITORING_EC2_INSTANCE_ID}" \
      --document-name "${BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME}" \
      --comment "Update bobfull backend Prometheus targets" \
      --parameters "file://${prometheus_command_payload_file}" \
      --query 'Command.CommandId' \
      --output text
  )"

  echo "Prometheus target update SSM command id: ${command_id}"
  if wait_prometheus_update_command "${command_id}"; then
    print_prometheus_update_command_output "${command_id}"
    return 0
  fi

  print_prometheus_update_command_output "${command_id}"
  return 1
}

describe_instance_states() {
  aws ec2 describe-instances \
    --region "${AWS_REGION}" \
    --instance-ids "$@" \
    --output json > "${ec2_instance_state_file}"
}

instance_state_summary_from_file() {
  python3 - "${ec2_instance_state_file}" "$@" <<'PY'
import json
import sys

states_path = sys.argv[1]
expected_ids = sys.argv[2:]
with open(states_path, encoding="utf-8") as source:
    payload = json.load(source)

states = {}
for reservation in payload.get("Reservations", []):
    for instance in reservation.get("Instances", []):
        states[instance.get("InstanceId")] = instance.get("State", {}).get("Name", "unknown")

summary = ", ".join(
    f"{instance_id}:{states.get(instance_id, 'missing')}"
    for instance_id in sorted(expected_ids)
)
print(summary)
PY
}

instance_ids_by_state_from_file() {
  local expected_state="$1"
  shift

  python3 - "${ec2_instance_state_file}" "${expected_state}" "$@" <<'PY'
import json
import sys

states_path = sys.argv[1]
expected_state = sys.argv[2]
expected_ids = sys.argv[3:]
with open(states_path, encoding="utf-8") as source:
    payload = json.load(source)

states = {}
for reservation in payload.get("Reservations", []):
    for instance in reservation.get("Instances", []):
        states[instance.get("InstanceId")] = instance.get("State", {}).get("Name", "unknown")

for instance_id in sorted(expected_ids):
    if states.get(instance_id, "missing") == expected_state:
        print(instance_id)
PY
}

all_instance_states_match_from_file() {
  local expected_state="$1"
  shift

  python3 - "${ec2_instance_state_file}" "${expected_state}" "$@" <<'PY'
import json
import sys

states_path = sys.argv[1]
expected_state = sys.argv[2]
expected_ids = sys.argv[3:]
with open(states_path, encoding="utf-8") as source:
    payload = json.load(source)

states = {}
for reservation in payload.get("Reservations", []):
    for instance in reservation.get("Instances", []):
        states[instance.get("InstanceId")] = instance.get("State", {}).get("Name", "unknown")

if any(states.get(instance_id, "missing") != expected_state for instance_id in expected_ids):
    raise SystemExit(1)
PY
}

unexpected_instance_states_from_file() {
  local allowed_states_csv="$1"
  shift

  python3 - "${ec2_instance_state_file}" "${allowed_states_csv}" "$@" <<'PY'
import json
import sys

states_path = sys.argv[1]
allowed_states = set(sys.argv[2].split(","))
expected_ids = sys.argv[3:]
with open(states_path, encoding="utf-8") as source:
    payload = json.load(source)

states = {}
for reservation in payload.get("Reservations", []):
    for instance in reservation.get("Instances", []):
        states[instance.get("InstanceId")] = instance.get("State", {}).get("Name", "unknown")

unexpected = [
    f"{instance_id}:{states.get(instance_id, 'missing')}"
    for instance_id in expected_ids
    if states.get(instance_id, "missing") not in allowed_states
]

if unexpected:
    print(", ".join(unexpected))
PY
}

wait_stopping_instances_stopped() {
  local instance_ids=("$@")
  local deadline=$((SECONDS + BACKEND_EC2_STATE_TIMEOUT_SECONDS))
  local stopping_ids=()
  local state_summary
  local unexpected_states

  while true; do
    describe_instance_states "${instance_ids[@]}"
    state_summary="$(instance_state_summary_from_file "${instance_ids[@]}")"
    unexpected_states="$(unexpected_instance_states_from_file "stopped,running,pending,stopping" "${instance_ids[@]}")"
    if [ -n "${unexpected_states}" ]; then
      echo "Inactive EC2 instances include unsupported state: ${unexpected_states}" >&2
      return 1
    fi

    mapfile -t stopping_ids < <(instance_ids_by_state_from_file stopping "${instance_ids[@]}")
    if [ "${#stopping_ids[@]}" -eq 0 ]; then
      echo "Inactive EC2 startable states confirmed: ${state_summary}"
      return 0
    fi

    echo "Inactive EC2 instances are stopping: ${state_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "Inactive EC2 instances did not leave stopping state before timeout." >&2
      return 1
    fi

    sleep "${BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS}"
  done
}

start_stopped_instances() {
  local instance_ids=("$@")
  local stopped_ids=()

  describe_instance_states "${instance_ids[@]}"
  mapfile -t stopped_ids < <(instance_ids_by_state_from_file stopped "${instance_ids[@]}")

  if [ "${#stopped_ids[@]}" -eq 0 ]; then
    echo "No inactive EC2 instances are stopped. Start command is not needed."
    return 0
  fi

  printf 'Starting inactive EC2 instances: %s\n' "${stopped_ids[*]}"
  aws ec2 start-instances \
    --region "${AWS_REGION}" \
    --instance-ids "${stopped_ids[@]}" >/dev/null
}

wait_instances_running() {
  local label="$1"
  shift
  local instance_ids=("$@")
  local deadline=$((SECONDS + BACKEND_EC2_STATE_TIMEOUT_SECONDS))
  local state_summary
  local unexpected_states

  while true; do
    describe_instance_states "${instance_ids[@]}"
    state_summary="$(instance_state_summary_from_file "${instance_ids[@]}")"
    if all_instance_states_match_from_file running "${instance_ids[@]}"; then
      echo "${label} EC2 instances are running: ${state_summary}"
      return 0
    fi

    unexpected_states="$(unexpected_instance_states_from_file "running,pending,stopped" "${instance_ids[@]}")"
    if [ -n "${unexpected_states}" ]; then
      echo "${label} EC2 instances include unexpected state while waiting for running: ${unexpected_states}" >&2
      return 1
    fi

    echo "${label} EC2 running wait pending: ${state_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "${label} EC2 instances did not become running before timeout." >&2
      return 1
    fi

    sleep "${BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS}"
  done
}

wait_instances_stopped() {
  local label="$1"
  shift
  local instance_ids=("$@")
  local deadline=$((SECONDS + BACKEND_EC2_STATE_TIMEOUT_SECONDS))
  local state_summary
  local unexpected_states

  while true; do
    describe_instance_states "${instance_ids[@]}"
    state_summary="$(instance_state_summary_from_file "${instance_ids[@]}")"
    if all_instance_states_match_from_file stopped "${instance_ids[@]}"; then
      echo "${label} EC2 instances are stopped: ${state_summary}"
      return 0
    fi

    unexpected_states="$(unexpected_instance_states_from_file "running,stopping,stopped" "${instance_ids[@]}")"
    if [ -n "${unexpected_states}" ]; then
      echo "${label} EC2 instances include unexpected state while waiting for stopped: ${unexpected_states}" >&2
      return 1
    fi

    echo "${label} EC2 stop wait pending: ${state_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "${label} EC2 instances did not become stopped before timeout." >&2
      return 1
    fi

    sleep "${BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS}"
  done
}

ensure_inactive_instances_running() {
  wait_stopping_instances_stopped "$@"
  start_stopped_instances "$@"
  wait_instances_running inactive "$@"
}

validate_ssm_online() {
  local instance_ids_csv

  instance_ids_csv="$(join_by_comma "$@")"
  aws ssm describe-instance-information \
    --region "${AWS_REGION}" \
    --filters "Key=InstanceIds,Values=${instance_ids_csv}" \
    --output json > "${ssm_info_file}"

  python3 - "${ssm_info_file}" "$@" <<'PY'
import json
import sys

info_path = sys.argv[1]
expected_ids = sys.argv[2:]
with open(info_path, encoding="utf-8") as source:
    payload = json.load(source)

instances = {
    item.get("InstanceId"): item.get("PingStatus")
    for item in payload.get("InstanceInformationList", [])
}
missing = [instance_id for instance_id in expected_ids if instance_id not in instances]
offline = [
    f"{instance_id}:{instances.get(instance_id)}"
    for instance_id in expected_ids
    if instance_id in instances and instances.get(instance_id) != "Online"
]
summary = ", ".join(
    f"{instance_id}:{instances.get(instance_id, 'missing')}"
    for instance_id in sorted(expected_ids)
)
print(summary)

if missing or offline:
    if missing:
        print("Missing SSM managed instances: " + ", ".join(missing), file=sys.stderr)
    if offline:
        print("SSM instances not Online: " + ", ".join(offline), file=sys.stderr)
    raise SystemExit(1)
PY
}

wait_ssm_online() {
  local deadline=$((SECONDS + BACKEND_SSM_ONLINE_TIMEOUT_SECONDS))
  local ssm_summary

  while true; do
    if ssm_summary="$(validate_ssm_online "$@" 2>&1)"; then
      echo "All target instances are SSM managed and Online: ${ssm_summary}"
      return 0
    fi

    echo "SSM Online wait pending: ${ssm_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "Target instances did not become SSM Online before timeout." >&2
      return 1
    fi

    sleep "${BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS}"
  done
}

target_group_health_is_healthy() {
  local target_group_arn="$1"
  shift

  aws elbv2 describe-target-health \
    --region "${AWS_REGION}" \
    --target-group-arn "${target_group_arn}" \
    --output json > "${target_health_file}"

  python3 - "${target_health_file}" "$@" <<'PY'
import json
import sys

health_path = sys.argv[1]
expected_ids = set(sys.argv[2:])
with open(health_path, encoding="utf-8") as source:
    payload = json.load(source)

descriptions = payload.get("TargetHealthDescriptions", [])
states = {
    description.get("Target", {}).get("Id"): description.get("TargetHealth", {}).get("State")
    for description in descriptions
}
summary = ", ".join(f"{instance_id}:{states.get(instance_id, 'missing')}" for instance_id in sorted(expected_ids))
print(summary)

if set(states) != expected_ids:
    raise SystemExit(1)
if any(states.get(instance_id) != "healthy" for instance_id in expected_ids):
    raise SystemExit(1)
PY
}

wait_target_group_healthy() {
  local target_group_arn="$1"
  shift
  local deadline=$((SECONDS + BACKEND_TG_HEALTH_TIMEOUT_SECONDS))
  local health_summary

  while true; do
    if health_summary="$(target_group_health_is_healthy "${target_group_arn}" "$@" 2>&1)"; then
      echo "Target group healthy: ${health_summary}"
      return 0
    fi

    echo "Target group health pending: ${health_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      echo "Target group did not become healthy before timeout: ${target_group_arn}" >&2
      return 1
    fi

    sleep "${BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS}"
  done
}

build_switch_actions() {
  python3 - "${listener_actions_file}" "${switch_actions_file}" "${active_target_group_arn}" "${inactive_target_group_arn}" <<'PY'
import json
import sys

source_path, output_path, active_arn, inactive_arn = sys.argv[1:5]
with open(source_path, encoding="utf-8") as source:
    actions = json.load(source)

for action in actions:
    if action.get("Type") != "forward":
        continue
    for target_group in action["ForwardConfig"]["TargetGroups"]:
        arn = target_group.get("TargetGroupArn")
        if arn == active_arn:
            target_group["Weight"] = 0
        elif arn == inactive_arn:
            target_group["Weight"] = 100

with open(output_path, "w", encoding="utf-8") as output:
    json.dump(actions, output, separators=(",", ":"))
PY
}

listener_weights_match() {
  local expected_active_weight="$1"
  local expected_inactive_weight="$2"

  load_listener_actions "${current_listener_actions_file}"
  python3 - "${current_listener_actions_file}" "${active_target_group_arn}" "${inactive_target_group_arn}" \
      "${expected_active_weight}" "${expected_inactive_weight}" <<'PY'
import json
import sys

actions_path, active_arn, inactive_arn, expected_active_weight, expected_inactive_weight = sys.argv[1:6]
expected_active_weight = int(expected_active_weight)
expected_inactive_weight = int(expected_inactive_weight)
with open(actions_path, encoding="utf-8") as source:
    actions = json.load(source)

forward_actions = [action for action in actions if action.get("Type") == "forward"]
if len(forward_actions) != 1:
    raise SystemExit(1)

weights = {
    target_group.get("TargetGroupArn"): int(target_group.get("Weight", 1))
    for target_group in forward_actions[0].get("ForwardConfig", {}).get("TargetGroups", [])
}
actual_active_weight = weights.get(active_arn)
actual_inactive_weight = weights.get(inactive_arn)
print(f"active={actual_active_weight} inactive={actual_inactive_weight}")

if actual_active_weight != expected_active_weight or actual_inactive_weight != expected_inactive_weight:
    raise SystemExit(1)
PY
}

wait_listener_weights() {
  local expected_active_weight="$1"
  local expected_inactive_weight="$2"
  local deadline=$((SECONDS + BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS))
  local weight_summary

  while true; do
    if weight_summary="$(listener_weights_match "${expected_active_weight}" "${expected_inactive_weight}" 2>&1)"; then
      echo "Listener weights confirmed: ${weight_summary}"
      return 0
    fi

    echo "Listener weight confirmation pending: ${weight_summary}"
    if [ "${SECONDS}" -ge "${deadline}" ]; then
      return 1
    fi

    sleep "${BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS}"
  done
}

verify_previous_active_stop_allowed() {
  local load_output
  local guard_output

  if ! load_output="$(load_listener_actions "${current_listener_actions_file}" 2>&1)"; then
    echo "Current Blue/Green Listener weight: unavailable"
    echo "Previous active EC2 STOP allowed: false"
    echo "STOP skip reason: failed to reload ALB Listener state before previous active STOP. ${load_output}"
    return 1
  fi

  if guard_output="$(
      python3 - "${current_listener_actions_file}" \
          "${BACKEND_BLUE_TARGET_GROUP_ARN}" "${BACKEND_GREEN_TARGET_GROUP_ARN}" \
          "${active_target_group_arn}" "${inactive_target_group_arn}" \
          "${active_color}" "${inactive_color}" <<'PY'
import json
import sys

(
    actions_path,
    blue_arn,
    green_arn,
    previous_active_arn,
    new_active_arn,
    previous_active_color,
    new_active_color,
) = sys.argv[1:8]

with open(actions_path, encoding="utf-8") as source:
    actions = json.load(source)

forward_actions = [action for action in actions if action.get("Type") == "forward"]
if len(forward_actions) != 1:
    print("Current Blue/Green Listener weight: unavailable")
    print("Previous active EC2 STOP allowed: false")
    raise SystemExit("STOP skip reason: Listener must have exactly one forward default action.")

target_groups = forward_actions[0].get("ForwardConfig", {}).get("TargetGroups", [])
weights = {
    target_group.get("TargetGroupArn"): int(target_group.get("Weight", 1))
    for target_group in target_groups
}

missing = [
    name
    for name, arn in (
        ("blue", blue_arn),
        ("green", green_arn),
        ("previous active", previous_active_arn),
        ("new active", new_active_arn),
    )
    if arn not in weights
]
if missing:
    print("Current Blue/Green Listener weight: unavailable")
    print("Previous active EC2 STOP allowed: false")
    raise SystemExit("STOP skip reason: Listener ForwardConfig is missing " + ", ".join(missing) + " target group.")

blue_weight = weights[blue_arn]
green_weight = weights[green_arn]
previous_active_weight = weights[previous_active_arn]
new_active_weight = weights[new_active_arn]

print(f"Current Blue/Green Listener weight: blue={blue_weight} green={green_weight}")
print(
    "Previous/New active Listener weight: "
    f"previous_{previous_active_color}={previous_active_weight} "
    f"new_{new_active_color}={new_active_weight}"
)

if new_active_weight == 100 and previous_active_weight == 0:
    print("Previous active EC2 STOP allowed: true")
    raise SystemExit(0)

print("Previous active EC2 STOP allowed: false")
raise SystemExit(
    "STOP skip reason: expected new active target group weight 100 "
    f"and previous active target group weight 0, actual new={new_active_weight} previous={previous_active_weight}."
)
PY
    2>&1)"; then
    printf '%s\n' "${guard_output}"
    return 0
  fi

  printf '%s\n' "${guard_output}"
  echo "Skipping previous active EC2 STOP because Listener weight revalidation did not allow STOP."
  return 1
}

rollback_listener() {
  echo "Rolling back ALB listener to the previous default actions." >&2
  aws elbv2 modify-listener \
    --region "${AWS_REGION}" \
    --listener-arn "${BACKEND_ALB_LISTENER_ARN}" \
    --default-actions "file://${listener_actions_file}" >/dev/null

  if wait_listener_weights 100 0; then
    echo "Rollback confirmed: active target group restored to weight 100."
  else
    echo "Rollback command completed, but listener weights were not confirmed." >&2
    return 1
  fi
}

verify_public_url() {
  local label="$1"
  local url="$2"
  local response_file="${tmp_dir}/public-${label}.txt"
  local attempt
  local http_code
  local curl_output

  for ((attempt = 1; attempt <= BACKEND_PUBLIC_VERIFY_ATTEMPTS; attempt++)); do
    : > "${response_file}"
    if curl_output="$(curl --silent --show-error --location \
        --max-time "${BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS}" \
        --output "${response_file}" \
        --write-out '%{http_code}' \
        "${url}" 2>&1)"; then
      http_code="${curl_output}"
    else
      http_code="000"
      echo "Public ${label} check attempt ${attempt}/${BACKEND_PUBLIC_VERIFY_ATTEMPTS} curl error: ${curl_output}" >&2
    fi

    echo "Public ${label} check attempt ${attempt}/${BACKEND_PUBLIC_VERIFY_ATTEMPTS}: HTTP ${http_code}"
    if [[ "${http_code}" =~ ^[0-9]{3}$ ]] \
        && [ "${http_code}" -ge 200 ] \
        && [ "${http_code}" -lt 400 ] \
        && [ -s "${response_file}" ]; then
      return 0
    fi

    if [ "${attempt}" -lt "${BACKEND_PUBLIC_VERIFY_ATTEMPTS}" ]; then
      sleep "${BACKEND_PUBLIC_VERIFY_DELAY_SECONDS}"
    fi
  done

  echo "Public ${label} verification failed: ${url}" >&2
  return 1
}

keep_previous_active_environment() {
  if [ "${BACKEND_PREVIOUS_ENV_KEEP_SECONDS}" -eq 0 ]; then
    echo "Previous active EC2 keep time is 0 seconds. Stopping previous active instances immediately."
    return 0
  fi

  echo "Keeping previous active EC2 instances running for rollback window: ${BACKEND_PREVIOUS_ENV_KEEP_SECONDS}s"
  sleep "${BACKEND_PREVIOUS_ENV_KEEP_SECONDS}"
}

stop_previous_active_instances() {
  printf 'Stopping previous active EC2 instances captured before switch: %s\n' "$*"
  aws ec2 stop-instances \
    --region "${AWS_REGION}" \
    --instance-ids "$@" >/dev/null

  wait_instances_stopped "previous active" "$@"
}

cleanup() {
  if [ -n "${tmp_dir:-}" ] && [ -d "${tmp_dir}" ]; then
    rm -rf "${tmp_dir}" || true
  fi
}

trap cleanup EXIT

required_env AWS_REGION
required_env ECR_IMAGE_URI
required_env PARAMETER_PREFIX
required_env BACKEND_ALB_LISTENER_ARN
required_env BACKEND_BLUE_TARGET_GROUP_ARN
required_env BACKEND_GREEN_TARGET_GROUP_ARN
required_env BACKEND_PUBLIC_READINESS_URL
required_env BACKEND_PUBLIC_API_VERIFY_URL
required_env BACKEND_MONITORING_EC2_INSTANCE_ID
required_env BACKEND_MONITORING_COMPOSE_DIR
required_env BACKEND_MONITORING_ENV_FILE

require_commands aws bash curl python3 mktemp

EXPECTED_TARGET_COUNT=2
BACKEND_TARGET_PORT="${BACKEND_TARGET_PORT:-8080}"
BACKEND_TG_HEALTH_TIMEOUT_SECONDS="${BACKEND_TG_HEALTH_TIMEOUT_SECONDS:-300}"
BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS="${BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS:-10}"
BACKEND_PUBLIC_VERIFY_ATTEMPTS="${BACKEND_PUBLIC_VERIFY_ATTEMPTS:-6}"
BACKEND_PUBLIC_VERIFY_DELAY_SECONDS="${BACKEND_PUBLIC_VERIFY_DELAY_SECONDS:-10}"
BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS="${BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS:-10}"
BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS="${BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS:-60}"
BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS="${BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS:-3}"
BACKEND_EC2_STATE_TIMEOUT_SECONDS="${BACKEND_EC2_STATE_TIMEOUT_SECONDS:-300}"
BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS="${BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS:-10}"
BACKEND_SSM_ONLINE_TIMEOUT_SECONDS="${BACKEND_SSM_ONLINE_TIMEOUT_SECONDS:-300}"
BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS="${BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS:-10}"
BACKEND_PREVIOUS_ENV_KEEP_SECONDS="${BACKEND_PREVIOUS_ENV_KEEP_SECONDS:-600}"
BACKEND_PROMETHEUS_CONTAINER_NAME="${BACKEND_PROMETHEUS_CONTAINER_NAME:-bobfull-prometheus}"
BACKEND_PROMETHEUS_TARGET_FILE="${BACKEND_PROMETHEUS_TARGET_FILE:-/tmp/prometheus-targets/bobfull-backend.yml}"
BACKEND_PROMETHEUS_PORT="${BACKEND_PROMETHEUS_PORT:-9090}"
BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS="${BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS:-180}"
BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS="${BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS:-10}"
BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME="${BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME:-AWS-RunShellScript}"
BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS="${BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS:-300}"
BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS="${BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS:-3}"

validate_positive_integer BACKEND_TARGET_PORT "${BACKEND_TARGET_PORT}"
validate_non_negative_integer BACKEND_TG_HEALTH_TIMEOUT_SECONDS "${BACKEND_TG_HEALTH_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS "${BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS}"
validate_positive_integer BACKEND_PUBLIC_VERIFY_ATTEMPTS "${BACKEND_PUBLIC_VERIFY_ATTEMPTS}"
validate_positive_integer BACKEND_PUBLIC_VERIFY_DELAY_SECONDS "${BACKEND_PUBLIC_VERIFY_DELAY_SECONDS}"
validate_non_negative_integer BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS "${BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS}"
validate_non_negative_integer BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS "${BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS "${BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS}"
validate_non_negative_integer BACKEND_EC2_STATE_TIMEOUT_SECONDS "${BACKEND_EC2_STATE_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS "${BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS}"
validate_non_negative_integer BACKEND_SSM_ONLINE_TIMEOUT_SECONDS "${BACKEND_SSM_ONLINE_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS "${BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS}"
validate_non_negative_integer BACKEND_PREVIOUS_ENV_KEEP_SECONDS "${BACKEND_PREVIOUS_ENV_KEEP_SECONDS}"
validate_positive_integer BACKEND_PROMETHEUS_PORT "${BACKEND_PROMETHEUS_PORT}"
validate_non_negative_integer BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS "${BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS "${BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS}"
validate_non_negative_integer BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS "${BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS}"
validate_positive_integer BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS "${BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS}"

tmp_dir="$(mktemp -d)"
listener_actions_file="${tmp_dir}/listener-actions-before.json"
current_listener_actions_file="${tmp_dir}/listener-actions-current.json"
switch_actions_file="${tmp_dir}/listener-actions-switch.json"
target_health_file="${tmp_dir}/target-health.json"
ssm_info_file="${tmp_dir}/ssm-info.json"
ec2_instance_state_file="${tmp_dir}/ec2-instance-states.json"
ec2_instance_details_file="${tmp_dir}/ec2-instance-details.json"
prometheus_target_yaml_file="${tmp_dir}/prometheus-targets.yml"
prometheus_command_payload_file="${tmp_dir}/prometheus-command-payload.json"

load_listener_actions "${listener_actions_file}"
IFS='|' read -r active_color active_target_group_arn inactive_color inactive_target_group_arn blue_weight green_weight \
  < <(read_blue_green_state)

echo "Blue target group weight: ${blue_weight}"
echo "Green target group weight: ${green_weight}"
echo "Active target group: ${active_color} ${active_target_group_arn}"
echo "Inactive target group: ${inactive_color} ${inactive_target_group_arn}"

mapfile -t active_instance_ids < <(extract_target_instance_ids "${active_target_group_arn}")
if [ "${#active_instance_ids[@]}" -ne "${EXPECTED_TARGET_COUNT}" ]; then
  echo "Active target group must resolve to exactly ${EXPECTED_TARGET_COUNT} EC2 instance ids." >&2
  exit 1
fi

mapfile -t inactive_instance_ids < <(extract_target_instance_ids "${inactive_target_group_arn}")
if [ "${#inactive_instance_ids[@]}" -ne "${EXPECTED_TARGET_COUNT}" ]; then
  echo "Inactive target group must resolve to exactly ${EXPECTED_TARGET_COUNT} EC2 instance ids." >&2
  exit 1
fi

printf 'Active target instances captured before switch: %s\n' "${active_instance_ids[*]}"
printf 'Inactive target instances: %s\n' "${inactive_instance_ids[*]}"
ensure_inactive_instances_running "${inactive_instance_ids[@]}"
wait_ssm_online "${inactive_instance_ids[@]}"

BACKEND_EC2_INSTANCE_IDS="${inactive_instance_ids[*]}" \
  bash ops/deployment/aws/run-ssm-backend-deploy-v1.sh

wait_target_group_healthy "${inactive_target_group_arn}" "${inactive_instance_ids[@]}"

build_switch_actions
echo "Switching ALB listener traffic: ${active_color}=0 ${inactive_color}=100"
if ! aws elbv2 modify-listener \
    --region "${AWS_REGION}" \
    --listener-arn "${BACKEND_ALB_LISTENER_ARN}" \
    --default-actions "file://${switch_actions_file}" >/dev/null; then
  echo "Failed to switch ALB listener traffic. Active target group remains unchanged." >&2
  exit 1
fi

if ! wait_listener_weights 0 100; then
  echo "ALB listener traffic switch was not confirmed; starting rollback." >&2
  rollback_listener
  exit 1
fi

if ! verify_public_url readiness "${BACKEND_PUBLIC_READINESS_URL}"; then
  rollback_listener
  exit 1
fi

if ! verify_public_url api "${BACKEND_PUBLIC_API_VERIFY_URL}"; then
  rollback_listener
  exit 1
fi

mapfile -t new_active_instance_ids < <(extract_target_instance_ids "${inactive_target_group_arn}")
if [ "${#new_active_instance_ids[@]}" -ne "${EXPECTED_TARGET_COUNT}" ]; then
  echo "New active target group must resolve to exactly ${EXPECTED_TARGET_COUNT} EC2 instance ids." >&2
  exit 1
fi
printf 'New active target instances after switch: %s\n' "${new_active_instance_ids[*]}"

mapfile -t new_active_private_ips < <(extract_instance_private_ips "${new_active_instance_ids[@]}")
if [ "${#new_active_private_ips[@]}" -ne "${EXPECTED_TARGET_COUNT}" ]; then
  echo "New active EC2 private IP lookup must return exactly ${EXPECTED_TARGET_COUNT} IPs." >&2
  exit 1
fi
printf 'New active EC2 private IPs: %s\n' "${new_active_private_ips[*]}"

new_active_metric_targets=()
for private_ip in "${new_active_private_ips[@]}"; do
  new_active_metric_targets+=("${private_ip}:${BACKEND_TARGET_PORT}")
done
new_active_metric_targets_csv="$(join_by_comma "${new_active_metric_targets[@]}")"

if ! update_prometheus_targets "${new_active_metric_targets_csv}" "${new_active_metric_targets[@]}"; then
  echo "Prometheus target update or UP verification failed. Previous active EC2 instances will remain running." >&2
  exit 1
fi

keep_previous_active_environment
if verify_previous_active_stop_allowed; then
  stop_previous_active_instances "${active_instance_ids[@]}"
else
  echo "Previous active EC2 STOP skipped. Workflow completed safely without stopping cleanup targets."
  exit 0
fi

echo "Blue-Green deployment completed. New active target group: ${inactive_color} ${inactive_target_group_arn}"
