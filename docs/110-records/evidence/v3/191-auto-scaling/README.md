# Issue #191 Auto Scaling 필요성 검증 Evidence

## 검증 대상

#191은 App EC2 Auto Scaling을 바로 적용하는 작업이 아니라, 현재 Active App EC2 2대가 실제 운영 부하에서 어디서 먼저 병목을 만드는지 확인한 뒤 Auto Scaling 필요 여부를 판단하는 작업이다.

이번 PR은 다음 세 가지 흐름을 함께 검증했다.

- Active App EC2 2대의 App CPU, RDS CPU, ALB 분산, Hikari Connection Pool 병목 확인
- 평상시 Inactive Blue-Green App EC2가 점유하던 불필요한 RDS Connection 제거
- Hikari `maximumPoolSize=12` 적용 후 동일 Stress Test 개선 경향 재현 확인

## 측정 계약

- Primary KPI: Hikari Active/Pending, Connection Acquire Latency, Stress Test latency와 dropped iterations
- Secondary KPI: App CPU, RDS CPU, RDS `Threads_connected`, DatabaseConnections, ALB 요청 분산
- 안전 확인: Blue-Green 배포의 START/STOP, Target Group health, ALB traffic switch, public API 검증, Prometheus Active target 갱신/UP 확인, 실패 시 이전 Active를 멈추지 않는 조건 유지

## 기준 코드

- Before SHA: `cdc7f5b1008cb8ac5987d9c8dbebf900f2efdb07`
- 구현 검증 기준 SHA: `90e9d096593ecf26be9167200898baa6f5bb2309`
- PR: #276

## 환경·데이터·실행 조건

| 항목 | 값 |
|---|---:|
| RDS `max_connections` | 60 |
| Active App EC2 | 2대 |
| Inactive App EC2 | 평상시 STOPPED |
| 기존 Hikari `maximumPoolSize` | 10 |
| 최종 Hikari `maximumPoolSize` | 12 |
| Parameter Store | `/bobfull/prod/db-pool-max-size=12` |

`scripts/aws/deploy-backend-v1.sh`는 `DB_POOL_MAX_SIZE:db-pool-max-size`를 optional parameter로 주입한다. Parameter Store 값이 없으면 env에 쓰지 않고 `application-prod.yml`의 `${DB_POOL_MAX_SIZE:10}` 기본값을 사용한다.

## Pool 10 기준 검증

ALB 뒤 Active App EC2 2대를 대상으로 k6 Stress Test를 수행했다.

- App CPU는 약 20~40% 수준으로 여유가 있었다.
- RDS CPU는 약 20%대 수준으로 여유가 있었다.
- ALB 요청 분산은 두 App에서 비슷하게 확인됐다.
- Hikari Active가 10/10까지 도달했다.
- Hikari Pending이 약 40~60까지 증가했다.
- Connection Acquire 지연이 발생했다.

Pool 10 Stress Test 결과:

| 지표 | 결과 |
|---|---:|
| HTTP 실패율 | 0% |
| avg | 35.87ms |
| p95 | 35.4ms |
| p99 | 358.79ms |
| max | 5.36s |
| Dropped Iterations | 417 |
| max VU | 300 |

현재 부하에서 먼저 관측된 병목은 App CPU 부족이 아니라 Hikari Connection Pool 대기였다.

## Inactive Blue-Green Connection 제거 검증

기존 PROCESSLIST에서는 Active App 2대뿐 아니라 Inactive Blue-Green App 2대도 각각 Hikari Connection을 유지했다.

```text
Active App 2대 x Hikari 10 = 최대 20
Inactive App 2대 x Hikari 10 = 최대 20
App 최대 Connection = 약 40
```

Inactive 환경은 사용자 트래픽을 받지 않지만 Spring Boot/Hikari가 실행 중이면 DB Connection을 유지한다. 따라서 RDS `max_connections=60` 상태에서 Auto Scaling으로 App을 늘리기 전에 Inactive 환경의 불필요한 Connection 점유를 먼저 제거했다.

확인 결과:

- RDS `Threads_connected` 약 45 -> 26
- Inactive App Connection 약 20 -> 0
- 직접 확인된 효과는 불필요한 DB Connection 제거와 EC2 비용 절감이다.

Inactive EC2 STOP이 API 응답시간을 개선했다고 단정하지 않는다. 동일 Stress Test에서 Active Hikari 10/10 및 Pending 증가가 다시 발생했으므로, Inactive Connection 문제와 Active App Pool 병목은 별개로 해석한다.

## Blue-Green 운영 안전 조건

최종 Blue-Green 흐름은 다음과 같다.

```text
Inactive START
-> EC2 Running 확인
-> SSM Online 확인
-> Backend 배포
-> Readiness 확인
-> Target Group Healthy
-> ALB Traffic 전환
-> Public API 검증
-> 신규 Active EC2 확인
-> Prometheus Target 변경
-> 신규 Active 2대 UP 확인
-> 600초 Rollback Window
-> STOP 직전 ALB Listener weight 재조회
-> 기존 Active STOP
```

실패 시 처리 기준:

- Inactive EC2 START, EC2 running 대기, SSM Online 대기, 배포 또는 Target Group health 검증이 실패하면 Listener traffic을 전환하지 않는다.
- ALB 전환 확인, public readiness 또는 public API 검증이 실패하면 기존 Listener default action으로 rollback한다.
- Public 검증 실패 시점에는 Prometheus target을 아직 변경하지 않으므로 이전 Active target이 유지된다.
- Prometheus target 전환 또는 신규 Active 2대의 UP 검증에 실패하면 기존 Active EC2를 STOP하지 않는다.
- Rollback Window 종료 후 기존 Active EC2를 STOP하기 직전에 ALB Listener weight를 다시 조회한다.
- 신규 Active Target Group weight가 100이고 기존 Target Group weight가 0일 때만 기존 Active EC2를 STOP한다.
- Rollback Window 중 수동 rollback 등으로 Listener 상태가 바뀌면 현재 Blue/Green weight와 STOP을 건너뛴 이유를 로그에 남기고 기존 Active EC2 STOP을 건너뛴다.
- Stop 대상은 ALB 전환 이후 다시 계산하지 않고 배포 시작 시점에 저장한 기존 Active instance id만 사용한다.

## Active Hikari 병목 분석

Pool 10 환경에서 Hikari Usage 최대 약 710~780ms가 확인됐다. RDS Slow Query Log를 활성화한 뒤 고부하에서 여러 Query 지연도 확인했다.

대표 Payment / TimeSlot Query를 `EXPLAIN ANALYZE`로 확인한 결과:

- Payment Query 단독 실행 약 0.17ms
- TimeSlot Query 단독 실행 약 1.6ms
- 기존 Index 사용 확인

이번 검증에서는 대표 Query에서 명확한 Full Scan 또는 Index 누락 같은 구조적 병목을 확인하지 못했다. 따라서 큰 Query 리팩터링은 하지 않고 Hikari Pool Size 자체를 먼저 검증했다.

## Pool 12 검증

Hikari `maximumPoolSize`를 10에서 12로 조정했다.

- Parameter Store: `/bobfull/prod/db-pool-max-size=12`
- `deploy-backend-v1.sh`: `DB_POOL_MAX_SIZE:db-pool-max-size` optional parameter 주입
- 실제 컨테이너: `DB_POOL_MAX_SIZE=12` 적용 확인

Pool 12 1차 Stress Test 결과:

| 지표 | 결과 |
|---|---:|
| HTTP 실패율 | 0% |
| avg | 19.2ms |
| p95 | 29.8ms |
| p99 | 111.85ms |
| max | 646.19ms |
| Dropped Iterations | 18 |
| max VU | 68 |

Pool 12 재현 Stress Test 결과:

| 지표 | 결과 |
|---|---:|
| HTTP 실패율 | 0% |
| avg | 18.2ms |
| p95 | 22.42ms |
| p99 | 94.55ms |
| max | 635.24ms |
| Dropped Iterations | 40 |
| max VU | 90 |

Pool 12 모니터링:

- Hikari Pending 거의 0
- Acquire max 약 380ms
- Usage max 약 600~610ms
- RDS CPU 최대 약 29.4%
- DatabaseConnections 최대 약 44 / `max_connections=60`

## 주요 측정 결과 비교

| 항목 | Pool 10 | Pool 12 1차 | Pool 12 재현 |
|---|---:|---:|---:|
| HTTP 실패율 | 0% | 0% | 0% |
| avg | 35.87ms | 19.2ms | 18.2ms |
| p95 | 35.4ms | 29.8ms | 22.42ms |
| p99 | 358.79ms | 111.85ms | 94.55ms |
| max | 5.36s | 646.19ms | 635.24ms |
| Dropped Iterations | 417 | 18 | 40 |
| max VU | 300 | 68 | 90 |
| Hikari Pending | 약 40~60 증가 | 거의 0 | 거의 0 |

Inactive STOP의 직접 효과는 별도로 확인했다.

- RDS `Threads_connected` 약 45 -> 26
- Inactive App Connection 약 20 -> 0
- 이 변화는 불필요한 DB Connection 제거와 EC2 비용 절감 효과로 해석한다.
- Pool 12 응답시간 개선과 Inactive STOP을 하나의 원인으로 묶어 해석하지 않는다.

Pool Size가 10에서 12로 2개 증가한 것에 비해 성능 변화가 크게 나타났으므로, 전체 개선 효과를 Pool Size 변경 하나만의 영향이라고 단정하지 않는다. 대신 Pool 12 환경에서 두 차례 동일 테스트 결과 개선 경향이 재현됐다고 정리한다.

Hikari Active가 Pool 10의 10/10에서 Pool 12의 최대 약 2로 나타난 것도 Pool Size 변경만의 효과라고 단정하지 않는다.

## Auto Scaling 판단

현재 측정 결과만으로는 App EC2 Auto Scaling 적용 근거가 부족하다.

- Active App EC2 2대의 CPU는 약 20~40% 수준으로 여유가 있었다.
- RDS CPU와 Connection 사용량은 현재 테스트 부하 기준으로 한도에 도달하지 않았다.
- ALB 요청 분산은 정상적으로 확인됐다.
- 먼저 관측된 병목은 App CPU가 아니라 Hikari Connection Pool 대기였다.
- Inactive EC2의 불필요한 Connection 제거 후에도 Active App Pool 병목은 별도로 재현됐다.
- Pool 12 환경에서는 Pending이 거의 0에 가깝고 응답 지표 개선 경향이 두 차례 확인됐다.

따라서 Auto Scaling은 적용 실패나 취소가 아니라, 현재 부하 기준으로 App CPU/처리량 포화 근거가 부족해 보류한다.

## 최종 운영 방향

- Active App EC2 2대 구조를 유지한다.
- Hikari `maximumPoolSize=12`를 유지한다.
- Inactive Blue-Green EC2는 평상시 STOP 상태로 유지한다.
- 배포 시 Inactive EC2를 START하고, ALB 전환 및 Prometheus target UP 확인 후 기존 Active를 600초 Rollback Window 동안 유지한다.
- Rollback Window 종료 시 ALB Listener weight를 다시 확인해 신규 Active Target Group weight가 100이고 기존 Target Group weight가 0일 때만 기존 Active를 STOP한다.
- Prometheus target 전환 또는 신규 Active 2대의 UP 검증에 실패하면 기존 Active EC2를 STOP하지 않는다. 모니터링이 비어 있는 상태에서 되돌아갈 대상을 없애지 않기 위한 조건이다.
- 향후 App CPU 또는 처리량 포화가 실제 지표로 확인될 때 Auto Scaling을 재검토한다.

## 검증 한계

- Pool 10 -> 12만으로 전체 성능 개선이 발생했다고 단정하지 않는다.
- Hikari Active 10/10 -> 최대 약 2 변화 역시 Pool Size 변경만의 효과라고 단정하지 않는다.
- Inactive STOP이 API 응답시간을 개선했다고 단정하지 않는다.
- 대표 Query에서 명확한 구조적 병목을 확인하지 못했지만, 모든 고부하 Query 문제가 없다고 단정하지 않는다.
- Auto Scaling 정책, ASG, Launch Template, Scheduled Scaling은 이번 PR 범위가 아니다.
- 실제 Secret, DB 비밀번호, 토큰, 계정 Key는 기록하지 않는다.

## 관련

- Issue: #191
- PR: #276
- 선행 Evidence: `docs/110-records/evidence/v3/169-app-ha/README.md`
