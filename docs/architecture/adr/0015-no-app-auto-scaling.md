# ADR 0015: 측정 결과 App Auto Scaling을 도입하지 않고 Active 2대를 유지

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 결정 유형: `MEASURED_AND_REJECTED — Auto Scaling 미도입`
- 관련 Issue·PR: #191, PR #276
- 선행 Evidence: `docs/evidence/v3/169-app-ha/README.md`, `docs/evidence/v3/191-auto-scaling/README.md`

## 배경

#169에서 ALB 뒤 Active App EC2 2대 구조와 Blue-Green 배포를 구성했다. 이 다중화의 목적은 App 계층 HA와 배포 안전성이며, 트래픽 증가에 따라 활성 App을 2→N으로 늘리는 Auto Scaling과는 목적이 다르다.

인기 회차 조회와 부하 테스트에서 높은 latency와 자원 포화가 관측됐기 때문에, App 서버 수를 자동으로 늘리는 것이 실제 병목을 해결하는지 별도로 판단할 필요가 있었다.

## 문제

Auto Scaling을 먼저 도입하면 실제 병목이 DB Connection Pool·Lock·Query·공용 Redis 등 하위 계층에 있을 때 서버 수만 늘려 병목을 악화시킬 수 있다.

따라서 다음 질문을 실측으로 확인해야 했다.

> 현재 Active App EC2 2대가 먼저 포화되는가, 아니면 App보다 다른 계층의 병목이 먼저 나타나는가?

## 고려한 대안

1. **즉시 Auto Scaling 도입** — CPU 등 임의 임계치로 ASG/Scaling Policy를 구성한다.
2. **Active 2대를 유지하고 병목을 먼저 측정** — App CPU·RDS CPU·Hikari Active/Pending·ALB 분산을 같은 부하에서 확인한다.
3. **고정 서버 수만 늘림** — 근거 없이 3~4대를 상시 운영한다.

## 결정

대안 2를 채택하고, 현재 프로젝트 범위에서는 **App Auto Scaling을 도입하지 않는다.**

운영 방향은 다음과 같다.

- Active App EC2 2대 유지
- Hikari `maximumPoolSize=12` 유지
- Inactive Blue-Green EC2는 평상시 STOP
- 배포 시 Inactive 환경을 START하고 전환·검증·Rollback Window 이후 이전 Active를 조건부 STOP
- 향후 App CPU/처리량 포화가 실제 지표로 확인될 때만 Auto Scaling 재검토

## 측정 근거

Pool 10 기준 Stress Test에서:

- App CPU 약 20~40%로 여유
- RDS CPU 약 20%대
- ALB 요청은 두 App에 비슷하게 분산
- Hikari Active 10/10 도달
- Hikari Pending 약 40~60 증가
- HTTP 실패율 0%
- p95 35.4ms / p99 358.79ms / max 5.36s / dropped iterations 417

즉 먼저 관측된 병목은 App CPU가 아니라 Hikari Connection Pool 대기였다.

평상시 Inactive Blue-Green EC2를 STOP하여 RDS `Threads_connected`가 약 45→26으로 감소하고 Inactive App의 약 20개 Connection 점유가 제거됐다. 이 효과는 **불필요한 DB Connection과 EC2 비용 제거**로 해석하며 API 성능 개선 원인으로 확대하지 않는다.

Hikari Pool을 10→12로 조정한 뒤 같은 Stress Test를 두 차례 재현했다.

| 지표 | Pool 10 | Pool 12 1차 | Pool 12 재현 |
|---|---:|---:|---:|
| HTTP 실패율 | 0% | 0% | 0% |
| p95 | 35.4ms | 29.8ms | 22.42ms |
| p99 | 358.79ms | 111.85ms | 94.55ms |
| Dropped Iterations | 417 | 18 | 40 |
| Hikari Pending | 약 40~60 | 거의 0 | 거의 0 |

Pool Size 변경 하나만으로 전체 개선이 발생했다고 단정하지 않는다. 다만 같은 조건에서 개선 경향이 반복되고 App CPU 포화 근거는 확인되지 않았으므로, 현재 Auto Scaling의 추가 복잡도와 비용을 정당화할 근거가 부족하다고 판단했다.

## 선택 이유

Auto Scaling은 서버 수를 자동으로 늘리는 기능 자체가 목적이 아니라 **App 처리 용량 부족이라는 실제 병목을 해결할 때** 도입해야 한다.

현재 실측에서는 App CPU보다 Connection Pool 대기가 먼저 나타났고, Pool/Blue-Green 운영 개선 후 현재 Active 2대 구조로 충분한 범위가 확인됐다. 따라서 측정되지 않은 미래 트래픽을 가정해 ASG를 먼저 운영하지 않는다.

## 장점

- 불필요한 ASG/Launch Template/Scaling Policy 운영 복잡도를 피한다.
- 서버 수 증가로 RDS Connection Budget을 더 압박하는 위험을 피한다.
- 현재 Evidence가 있는 병목부터 개선한다.
- Auto Scaling 미도입 자체를 수치 근거가 있는 기술 결정으로 남긴다.

## 단점과 위험

- 실제 트래픽이 현재 측정 범위를 넘어 App CPU/처리량을 포화시키면 Active 2대만으로 부족할 수 있다.
- 갑작스러운 짧은 burst는 Reactive Scaling의 기동 시간보다 빠를 수 있으며 별도 진입 제어가 필요할 수 있다.
- 이번 실험은 실제 운영 트래픽 전체를 대표하지 않는다.

## 검증 방법

- Active App 2대 대상 동일 Stress Test
- App CPU / RDS CPU / ALB 분산 / Hikari Active·Pending·Acquire latency 동시 관측
- Inactive EC2 STOP 전후 RDS Connection 사용 확인
- Hikari Pool 12 적용 후 동일 부하 재현
- Blue-Green Guardrail 회귀 검증

## 재검토 조건

다음 중 하나가 반복 측정될 때 Auto Scaling을 다시 검토한다.

- App CPU 또는 App 처리량이 먼저 포화
- HTTP p95/p99 악화 원인이 App 처리 용량 부족으로 분리됨
- DB Pool·RDS CPU·Lock·Redis 등 하위 의존성은 여유가 있는데 요청 대기/5xx가 증가
- Active 2대 대비 3대 이상에서 같은 조건의 처리량·응답시간 개선이 확인

재검토 시에는 RDS `max_connections`, Redis/외부 API 한도, 신규 Target Healthy까지 걸리는 시간과 추가 비용을 함께 본다.
