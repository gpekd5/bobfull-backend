# AWS V1 백엔드 배포 설정 기준

이 문서는 저장소에 남기는 백엔드 운영 설정과 재실행 가능한 수동 배포 기준을 정리한다.

실제 AWS 콘솔 작업, EC2 터미널 명령, 캡처 중심 진행 기록은 외부 배포 기록에서 관리한다. 저장소에는 값이 없는 스크립트와 설정 기준만 남긴다.

## 이번 PR에 남기는 범위

- Spring Boot prod Profile 설정
- Docker 이미지 빌드 기준
- 로컬 Docker app 검증용 Compose 설정
- ECR push, EC2 bootstrap, EC2 deploy, 배포 verify 스크립트
- GitHub Actions 기반 백엔드 CI workflow와 자동 배포 workflow 파일
- ALB Target Group weight 기반 Blue-Green 배포 실행 순서 제어
- 평상시 비활성 Blue/Green App EC2 STOP, 배포 시작 시 START, 배포 검증 후 기존 Active EC2 STOP 기준
- Blue-Green ALB 전환 후 Monitoring EC2의 Prometheus backend scrape target 자동 갱신과 UP 확인 기준
- 운영 환경변수 이름과 Parameter Store 이름 기준
- 이미지 저장용 S3 버킷 이름 환경변수 기준
- 식당 이미지 검증용 Java Lambda 수동 설정 기준
- CloudWatch Logs log group 이름 기준

## 운영 Profile 환경변수

`application-prod.yml`은 실제 값을 직접 저장하지 않고 환경변수만 참조한다.

| 환경변수 | 용도 | 필수 여부 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | prod Profile 활성화 | 필수 |
| `DB_URL` | RDS MySQL JDBC URL | 필수 |
| `DB_USERNAME` | RDS DB 사용자 이름 | 필수 |
| `DB_PASSWORD` | RDS DB 비밀번호 | 필수 |
| `REDIS_HOST` | Redis Host | 필수 |
| `REDIS_PORT` | Redis Port | 선택 |
| `REDIS_SSL_ENABLED` | Redis SSL/TLS 사용 여부. prod 기본값은 `true`이며 EC2-local/Docker Redis에서는 `false`로 둔다. | 선택 |
| `DB_POOL_MAX_SIZE` | Hikari maximumPoolSize. prod 기본값은 `10`이며 #191 검증 기준값은 `12`이다. | 선택 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap servers | 필수 |
| `RESTAURANT_INSIGHT_AI_ENABLED` | Restaurant Feedback Insight Provider 활성화 여부. prod 기본값은 `false`다. | 선택 |
| `KAFKA_RESTAURANT_INSIGHT_CONSUMER_ENABLED` | Restaurant Feedback Insight Kafka Consumer 활성화 여부. prod 기본값은 `false`다. | 선택 |
| `JWT_SECRET` | JWT 서명 Secret | 필수 |
| `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS` | Access Token 만료 초 | 선택 |
| `AUTH_REFRESH_TOKEN_EXPIRATION_SECONDS` | Refresh Token 만료 초 | 선택 |
| `CORS_ALLOWED_ORIGINS` | 허용 Origin 목록 | 선택 |
| `PORTONE_API_SECRET` | PortOne API Secret | 필수 |
| `PORTONE_CHANNEL_KEY` | PortOne Channel Key | 선택 |
| `PORTONE_STORE_ID` | PortOne Store ID | 필수 |
| `PORTONE_WEBHOOK_SECRET` | PortOne Webhook Secret | 필수 |
| `MAIL_HOST` | SMTP Host | 필수 |
| `MAIL_USERNAME` | SMTP 사용자 이름 | 필수 |
| `MAIL_PASSWORD` | SMTP 비밀번호 또는 앱 비밀번호 | 필수 |
| `MAIL_PORT` | SMTP Port | 선택 |
| `MAIL_SMTP_AUTH` | SMTP 인증 사용 여부 | 선택 |
| `MAIL_SMTP_STARTTLS` | SMTP STARTTLS 사용 여부 | 선택 |
| `NOTIFICATION_EMAIL_FROM_ADDRESS` | 예약 알림 발신자 이메일 | 선택 |
| `PAYMENT_EXPIRATION_ENABLED` | 결제 만료 스케줄러 활성화 | 선택 |
| `PAYMENT_EXPIRATION_FIXED_DELAY` | 결제 만료 스케줄러 주기 | 선택 |
| `PAYMENT_EXPIRATION_BATCH_SIZE` | 결제 만료 배치 크기 | 선택 |
| `PAYMENT_REFUND_RECONCILIATION_ENABLED` | 환불 재조정 스케줄러 활성화 | 선택 |
| `PAYMENT_REFUND_RECONCILIATION_FIXED_DELAY` | 환불 재조정 스케줄러 주기 | 선택 |
| `PAYMENT_REFUND_RECONCILIATION_MINIMUM_AGE` | 재조회 대상 최소 경과 시간 | 선택 |
| `PAYMENT_REFUND_RECONCILIATION_RECHECK_DELAY` | 재조회 간격 | 선택 |
| `PAYMENT_REFUND_RECONCILIATION_BATCH_SIZE` | 환불 재조정 배치 크기 | 선택 |
| `AWS_REGION` | AWS Region | 선택 |
| `S3_IMAGE_BUCKET` | 식당 이미지 S3 버킷 이름 | 필수 |
| `S3_IMAGE_UPLOAD_URL_EXPIRATION` | 식당 이미지 Presigned PUT URL 만료 시간 | 선택 |
| `S3_IMAGE_GET_URL_EXPIRATION` | 식당 이미지 Presigned GET URL 만료 시간 | 선택 |

기본값이 있는 선택 환경변수는 운영에서 명시하지 않아도 애플리케이션 기본값으로 동작한다.

## Parameter Store 이름 기준

운영 값은 `/bobfull/prod` 아래에 저장한다.

필수 Parameter:

```text
/bobfull/prod/db-url
/bobfull/prod/db-username
/bobfull/prod/db-password
/bobfull/prod/redis-host
/bobfull/prod/kafka-bootstrap-servers
/bobfull/prod/jwt-secret
/bobfull/prod/portone-api-secret
/bobfull/prod/portone-store-id
/bobfull/prod/portone-webhook-secret
/bobfull/prod/s3-image-bucket
/bobfull/prod/mail-host
/bobfull/prod/mail-username
/bobfull/prod/mail-password
```

선택 Parameter:

```text
/bobfull/prod/redis-port
/bobfull/prod/redis-ssl-enabled
/bobfull/prod/db-pool-max-size
/bobfull/prod/restaurant-insight-ai-enabled
/bobfull/prod/kafka-restaurant-insight-consumer-enabled
/bobfull/prod/jwt-access-token-expiration-seconds
/bobfull/prod/auth-refresh-token-expiration-seconds
/bobfull/prod/jpa-ddl-auto
/bobfull/prod/cors-allowed-origins
/bobfull/prod/portone-channel-key
/bobfull/prod/mail-port
/bobfull/prod/mail-smtp-auth
/bobfull/prod/mail-smtp-starttls
/bobfull/prod/notification-email-from-address
/bobfull/prod/payment-expiration-enabled
/bobfull/prod/payment-expiration-fixed-delay
/bobfull/prod/payment-expiration-batch-size
/bobfull/prod/payment-refund-reconciliation-enabled
/bobfull/prod/payment-refund-reconciliation-fixed-delay
/bobfull/prod/payment-refund-reconciliation-minimum-age
/bobfull/prod/payment-refund-reconciliation-recheck-delay
/bobfull/prod/payment-refund-reconciliation-batch-size
/bobfull/prod/s3-image-upload-url-expiration
/bobfull/prod/s3-image-get-url-expiration
```

Parameter Store 이름은 kebab-case로 저장하고, `ops/deployment/aws/deploy-backend-v1.sh`가 컨테이너 실행 시 `DB_URL`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `S3_IMAGE_BUCKET` 같은 대문자 환경변수 이름으로 변환한다. Restaurant Feedback Insight의 Parameter Store 자동 주입은 현재 `restaurant-insight-ai-enabled`와 `kafka-restaurant-insight-consumer-enabled` 두 개만 지원한다.

`application-prod.yml`에는 `KAFKA_RESTAURANT_INSIGHT_GROUP_ID`, `KAFKA_RESTAURANT_INSIGHT_DLT_TOPIC` 자리표시자가 있어 직접 컨테이너 환경변수로 주입하면 값을 바꿀 수 있다. 다만 현재 배포 스크립트의 `/bobfull/prod/...` Parameter Store 자동 주입 항목은 아니다. Restaurant Insight retry는 현재 `RestaurantInsightConsumerConfig`의 application property 기본값(max attempts `3`, retry backoff `1000ms`)을 사용하며, 별도 prod Parameter Store 매핑은 제공하지 않는다.

비밀번호, JWT Secret, PortOne Secret, SMTP 비밀번호처럼 노출되면 안 되는 값은 `SecureString`으로 저장한다.

## 채팅 WebSocket 배포 참고 (#50, #170, #169)

예약 채팅은 `/ws` STOMP 엔드포인트와 각 애플리케이션 인스턴스의 In-memory SimpleBroker(`registry.enableSimpleBroker`)를 사용한다. #50 당시에는 단일 App EC2 기준이라 인스턴스 간 세션 공유가 없었지만, #170에서 DB 저장 후 Redis Pub/Sub으로 모든 App 인스턴스에 신호를 전파하고 각 인스턴스가 자기 SimpleBroker 세션으로 전달하는 구조를 구현했다. #169에서는 다중 App EC2와 공용 ElastiCache Redis 환경의 인스턴스 간 전달을 확인했다.

- 신규 GitHub Variables·Secrets, 신규 SSM Parameter Store 값은 없다. 채팅은 STOMP CONNECT 인증에 기존 `JWT_SECRET`을, STOMP Endpoint `setAllowedOrigins`에 기존 `CORS_ALLOWED_ORIGINS`를 그대로 재사용한다. `CORS_ALLOWED_ORIGINS`를 변경하면 REST CORS와 WebSocket 허용 Origin에 동시에 반영되므로, Origin 값을 다룰 때는 두 용도를 함께 고려한다.
- `build.gradle`에 `spring-boot-starter-websocket` 의존성이 추가되었을 뿐, 별도 배포 스크립트·포트 변경은 없다. `/ws`는 기존 애플리케이션 포트(`8080`)를 공유한다.
- WebSocket 세션 자체는 여전히 각 App 인스턴스의 local SimpleBroker에 붙어 있지만, ChatMessage 커밋 뒤 Redis Pub/Sub(`bobfull:chat:messages`)이 모든 인스턴스에 payload를 전달한다. Redis Pub/Sub은 실시간 전달만 담당하며 메시지를 저장하거나 다시 보내주지 않는다. 단절 중 놓친 메시지는 DB cursor 조회로 복구한다.
- Kafka는 채팅 실시간 전파가 아니라 AI Moderation과 Restaurant Feedback Insight 후속 처리에 사용한다. Redis Pub/Sub과 Kafka의 책임을 섞지 않는다.
- 배포 후 검증(`verify-backend-v1.sh`)은 REST `GET /api/restaurants`만 확인하며 WebSocket 연결 자체는 검증하지 않는다. 컨테이너 기동 여부 확인이 목적이므로 현재 범위에서는 별도 확인을 추가하지 않는다.

## Prometheus/Grafana 모니터링 배포 참고 (#64)

모니터링 V1은 App EC2와 분리된 Monitoring EC2에서 Prometheus와 Grafana를 Docker Compose로 함께 실행한다.

- 백엔드는 기존 애플리케이션 포트(`8080`)의 `/actuator/prometheus`를 노출하고, Prometheus가 App EC2 private IP 또는 내부 DNS로 scrape한다.
- Prometheus는 `BOBFULL_BACKEND_METRICS_TARGETS`로 `bobfull-backend` file_sd target을 만들고, Blue-Green 배포 성공 후 GitHub Actions가 Monitoring EC2에 SSM 명령을 보내 새 Active EC2 2대의 private IP로 target을 갱신한 뒤 `/-/reload`를 호출한다. 운영에서 env 파일과 compose/config 경로가 분리될 수 있으므로 env 파일은 `BACKEND_MONITORING_ENV_FILE`, compose/config 위치는 `BACKEND_MONITORING_COMPOSE_DIR`로 각각 받는다.
- App EC2 보안 그룹은 Monitoring EC2 보안 그룹에서 들어오는 `8080` 접근만 허용한다. Grafana 외부 접속 포트(`3000`)는 운영 접근 주체로 제한한다.
- Slack Alert Contact Point는 실제 모니터링 채널 Webhook URL을 `BACKEND_MONITORING_ENV_FILE` 경로의 env 파일 또는 운영 비밀 저장소로 주입하고, 배포 직후 Grafana Contact Point `Test` 수신을 확인한다.
- Prometheus/Grafana 구성 파일은 `ops/monitoring/` 아래에 두며, 상세 실행·검증·장애 대응 기준은 [monitoring-runbook.md](../080-operations/monitoring-runbook.md)를 따른다.
- 초기 Alert Rule 임계값은 테스트 기준으로 시작한다. p95, 오류율, 로그인 실패 임계값은 실제 AWS 단일 App EC2 k6 기준선 측정 후 [monitoring-baseline-template.md](../120-templates/monitoring-baseline-template.md)에 기록한 값으로 조정한다.

## GitHub Actions 백엔드 CI와 CD

백엔드는 검증 단계와 운영 배포 단계를 분리한다.

- `.github/workflows/ci-backend-v1.yml`: `develop` push에서 Gradle 검증과 Docker build만 수행한다.
- `.github/workflows/deploy-backend-v1.yml`: `main` push에서 CI 성공 후 ECR push, 비활성 ALB Target Group의 EC2 2대에 SSM 배포, Target Group health 확인, Listener weight 전환과 public 검증을 수행한다.

ECR repository는 AWS에 미리 생성되어 있어야 한다. 배포 workflow와 ECR push 스크립트는 `aws ecr describe-repositories`로 존재 여부만 확인하며, 없으면 실패하고 자동 생성하지 않는다.
ECR image는 GitHub commit SHA 태그로만 push한다. 이미지 태그 불변성을 유지하기 위해 `latest` 태그는 생성하거나 push하지 않는다.

feature 브랜치와 `pull_request` 이벤트에서는 백엔드 V1 CI/CD workflow를 실행하지 않는다.

CI 흐름:

```text
develop push
→ Gradle clean check bootJar
→ Docker image build
```

CD 흐름:

```text
main push
→ Gradle clean check bootJar
→ Docker image build
→ ECR push
→ ALB Listener의 현재 Blue/Green weight 조회
→ weight 0인 비활성 Target Group의 EC2 target 2대 조회
→ 비활성 EC2가 stopping이면 stopped까지 대기
→ 비활성 EC2가 stopped이면 start-instances 실행
→ 비활성 EC2 2대가 running이 될 때까지 대기
→ 비활성 EC2 2대가 SSM managed Online이 될 때까지 대기
→ SSM Run Command로 비활성 EC2 2대에 같은 image 배포
→ 각 EC2에서 Parameter Store env-file 생성, 기존 컨테이너 교체, localhost readiness 확인
→ 비활성 Target Group의 모든 target healthy 확인
→ ALB Listener weight를 기존 활성 0, 신규 활성 100으로 전환
→ public readiness와 API 검증
→ 실패 시 기존 Listener default action으로 rollback
→ 신규 Active Target Group의 EC2 2대 private IP 조회
→ Monitoring EC2에 SSM Run Command로 Prometheus `bobfull-backend` target 갱신
→ Prometheus `up{job="bobfull-backend"}`에서 신규 Active target 2대가 모두 UP인지 확인
→ 기존 활성 EC2 2대를 rollback window 동안 running 유지
→ STOP 직전 ALB Listener의 Blue/Green Target Group weight 재조회
→ 신규 Active Target Group weight 100, 기존 Target Group weight 0일 때만 STOP 허용
→ rollback window 종료 후 배포 시작 시점에 저장한 기존 활성 EC2 2대만 stop-instances 실행
→ ECR, Parameter Store, S3, CloudWatch 확인
```

GitHub Actions의 AWS 인증은 장기 Access Key를 저장하지 않고 OIDC로 IAM Role을 assume한다. EC2 22번 포트를 열거나 PEM Private Key를 GitHub Secret에 저장하지 않는다.

필수 GitHub Variables:

```text
AWS_REGION
ECR_REPOSITORY
BACKEND_PARAMETER_PREFIX
BACKEND_ALB_LISTENER_ARN
BACKEND_BLUE_TARGET_GROUP_ARN
BACKEND_GREEN_TARGET_GROUP_ARN
BACKEND_PUBLIC_READINESS_URL
BACKEND_PUBLIC_API_VERIFY_URL
BACKEND_MONITORING_EC2_INSTANCE_ID
BACKEND_MONITORING_COMPOSE_DIR
BACKEND_MONITORING_ENV_FILE
```

선택 GitHub Variables:

```text
BACKEND_TARGET_PORT
BACKEND_TG_HEALTH_TIMEOUT_SECONDS
BACKEND_TG_HEALTH_POLL_INTERVAL_SECONDS
BACKEND_PUBLIC_VERIFY_ATTEMPTS
BACKEND_PUBLIC_VERIFY_DELAY_SECONDS
BACKEND_PUBLIC_VERIFY_TIMEOUT_SECONDS
BACKEND_LISTENER_WEIGHT_TIMEOUT_SECONDS
BACKEND_LISTENER_WEIGHT_POLL_INTERVAL_SECONDS
BACKEND_EC2_STATE_TIMEOUT_SECONDS
BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS
BACKEND_SSM_ONLINE_TIMEOUT_SECONDS
BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS
BACKEND_PREVIOUS_ENV_KEEP_SECONDS
BACKEND_PROMETHEUS_CONTAINER_NAME
BACKEND_PROMETHEUS_TARGET_FILE
BACKEND_PROMETHEUS_PORT
BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS
BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS
BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME
BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS
BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS
```

기본값:

| 변수 | 기본값 | 용도 |
|---|---:|---|
| `BACKEND_EC2_STATE_TIMEOUT_SECONDS` | `300` | EC2 `stopping -> stopped`, `stopped/pending -> running`, `stopping/running -> stopped` 대기 timeout |
| `BACKEND_EC2_STATE_POLL_INTERVAL_SECONDS` | `10` | EC2 상태 polling 간격 |
| `BACKEND_SSM_ONLINE_TIMEOUT_SECONDS` | `300` | EC2 running 이후 SSM `PingStatus=Online` 대기 timeout |
| `BACKEND_SSM_ONLINE_POLL_INTERVAL_SECONDS` | `10` | SSM Online polling 간격 |
| `BACKEND_PREVIOUS_ENV_KEEP_SECONDS` | `600` | public 검증 성공 후 기존 active EC2를 rollback 가능 상태로 유지하는 시간 |
| `BACKEND_PROMETHEUS_CONTAINER_NAME` | `bobfull-prometheus` | Monitoring EC2의 Prometheus 컨테이너 이름 |
| `BACKEND_PROMETHEUS_TARGET_FILE` | `/tmp/prometheus-targets/bobfull-backend.yml` | Prometheus 컨테이너 내부 `bobfull-backend` file_sd target 파일 |
| `BACKEND_PROMETHEUS_PORT` | `9090` | Monitoring EC2 localhost에서 Prometheus API와 `/-/reload`에 접근하는 포트 |
| `BACKEND_PROMETHEUS_TARGET_UP_TIMEOUT_SECONDS` | `180` | Prometheus target 갱신 후 신규 Active 2대 UP 확인 timeout |
| `BACKEND_PROMETHEUS_TARGET_UP_POLL_INTERVAL_SECONDS` | `10` | Prometheus target UP 확인 polling 간격 |
| `BACKEND_PROMETHEUS_SSM_DOCUMENT_NAME` | `AWS-RunShellScript` | Monitoring EC2 target 갱신에 사용할 SSM 문서 |
| `BACKEND_PROMETHEUS_SSM_TIMEOUT_SECONDS` | `300` | Monitoring EC2 SSM 명령 완료 대기 timeout |
| `BACKEND_PROMETHEUS_SSM_POLL_INTERVAL_SECONDS` | `3` | Monitoring EC2 SSM 명령 상태 polling 간격 |

Monitoring EC2 실제 운영 경로 예시:

```text
BACKEND_MONITORING_ENV_FILE=/opt/bobfull-monitoring/.env
BACKEND_MONITORING_COMPOSE_DIR=/opt/bobfull-monitoring/repo/ops/monitoring
```

현재 구현은 `BACKEND_PREVIOUS_ENV_KEEP_SECONDS` 동안 GitHub Actions job 안에서 정해진 시간만큼 `sleep`으로 대기한다. 구조가 단순하고 배포 직후 롤백 가능한 시간이 한 workflow 로그에 남는 장점이 있다. 다만 workflow 점유 시간이 운영상 부담되면 후속으로 EventBridge Scheduler 또는 별도 수동 cleanup workflow를 검토한다.

필수 GitHub Secrets:

```text
AWS_ROLE_TO_ASSUME
```

`S3_IMAGE_BUCKET`은 GitHub Variable로 넘기지 않고 Parameter Store의 `/bobfull/prod/s3-image-bucket` 값을 사용한다.

GitHub Actions OIDC Role에는 최소한 다음 권한이 필요하다.

```text
sts:GetCallerIdentity
ecr:GetAuthorizationToken
ecr:DescribeRepositories
ecr:BatchCheckLayerAvailability
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
ecr:PutImage
ecr:BatchGetImage
ecr:DescribeImages
ssm:SendCommand
ssm:GetCommandInvocation
ssm:DescribeInstanceInformation
ssm:GetParameter
ssm:GetParametersByPath
elasticloadbalancing:DescribeListeners
elasticloadbalancing:ModifyListener
elasticloadbalancing:DescribeTargetHealth
ec2:DescribeInstances
ec2:StartInstances
ec2:StopInstances
s3:ListBucket
logs:DescribeLogStreams
```

Prometheus target 자동 갱신은 새 AWS action 이름을 추가로 요구하지 않는다. 다만 기존 `ssm:SendCommand`, `ssm:GetCommandInvocation`, `ssm:DescribeInstanceInformation` 권한의 Resource 범위에 Monitoring EC2 instance ARN과 `AWS-RunShellScript` 문서 ARN이 포함되어야 한다. 새 Active EC2 private IP 조회는 이미 필요한 `ec2:DescribeInstances`를 사용한다. Hikari Pool Size 검증에 사용하는 `/bobfull/prod/db-pool-max-size`는 기존 `ssm:GetParameter` 권한으로 조회하며, 값이 없으면 컨테이너 env에 `DB_POOL_MAX_SIZE`를 쓰지 않아 애플리케이션 기본값 `10`이 적용된다.

대상 EC2는 SSM managed instance로 등록되어 있어야 하며, EC2 instance profile에는 SSM Agent 동작과 EC2 내부 배포 스크립트 실행에 필요한 권한이 필요하다.

```text
AmazonSSMManagedInstanceCore
ecr:GetAuthorizationToken
ecr:BatchGetImage
ecr:GetDownloadUrlForLayer
ssm:GetParameter
ssm:GetParameters
ssm:GetParametersByPath
kms:Decrypt
s3:ListBucket
logs:CreateLogGroup
```

CI 성공 여부는 다음을 모두 통과해야 한다.

- Gradle `clean check bootJar` 성공
- Docker image build 성공

CD 배포 성공 여부는 다음을 모두 통과해야 한다.

- Gradle `clean check bootJar` 성공
- Docker image build와 ECR push 성공
- ALB Listener의 Blue/Green weight가 정확히 `100/0` 또는 `0/100`
- 비활성 Target Group의 EC2 target이 정확히 2대
- 비활성 EC2가 `stopped`이면 start, `running`이면 그대로 진행, `pending`이면 running까지 대기, `stopping`이면 stopped까지 대기 후 start
- 비활성 EC2가 기타 비정상 상태이면 명확한 오류로 배포 실패
- 비활성 EC2 2대가 모두 `running`
- 비활성 EC2 2대가 모두 SSM managed `Online`
- 비활성 EC2 2대의 `aws ssm send-command` 명령 완료 상태가 모두 `Success`
- 비활성 EC2 2대 내부 배포 스크립트의 컨테이너 `running` 확인 성공
- 비활성 EC2 2대 내부 `localhost` 기준 readiness health check 성공
- 비활성 Target Group의 target 2대가 모두 `healthy`
- ALB Listener weight 전환 후 public readiness와 API 검증 성공
- 신규 Active Target Group의 EC2 2대 private IP 조회 성공
- Monitoring EC2 SSM 명령으로 `BACKEND_MONITORING_ENV_FILE`의 `BOBFULL_BACKEND_METRICS_TARGETS`와 Prometheus file_sd target 파일 갱신 성공
- Prometheus `/-/reload` 성공
- Prometheus API 기준 신규 Active target 2대의 `up{job="bobfull-backend"}`가 모두 `1`
- public 검증 성공 후 rollback window 동안 기존 활성 EC2 2대 running 유지
- 기존 활성 EC2 STOP 직전 ALB Listener weight 재조회 결과 신규 Active Target Group weight가 `100`, 기존 Target Group weight가 `0`
- STOP guard 조건을 만족하면 rollback window 종료 후 배포 시작 시점에 저장한 기존 활성 EC2 2대만 `stopped`
- STOP guard 조건을 만족하지 않으면 기존 활성 EC2를 STOP하지 않고 workflow를 안전하게 종료
- Parameter Store 경로 조회, S3 이미지 버킷 접근, CloudWatch Log Group 접근 확인
- 비활성 EC2에서 실행 중인 컨테이너 image가 이번 workflow에서 push한 image URI와 일치

비활성 EC2 START, EC2 running 대기, SSM Online 대기, 비활성 배포 또는 Target Group health 검증이 실패하면 Listener traffic을 전환하지 않는다. Traffic 전환 후 Listener 확인 또는 public 검증이 실패하면 전환 직전에 저장한 Listener default action으로 rollback하며, 이 시점에는 Prometheus target을 아직 바꾸지 않았으므로 이전 Active target이 유지된다. rollback이 발생했거나 rollback을 시도한 경우 기존 활성 EC2는 절대 stop하지 않는다. public 검증 성공 후 Prometheus target 갱신, reload 또는 신규 Active target 2대 UP 확인이 실패해도 기존 활성 EC2는 stop하지 않는다. rollback window 종료 후 기존 활성 EC2를 STOP하기 직전에는 ALB Listener의 Blue/Green Target Group weight를 다시 조회하고, 신규 Active Target Group weight가 `100`이고 기존 Target Group weight가 `0`인 경우에만 stop을 허용한다. 수동 rollback 등으로 Listener 상태가 바뀌어 조건을 만족하지 않으면 현재 Blue/Green weight, STOP 허용 여부, skip reason을 로그에 남기고 기존 활성 EC2 STOP을 건너뛴 채 workflow를 안전하게 종료한다. stop 대상은 ALB 전환 이후 다시 계산하지 않고 배포 시작 시점에 저장한 active Target Group의 EC2 instance id만 사용한다. EC2 배포 실패 원인은 EC2 Docker/CloudWatch Logs에서 확인한다.

## CORS와 S3 프론트엔드 Origin

프론트엔드가 S3 정적 웹사이트 호스팅으로 배포되면 브라우저의 Origin은 S3 웹사이트 endpoint가 된다. 백엔드는 이 Origin을 `CORS_ALLOWED_ORIGINS`로 받아 Spring Security CORS 설정에 적용한다.

S3 정적 웹사이트 endpoint 예시:

```text
http://<frontend-bucket>.s3-website.ap-northeast-2.amazonaws.com
```

CloudFront를 붙인 뒤에는 CloudFront 배포 도메인 또는 실제 서비스 도메인을 Origin으로 사용한다.

```text
https://<cloudfront-distribution-domain>
https://<service-domain>
```

Parameter Store 등록 예시:

```bash
aws ssm put-parameter \
  --region ap-northeast-2 \
  --name /bobfull/prod/cors-allowed-origins \
  --type String \
  --value "http://<frontend-bucket>.s3-website.ap-northeast-2.amazonaws.com" \
  --overwrite
```

로컬 프론트엔드와 S3 프론트엔드를 함께 허용해야 하면 쉼표로 구분한다.

```text
http://localhost:5173,http://<frontend-bucket>.s3-website.ap-northeast-2.amazonaws.com
```

Origin에는 path를 넣지 않고 scheme, host, port까지만 기록한다. 값을 바꾼 뒤에는 EC2에서 `ops/deployment/aws/deploy-backend-v1.sh`를 다시 실행해 env-file에 `CORS_ALLOWED_ORIGINS`가 기록된 컨테이너로 교체한다.

## AWS 리소스 이름 기준

| 항목 | 기준 |
|---|---|
| Parameter Store prefix | `/bobfull/prod` |
| CloudWatch Log Group | `/bobfull/backend` |
| S3 이미지 버킷 | Parameter Store `s3-image-bucket` 또는 `S3_IMAGE_BUCKET` 환경변수로 주입 |
| 식당 이미지 검증 Lambda | `bobfull-restaurant-image-validator` |
| Lambda CloudWatch Log Group | `/aws/lambda/bobfull-restaurant-image-validator` |
| 컨테이너 이름 | `bobfull-backend` |
| 애플리케이션 포트 | `8080` |

## 식당 이미지 S3·Lambda 수동 설정

식당 이미지는 백엔드가 바이너리를 직접 받지 않고 S3 Presigned URL로 처리한다. Spring Boot는 `uploadUrl`, `tempImageKey`, `finalImageKey`를 발급하고, Java Lambda가 임시 객체를 검증해 최종 경로로 복사한다.

### S3 버킷

- 버킷 이름은 `S3_IMAGE_BUCKET`과 `/bobfull/prod/s3-image-bucket`에 동일하게 기록한다.
- S3 Event Notification은 `ObjectCreated:*`, prefix `temp/restaurants/`로 Lambda를 호출한다.
- temp 객체는 lifecycle rule로 prefix `temp/`를 1일 후 만료한다.
- 프론트엔드 Origin에서 Presigned PUT/GET을 사용할 수 있도록 CORS를 설정한다.
- 백엔드가 Presigned URL을 서명하고 최종 객체 존재 확인·기존 객체 삭제를 수행하므로 EC2 애플리케이션 역할에도 S3 권한이 필요하다.

```json
[
  {
    "AllowedOrigins": ["https://<frontend-origin>"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 300
  }
]
```

### Lambda

- Runtime: Java 17
- Handler: `com.bobfull.lambda.restaurantimage.RestaurantImageValidationHandler::handleRequest`
- Memory: 256MB
- Timeout: 10s
- Environment: `S3_IMAGE_BUCKET=<image-bucket-name>`
- 실패 재시도는 AWS Lambda 기본 비동기 재시도를 사용한다. DLQ는 후속 운영 고도화에서 별도 결정한다.
- 로그는 CloudWatch Logs 기본 로그 그룹(`/aws/lambda/<function-name>`)을 사용한다.

Lambda 실행 역할에는 최소한 다음 권한이 필요하다.

```text
s3:GetObject    arn:aws:s3:::<image-bucket>/temp/restaurants/*
s3:DeleteObject arn:aws:s3:::<image-bucket>/temp/restaurants/*
s3:PutObject    arn:aws:s3:::<image-bucket>/restaurants/*
logs:CreateLogGroup
logs:CreateLogStream
logs:PutLogEvents
```

백엔드 실행 역할에는 최소한 다음 권한이 필요하다.

```text
s3:PutObject    arn:aws:s3:::<image-bucket>/temp/restaurants/*
s3:GetObject    arn:aws:s3:::<image-bucket>/restaurants/*
s3:DeleteObject arn:aws:s3:::<image-bucket>/restaurants/*
```

Lambda 배포용 fat jar는 다음 Gradle task로 생성한다.

```bash
./gradlew :lambda:restaurant-image-validator:jar
```

생성 산출물 기준 경로:

```text
lambda/restaurant-image-validator/build/libs/restaurant-image-validator-0.0.1-SNAPSHOT-aws.jar
```

## 제외 범위

다음 항목은 이번 PR에 포함하지 않는다.

- Auto Scaling, Route 53, ACM HTTPS, CloudFront
- main 반영 이후의 백엔드 운영 CD 실제 실행 결과
