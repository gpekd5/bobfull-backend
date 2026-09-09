# ADR 0013: ALB 기반 Blue-Green 배포 전략 채택

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 관련 Issue·PR: #169, #191, PR #257, PR #276
- 주요 Evidence: `docs/evidence/v3/169-app-ha/README.md`, `docs/evidence/v3/191-auto-scaling/README.md`

## 배경

단일 App EC2에서 기존 컨테이너를 종료한 뒤 새 컨테이너를 기동하는 배포 구조는 CI/CD 시간을 줄여도 애플리케이션이 실제 요청을 처리하지 못하는 구간이 남았다. 대표 측정에서 단일 EC2 배포 중 약 40.25초의 접근 불가 구간이 확인됐다.

또한 한 App EC2의 메모리 리소스 경쟁으로 Health Check·Prometheus·SSM까지 영향을 받은 경험을 통해, 한 인스턴스 문제가 App 계층 전체에 영향을 주는 구조적 한계를 확인했다.

이 두 문제는 구분한다.

- **배포 안전성**: 신규 버전을 Traffic 투입 전에 검증하고 실패 시 빠르게 되돌릴 수 있어야 한다.
- **실행 중 App 계층 가용성**: ALB 뒤 Active App EC2 2대 중 1대가 실패해도 다른 Healthy Target이 요청을 처리해야 한다.

Blue-Green은 첫 번째 문제인 배포 안전성을 해결하는 전략이고, 두 번째는 `ALB + Active App EC2 2대` 구성으로 확보한다. 두 개념을 같은 것으로 보지 않는다.

## 문제

새 버전을 배포하면서 다음 요구를 함께 만족해야 했다.

- 운영 트래픽을 받기 전에 신규 버전 Health/API 검증
- 배포 중 HTTP 요청 연속성 유지
- 신규 버전 검증 실패 시 기존 버전으로 빠른 Traffic Rollback
- 현재 프로젝트 규모에서 불필요한 상시 리소스 비용 최소화

별도로, 실행 중 App EC2 1대 장애 우회는 Active Target Group에 Healthy App EC2 2대를 두는 구성으로 검증한다.

## 고려한 대안

### Rolling

기존 인스턴스를 순차 교체한다.

- 추가 리소스가 상대적으로 적다.
- 구/신 버전이 실제 트래픽을 동시에 받을 수 있다.
- 실패 시 이미 교체한 인스턴스를 다시 되돌려야 한다.
- 신규 버전을 전체 Traffic Switch 전에 독립적으로 검증하기 어렵다.

### Blue-Green

신규 환경을 별도로 준비하고 검증한 뒤 ALB Traffic을 전환한다.

- 신규 환경을 운영 트래픽 전 사전 검증할 수 있다.
- 기존 환경을 유지한 채 Traffic만 되돌리는 Rollback 경계가 명확하다.
- 배포 동안 추가 App EC2가 필요하다.

## 결정

ALB Target Group weight를 이용한 Blue-Green 배포를 채택한다.

```text
Active Target Group 100
Inactive Target Group 0
        ↓
Inactive EC2 START / 신규 이미지 배포
        ↓
local readiness + Target Group Healthy
        ↓
ALB weight Active 0 / Inactive 100
        ↓
Public readiness + 핵심 API 검증
        ↓
Prometheus Target 전환 + 신규 Active 2대 UP 확인
        ↓
Rollback Window 유지
        ↓
Listener weight 재확인 후 기존 Active STOP
```

전환 후 Public 검증이 실패하면 배포 시작 전에 저장한 기존 Listener action으로 즉시 Rollback한다. Public 검증 또는 Prometheus Target 전환·신규 Active UP 검증이 실패한 상태에서는 기존 Active EC2를 STOP하지 않는다.

평상시에는 Active App EC2 2대만 유지하고 Inactive Blue-Green EC2 2대는 STOP 상태로 둔다. 배포 시에만 Inactive 환경을 START하여 추가 비용과 불필요한 DB Connection 점유를 제한한다.

이 `Inactive STOP`, Prometheus Target 전환 검증, STOP 직전 Listener weight 재확인 guardrail은 #169의 초기 Blue-Green 구성 이후 #191 / PR #276에서 최종 운영 방식으로 보강됐다.

## 선택 이유

BobFull은 단순히 배포 명령을 자동화하는 것보다 **신규 버전 사전 검증과 실패 배포의 Traffic Rollback을 실제로 검증할 수 있는 구조**를 우선했다.

실제 AWS Evidence에서 다음을 확인했다.

- Active App EC2 2대 Target Healthy
- App EC2 1대 backend 중지 후 외부 API 10/10 HTTP 200 — 이는 Blue-Green 자체가 아니라 `ALB + Active App 2대` Runtime HA 검증이다.
- 정상 Blue-Green 배포 중 public readiness 2,787/2,787 HTTP 200, 실패 0, 관측 다운타임 0초
- 의도적 Public API 검증 실패 후 Listener weight를 기존 Blue 100 / Green 0으로 자동 복구
- Rollback 전체 과정 중 public readiness 2,758/2,758 HTTP 200, 실패 0
- #191 후속 검증에서 Inactive App Connection 약 20 → 0, RDS `Threads_connected` 약 45 → 26을 확인하고 평상시 Inactive STOP을 최종 운영 방식으로 유지

Before와 After의 측정 위치가 완전히 동일하지 않으므로 40.25초 → 0초를 엄밀한 동일조건 개선율로 표현하지 않는다.

## Blue-Green, Runtime HA, Auto Scaling의 구분

- **Blue-Green**: 신규 버전 사전 검증과 Traffic Switch/Rollback을 위한 배포 전략이다.
- **Runtime App HA**: ALB가 Active App EC2 2대의 Health를 보고 Healthy Target으로 요청을 전달하는 구성이다.
- **Traffic Auto Scaling**: 활성 환경의 처리 용량을 부하에 따라 2→N으로 변경하는 별도 결정이며 ADR 0015에서 다룬다.

배포 중 Blue와 Green 양쪽 인스턴스가 동시에 존재하는 것을 Runtime HA나 Auto Scaling 성과로 계산하지 않는다.

## 장점

- 신규 버전을 실제 Traffic Switch 전에 검증할 수 있다.
- 실패 시 기존 환경으로 Traffic을 되돌리는 Rollback 경계가 빠르고 명확하다.
- 같은 ALB Public Endpoint를 유지한다.
- 평상시 Inactive 환경을 STOP해 상시 4대 운영 비용과 불필요한 DB Connection 점유를 피한다.

## 단점과 위험

- 배포 시 App EC2가 최대 4대까지 존재해 비용과 DB Connection Budget을 고려해야 한다.
- Blue/Green이 같은 RDS를 공유하므로 파괴적 Schema 변경은 App Rollback을 무력화할 수 있다. 이 문제는 ADR 0016에서 별도 관리한다.
- RDS Single-AZ, 단일 Kafka broker 등 다른 계층까지 포함한 전체 시스템 HA는 아니다.
- Blue-Green 자체가 App EC2 장애 우회를 제공하는 것은 아니다. 실행 중 장애 우회는 ALB 뒤 Active App 2대 구성에 의존한다.

## 검증 방법

- 정상 배포 중 public endpoint 연속 요청
- 신규 Target 2대 Health 검증 후 Traffic Switch
- 의도적 Public 검증 실패 후 자동 Rollback
- Rollback 중 public endpoint 연속 요청
- Prometheus Target 변경 및 신규 Active 2대 UP 확인
- 기존 Active STOP 전 Listener weight 재검증
- 별도 Runtime HA 검증으로 Active App Target 1대 장애 시 다른 Healthy Target으로 요청 지속 확인

Blue-Green 정상/rollback 수치는 #169 Evidence를, Inactive STOP·Prometheus 전환·STOP guardrail의 최종 운영 규칙은 #191 Evidence를 기준으로 한다.

## 재검토 조건

- 상시 Blue/Green 유지가 필요한 배포 빈도·복구 요구가 생길 때
- 배포 시간 추가 EC2 비용이 현재 방식의 이점을 넘을 때
- 컨테이너 오케스트레이션 플랫폼 등 배포 단위가 근본적으로 바뀔 때
