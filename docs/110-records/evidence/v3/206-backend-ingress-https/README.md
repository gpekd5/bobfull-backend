# Issue #206 — Backend Ingress HTTPS Evidence

## 검증 대상

BobFull Backend의 외부 진입점을 App EC2 직접 공개 구조에서 Route 53, ALB, ACM 기반 HTTPS 진입 구조로 전환했는지 확인한다. 이번 기록은 AWS 콘솔에서 이미 완료된 인프라 변경 결과를 저장소 Evidence로 남기는 문서 전용 작업이며, 애플리케이션 코드와 테스트 코드는 변경하지 않는다.

## 측정 계약

- Primary KPI: 외부 Backend API 진입점이 `https://api.bobfull.click` HTTPS ALB 경로로 동작하고, App EC2 `:8080` 직접 공개가 제거된 상태
- Secondary KPI: ALB Target Group `Healthy`, HTTP `:80` 요청의 HTTPS `:443` 301 Redirect, Monitoring EC2의 Prometheus 접근 유지
- 안전 확인: `/actuator/health`가 `UP`을 반환하고, App EC2 `:8080` 접근 허용 범위가 ALB SG와 Monitoring SG로 제한된 상태

## 기준 코드

- Before SHA: `200692f` (Evidence 문서 작성 전 최신 `develop`)
- After SHA: PR Head SHA (이 문서만 추가하는 커밋)

## 환경·데이터·실행 조건

- 대상 환경: AWS 운영 인프라
- 도메인: `bobfull.click`, `api.bobfull.click`
- TLS 종료 지점: Application Load Balancer HTTPS `:443`
- Backend App 포트: App EC2 HTTP `:8080`
- Health Check 경로: `/actuator/health`
- 직접 재확인 일시: 2026-08-11 KST
- 직접 재확인 범위: 공개 HTTPS Health Check와 HTTP to HTTPS Redirect
- AWS 콘솔 확인 범위: Human이 제공한 ALB, Target Group, ACM, Route 53, Security Group, Monitoring 접근 확인 결과

민감정보, AWS 리소스 ARN, Security Group ID, EC2 Public IP는 이 Evidence에 기록하지 않는다.

## Before 결과

Issue #206에 기록된 변경 전 구조는 다음과 같다.

```text
Client
  -> HTTP
  -> App EC2 :8080
```

변경 전 접근 제어:

```text
App EC2 :8080 <- 0.0.0.0/0
App EC2 :8080 <- Monitoring SG
```

변경 전 한계:

- App EC2가 직접 Backend API Public Endpoint 역할을 했다.
- Backend API가 별도 ALB HTTPS 진입 구조 없이 제공됐다.
- 이후 다중 EC2 구성 시 동일한 Public Endpoint를 유지할 Load Balancing 기반이 부족했다.

Before 상태는 이 Evidence 작성 시점에 이미 인프라 변경이 완료된 뒤였으므로 재현하지 않았다. 완료된 작업을 소급해 성능 수치나 반복 측정값으로 작성하지 않는다.

## 변경 내용

Human이 AWS 콘솔에서 완료한 인프라 변경은 다음과 같다.

- Application Load Balancer 생성
- Target Group `bobfull-app-tg` 생성 및 App EC2 `:8080` 연결
- `/actuator/health` 기반 Target Health Check 설정 및 `Healthy` 확인
- `bobfull.click` 도메인 등록
- ACM 인증서 발급
  - `bobfull.click`
  - `*.bobfull.click`
- ALB HTTPS `:443` Listener에 ACM 인증서 연결
- Route 53에서 `api.bobfull.click`을 ALB Alias로 연결
- HTTP `:80` Listener를 HTTPS `:443`으로 301 Redirect하도록 변경
- App EC2 Security Group에서 `8080 <- 0.0.0.0/0` 제거
- App EC2 `:8080` 접근을 다음 Security Group으로 제한
  - `bobfull-alb-sg`
  - `bobfull-monitoring-sg`
- App EC2 Public IP `:8080` 직접 접근 차단 확인
- Monitoring EC2에서 App EC2 Prometheus 접근 유지 확인

변경 후 목표 구조:

```text
Client
  -> Route 53 api.bobfull.click
  -> ALB HTTPS :443 + ACM
  -> Target Group bobfull-app-tg
  -> App EC2 HTTP :8080
```

## After 결과

| 지표·현상 | Before | After | 판정 |
|---|---|---|---|
| Backend Public Endpoint | App EC2 Public Endpoint `:8080` 직접 진입 | `api.bobfull.click -> Route 53 -> ALB -> Target Group -> App EC2 :8080` | PASS |
| HTTPS 적용 | 별도 HTTPS ALB 진입 구조 없음 | ALB HTTPS `:443` Listener에 ACM 인증서 연결 | PASS |
| HTTP 평문 진입 정책 | App EC2 `:8080` 직접 HTTP 진입 | ALB HTTP `:80` 요청을 HTTPS `:443`으로 301 Redirect | PASS |
| App EC2 `:8080` 공개 범위 | `0.0.0.0/0` 허용 | `bobfull-alb-sg`, `bobfull-monitoring-sg`만 허용 | PASS |
| Target Health | ALB Target Group 없음 | `bobfull-app-tg`에서 App EC2 `Healthy` 확인 | PASS |
| Health Check | App EC2 직접 확인 구조 | `https://api.bobfull.click/actuator/health`가 `status: UP` 반환 | PASS |
| App EC2 Public IP `:8080` 직접 접근 | 공개 접근 가능 | 직접 접근 차단 확인 | PASS |
| Monitoring Prometheus 접근 | Monitoring SG에서 App EC2 `:8080` 접근 | Monitoring EC2에서 Prometheus 접근 유지 확인 | PASS |

직접 재확인한 공개 엔드포인트 결과:

```text
GET https://api.bobfull.click/actuator/health
HTTP 200
{"groups":["liveness","readiness"],"status":"UP"}
```

```text
GET http://api.bobfull.click/actuator/health
HTTP 301
Location: https://api.bobfull.click:443/actuator/health
```

## 정합성 회귀 검증

- `/actuator/health`가 HTTPS ALB 경로에서 `UP`을 반환하므로 ALB, Target Group, App EC2 애플리케이션 기동 상태의 기본 연결은 유지된다.
- App EC2 Public IP `:8080` 직접 접근 차단은 Human이 AWS 콘솔 변경 후 확인했다.
- Monitoring EC2에서 App EC2 Prometheus 접근 유지는 Human이 확인했다.
- 애플리케이션 코드, 설정 파일, 테스트 코드는 변경하지 않았다.
- Frontend API Base URL, PortOne Webhook/Callback처럼 외부 서비스나 별도 배포 환경에 속한 설정은 이 저장소 문서 커밋에서 직접 변경하지 않았고, 별도 실행 검증도 수행하지 않았다.

## 구조화 로그·메트릭

이 Evidence는 AWS 인프라 상태 전환 기록이며, 성능 측정이나 장기 운영 메트릭 수집을 수행하지 않았다.

- ALB Target Health: Human이 AWS 콘솔에서 `Healthy` 확인
- Public Health Check: `https://api.bobfull.click/actuator/health` 직접 호출로 `UP` 확인
- HTTP Redirect: `http://api.bobfull.click/actuator/health` 직접 호출로 `301` 확인
- Prometheus 접근 유지: Human이 Monitoring EC2에서 확인

## 결과 해석

이번 변경으로 Backend의 공식 Public Endpoint는 App EC2 `:8080`이 아니라 `api.bobfull.click`의 ALB HTTPS 진입점이 되었다. TLS 종료는 Spring Boot가 아니라 ALB에서 수행하며, App EC2는 내부 Backend Target으로 남는다.

App EC2 `:8080`에서 `0.0.0.0/0` 접근을 제거하고 ALB SG와 Monitoring SG만 허용했으므로, 외부 사용자는 ALB를 경유하고 운영 모니터링은 기존 Prometheus 경로를 유지한다. 이 구조는 이후 #169에서 다중 App EC2를 Target Group에 추가하는 기반으로 사용할 수 있다.

## 검증 한계

- AWS 콘솔 화면, 리소스 ARN, Security Group ID, EC2 Public IP는 민감정보·운영정보 노출을 피하기 위해 저장소에 기록하지 않았다.
- Before 상태는 이미 변경 완료 후였으므로 재현하지 않았고, Issue #206에 기록된 변경 전 구조를 기준으로 비교했다.
- 성능, 처리량, p95/p99 latency, 장기 장애 전환, 다중 EC2 Blue-Green 배포 중 요청 연속성은 측정하지 않았다.
- Frontend Base URL, PortOne Webhook/Callback 등 외부 설정은 이 Evidence에서 직접 검증하지 않았다.
- WAF, Auto Scaling, 다중 EC2, RDS/Redis 고가용성은 이번 범위가 아니다.

## 관련

- Issue: #206
- PR: 생성 예정
- 선행: #150, #64
- 후속: #169, #191
- ADR: Issue #206에서 ADR 필요 후보로 기록했으나, 이번 PR은 이미 적용된 인프라 Evidence 기록만 수행한다.
