# Prometheus·Grafana 모니터링 운영 절차

## 구성 기준

- Prometheus와 Grafana는 애플리케이션 EC2와 분리된 Monitoring EC2 1대에서 Docker Compose로 실행한다.
- Prometheus는 App EC2의 `/actuator/prometheus`를 scrape한다.
- Grafana는 Prometheus Data Source, BobFull Overview Dashboard, 초기 Grafana Alert Rule, Slack Contact Point를 provisioning으로 로드한다.
- Slack으로 발송되는 운영 Alert의 기준은 Grafana Alert Rule이다. Prometheus는 Spring Boot 메트릭 scrape와 Grafana Data Source 역할만 맡는다.
- Slack Webhook URL, Grafana 관리자 비밀번호 같은 비밀값은 `BACKEND_MONITORING_ENV_FILE` 경로의 env 파일 또는 운영 비밀 저장소에만 둔다.

## 네트워크

1. App EC2 Security Group은 Monitoring EC2의 private IP 또는 Security Group에서 `8080` 접근을 허용한다.
2. Monitoring EC2 Security Group은 운영자 접근용 `3000`(Grafana)과 필요 시 `9090`(Prometheus)을 제한된 IP에서만 허용한다.
3. Prometheus Target은 public IP가 아니라 App EC2 private IP 또는 private DNS를 사용한다.

## 배포

```bash
cp /opt/bobfull-monitoring/repo/ops/monitoring/.env.example /opt/bobfull-monitoring/.env
vi /opt/bobfull-monitoring/.env
docker compose \
  --env-file /opt/bobfull-monitoring/.env \
  -f /opt/bobfull-monitoring/repo/ops/monitoring/docker-compose.yml \
  up -d
```

필수 값:

```text
BOBFULL_BACKEND_METRICS_TARGETS=<active-app-ec2-private-ip-1>:8080,<active-app-ec2-private-ip-2>:8080
GRAFANA_ADMIN_PASSWORD=<strong-password>
GRAFANA_SLACK_WEBHOOK_URL=<slack-incoming-webhook-url>
GRAFANA_SLACK_RECIPIENT=<slack-channel-name>
```

`BOBFULL_BACKEND_METRICS_TARGET`는 기존 단일 App EC2 측정용 대체값이다. `BOBFULL_BACKEND_METRICS_TARGETS`가 비어 있을 때만 사용한다.

## Blue/Green Active App 2대 측정 설정

#191 기준선 측정은 현재 ALB Listener weight가 100인 Active Target Group의 App EC2 2대만 대상으로 한다. Inactive 환경까지 함께 scrape하면 HTTP RPS, latency, Hikari 지표가 섞이므로 같은 job에 넣지 않는다. Inactive 측정이 필요하면 별도 job 또는 label로 분리한다.

ALB DNS를 Prometheus target으로 사용하지 않는다. Prometheus target은 App EC2 private IP 또는 private DNS와 `8080` 포트를 직접 지정해야 instance별 지표를 분리할 수 있다.

Monitoring EC2 최초 구성 때는 env 파일에 현재 Active 2대의 초기값을 한 번 입력한다. 이후 Blue-Green 배포에서는 배포 스크립트가 Prometheus target을 자동 전환하므로, 평상시 운영 절차로 사람이 env 파일의 target을 직접 바꾸지 않는다. 실제 운영 구조에서 env 파일과 compose/config 경로가 분리되어 있으면 env 파일은 `BACKEND_MONITORING_ENV_FILE`, compose/config 위치는 `BACKEND_MONITORING_COMPOSE_DIR`로 각각 지정한다.

운영 경로 예시:

```text
BACKEND_MONITORING_ENV_FILE=/opt/bobfull-monitoring/.env
BACKEND_MONITORING_COMPOSE_DIR=/opt/bobfull-monitoring/repo/ops/monitoring
```

```text
BOBFULL_BACKEND_METRICS_TARGETS=10.0.1.10:8080,10.0.1.11:8080
```

이후 Blue-Green 배포에서는 `ops/deployment/aws/deploy-backend-blue-green-v1.sh`가 ALB 전환과 public 검증 이후 새 Active Target Group의 EC2 private IP 2개를 조회하고, Monitoring EC2에 SSM 명령을 보내 다음을 자동 수행한다.

1. Monitoring EC2의 `BACKEND_MONITORING_ENV_FILE`에서 `BOBFULL_BACKEND_METRICS_TARGETS`를 새 Active 2대로 갱신한다.
2. Prometheus 컨테이너 내부 file_sd target 파일(`/tmp/prometheus-targets/bobfull-backend.yml`)을 같은 값으로 갱신한다.
3. Prometheus `/-/reload`를 호출한다.
4. Prometheus API에서 새 Active target 2대의 `up{job="bobfull-backend"}` 값이 모두 `1`인지 확인한다.

이 단계가 실패하면 배포 스크립트는 이전 Active EC2를 STOP하지 않는다.

자동 갱신에 필요한 GitHub Variables:

```text
BACKEND_MONITORING_EC2_INSTANCE_ID
BACKEND_MONITORING_COMPOSE_DIR
BACKEND_MONITORING_ENV_FILE
```

선택적으로 다음 값을 운영 환경에 맞게 조정할 수 있다.

```text
BACKEND_PROMETHEUS_CONTAINER_NAME
BACKEND_PROMETHEUS_TARGET_FILE
BACKEND_PROMETHEUS_PORT
BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS
BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS
BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME
BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS
BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS
```

배포 실패 로그에서 확인할 항목:

- `New active EC2 private IPs`
- `New active EC2 metrics targets`
- `Monitoring env file updated`
- `Prometheus target file preview`
- `Prometheus target state target=<ip:port> state=UP|DOWN`
- `Prometheus target update or UP verification failed. Previous active EC2 instances will remain running.`

로그에는 `BOBFULL_BACKEND_METRICS_TARGETS` 갱신 결과만 출력하고, Grafana 비밀번호·Slack Webhook 같은 다른 env 값은 출력하지 않는다.

자동 전환 실패 시 현재 Active target을 확인하거나 복구해야 할 때는 다음 순서를 사용한다.

1. GitHub Variables 또는 운영 기록에서 Blue/Green Listener와 Target Group ARN을 확인한다.

```bash
export BACKEND_ALB_LISTENER_ARN=<listener-arn>
export BACKEND_BLUE_TARGET_GROUP_ARN=<blue-target-group-arn>
export BACKEND_GREEN_TARGET_GROUP_ARN=<green-target-group-arn>
```

2. Listener default action에서 weight가 100인 Target Group을 Active로 판단한다.

```bash
aws elbv2 describe-listeners \
  --listener-arns "${BACKEND_ALB_LISTENER_ARN}" \
  --query 'Listeners[0].DefaultActions'
```

3. Active Target Group에 등록된 target instance id를 확인한다.

```bash
aws elbv2 describe-target-health \
  --target-group-arn "<active-target-group-arn>" \
  --query 'TargetHealthDescriptions[].Target.Id' \
  --output text
```

4. 해당 instance id 2개의 private IP를 확인한다.

```bash
aws ec2 describe-instances \
  --instance-ids <instance-id-1> <instance-id-2> \
  --query 'Reservations[].Instances[].PrivateIpAddress' \
  --output text
```

5. Monitoring EC2의 `BACKEND_MONITORING_ENV_FILE` 또는 Prometheus UI에서 Active App 2대가 comma-separated target으로 반영됐는지 확인한다. 자동 전환이 실패해 복구가 필요할 때만 해당 env 파일의 `BOBFULL_BACKEND_METRICS_TARGETS`를 현재 Active 2대로 수정한다. env 파일에는 Grafana 비밀번호와 Slack Webhook 같은 secret이 포함될 수 있으므로 전체 파일 내용을 로그나 PR에 붙이지 않는다.

```text
BOBFULL_BACKEND_METRICS_TARGETS=10.0.1.10:8080,10.0.1.11:8080
```

6. 복구 수정이 필요했다면 Prometheus 컨테이너 내부 file_sd target 파일을 같은 target 값으로 맞춘 뒤 `/-/reload`를 호출한다. Prometheus UI `Status -> Targets`에서 `bobfull-backend` target 2개가 모두 `UP`인지 확인한다. 평상시 Blue-Green 배포 후 갱신은 `/-/reload`로 처리하므로 Prometheus/Grafana 재기동을 우선하지 않는다.

## 확인 순서

1. App EC2에서 `/actuator/health`가 `UP`인지 확인한다.
2. App EC2에서 `/actuator/prometheus`가 Prometheus text format을 반환하는지 확인한다.
3. Monitoring EC2 Prometheus UI에서 `Status → Targets → bobfull-backend`가 `UP`인지 확인한다.
4. Grafana에서 Prometheus Data Source `Save & test`가 성공하는지 확인한다.
5. Grafana `BobFull Overview` Dashboard에서 HTTP, JVM, DB, business event 패널이 표시되는지 확인한다.
6. Grafana Contact Point `slack-monitoring`의 `Test`를 실행해 Slack 모니터링 채널 수신을 확인한다.
7. 테스트용 임계치를 낮추거나 테스트 이벤트를 발생시켜 Alert Rule이 `Firing`으로 전환되고 Slack에 전달되는지 확인한다.

## 주요 PromQL

```promql
up{job="bobfull-backend"}
sum by (status) (rate(http_server_requests_seconds_count{job="bobfull-backend"}[1m]))
histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket{job="bobfull-backend"}[5m])))
sum(jvm_memory_used_bytes{job="bobfull-backend",area="heap"}) / sum(jvm_memory_max_bytes{job="bobfull-backend",area="heap"})
max(hikaricp_connections_pending{job="bobfull-backend"})
sum by (event) (increase(bobfull_business_events_total[5m]))
```

## #191 instance별 측정 PromQL

```promql
process_cpu_usage{job="bobfull-backend"}

sum by (instance, area) (
  jvm_memory_used_bytes{job="bobfull-backend"}
)

sum by (instance) (
  rate(http_server_requests_seconds_count{job="bobfull-backend",uri!~"/actuator/.*"}[1m])
)

histogram_quantile(0.95,
  sum by (instance, le) (
    rate(http_server_requests_seconds_bucket{job="bobfull-backend",uri!~"/actuator/.*"}[5m])
  )
)

histogram_quantile(0.99,
  sum by (instance, le) (
    rate(http_server_requests_seconds_bucket{job="bobfull-backend",uri!~"/actuator/.*"}[5m])
  )
)

sum by (instance, status) (
  rate(http_server_requests_seconds_count{job="bobfull-backend",status=~"4..",uri!~"/actuator/.*"}[1m])
)

sum by (instance, status) (
  rate(http_server_requests_seconds_count{job="bobfull-backend",status=~"5..",uri!~"/actuator/.*"}[1m])
)

hikaricp_connections_active{job="bobfull-backend"}
hikaricp_connections_idle{job="bobfull-backend"}
hikaricp_connections_pending{job="bobfull-backend"}
hikaricp_connections_max{job="bobfull-backend"}
```

## Alert 기준

- 운영 Alert는 `ops/monitoring/grafana/provisioning/alerting/alert-rules.yml`에서 관리하며, 각 Rule은 `slack-monitoring` Contact Point로 연결한다.
- Prometheus Alert Rule은 별도로 로드하지 않는다. 동일 조건을 Prometheus와 Grafana 양쪽에서 중복 평가하지 않기 위함이다.
- 1순위 비즈니스 사건과 `PAYMENT_WEBHOOK_PERMANENT_FAILURE`는 5분 안에 1건 이상 발생하면 즉시 확인한다.
- `LOGIN_FAILED`, `AUTH_REISSUE_FAILED`, `IMAGE_STORAGE_REQUEST_FAILED`, `RECRUITMENT_DEADLINE_FAILED`, p95, 오류율, JVM Heap, DB Connection Pending은 초기 검증용 임계치로 시작하고 실제 AWS 기준 데이터 측정 후 조정한다.
- 개별 `paymentId`, `refundId`, `reservationId`, `memberId`, email, client IP는 Prometheus Label로 넣지 않는다. 상세 원인은 CloudWatch 구조화 로그에서 확인한다.

## 기준 데이터 측정

실제 AWS 단일 App EC2 환경에서 k6로 지정 API에 부하를 발생시킨 뒤 `docs/120-templates/monitoring-baseline-template.md`에 결과를 기록한다.

기록 대상:

- 요청량
- 평균 응답시간
- p95 응답시간
- 오류율
- JVM CPU/Memory
- DB Connection Pool active/idle/pending
- 테스트 조건과 실행 시각
