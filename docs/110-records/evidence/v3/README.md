# BobFull V3 Evidence Standard

## 목적

V3에서는 기술을 도입했다는 사실보다 **왜 도입했고, 실제로 무엇이 달라졌는지 재현 가능한 근거로 남기는 것**을 우선한다.

따라서 성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI 등 고도화 효과를 주장하는 Issue/PR은 가능한 범위에서 다음 흐름을 따른다.

```text
현재 구조 확인
→ Before 문제 재현 또는 기준값 측정
→ 개선 구현
→ 같은 조건 After 재검증
→ 정합성 회귀 확인
→ Evidence 기록
→ PR/ADR/Troubleshooting에서 근거 연결
```

단순 CRUD·DTO·문서·설정처럼 Before/After 비교가 의미 없는 변경에는 억지 숫자를 만들지 않는다. 이 경우 `NOT_APPLICABLE` 이유와 정상·실패·경계 검증으로 대체한다.

## 고도화 Issue의 측정 계약

고도화 Issue는 구현 전에 **무엇을 성공으로 볼지** 먼저 정한다. 가능한 경우 다음 세 축을 Issue에 명시한다.

```text
Primary KPI
→ 이번 개선의 성공 여부를 가장 직접적으로 보여주는 핵심 지표·현상

Secondary KPI
→ 병목 원인과 부작용을 해석하기 위한 보조 지표

안전 확인
→ 빨라지거나 안정화되는 과정에서 기존 기능·정합성·멱등성·오류율이 깨지지 않았는지 확인하는 지표
```

예시:

```text
SQL/Index
Primary KPI: 조회 API p95
Secondary KPI: query time, rows examined, DB Pool
안전 확인: 응답 데이터 동일, 오류율 증가 없음

Kafka AI
Primary KPI: Kafka 장애 중 Outbox 보존·복구 성공 건수와 Consumer Lag 회복
Secondary KPI: Chat SEND p95, AI 처리 p95, consume rate, Retry/DLT, token/cost
안전 확인: ChatMessage 유실 0, Moderation 중복 저장 0
```

모든 Issue에 세 항목을 억지로 채우지 않는다. 정량 KPI보다 정상·실패·경계 현상이 더 중요한 신뢰성 문제는 `PENDING 보존`, `재시작 복구`, `중복 side effect 0건`처럼 검증 가능한 현상을 Primary KPI로 둘 수 있다.

## Evidence 유형

| 유형 | 우선 확인할 Evidence |
|---|---|
| 성능 | p50/p95/p99, 처리량, 오류율, 쿼리 수, DB Pool, CPU·메모리 |
| 신뢰성 | 이벤트 유실, 재시작 복구, 재시도, 최종 실패, 중복 처리 |
| 동시성 | 초과 처리, 중복 생성, 락 대기, deadlock, timeout |
| 인프라 | 장애 전환, 실패 요청, Target Health, 배포 중 요청 연속성 |
| 캐시 | 응답시간·DB 부하 + stale/TTL/무효화/최종 DB 검증 |
| Kafka/Outbox | Consumer Lag, 처리 대기량, 처리량, Retry/DLT, 멱등성, 장애가 다른 처리로 번지는지 |
| AI | 고정 입력셋 결과 + timeout/5xx/잘못된 응답 시 기본 서비스 영향 |
| 보안/제약 | 우회 재현 Before → 차단 After |
| 단순 기능 | 정상·실패·경계 계약. 정량 Before/After는 N/A 가능 |

## 동일 조건 원칙

Before/After는 가능한 한 다음 조건을 맞춘다.

- 애플리케이션 인스턴스 수와 CPU·메모리
- DB 종류·버전·Connection Pool
- 테스트 데이터 규모와 상태
- K6 VU·arrival rate·duration 또는 동시성 조건
- 워밍업·반복 횟수
- Redis/Kafka/Outbox 등 의존성 설정
- Fake/Stub/Mock/Sandbox/실서비스 사용 범위

서로 다른 환경의 결과를 직접 개선율로 비교하지 않는다. 조건이 달라졌다면 그 차이를 명시하고 별도 참고 결과로 취급한다.

## 완료 작업의 수치 소급 금지

과거 작업이 이미 완료됐는데 반복 실험·실환경 장애·성능 측정을 실제로 수행하지 않았다면, 나중에 포트폴리오를 위해 임의의 비율이나 평균값을 만들어 넣지 않는다.

예:

```text
실제로 1회 결정론 테스트만 수행
→ "유실률 100% → 0%"라고 확대 작성 금지

실제로 N=100 반복 장애 실험을 추가 수행
→ 별도 Evidence에 환경·반복 횟수·결과를 기록한 뒤 정량 결과 사용 가능
```

기존 Evidence에 `실제 JVM kill/restart 반복 미수행`, `실서비스 미검증` 같은 한계가 적혀 있으면 그 한계를 유지한다. 추가 검증을 했다면 원래 결과를 덮어쓰지 말고 후속 검증 범위와 실행일·SHA를 구분해 기록한다.

## 권장 경로

Issue별 Evidence는 다음 경로를 권장한다.

```text
docs/110-records/evidence/v3/<issue-number>-<short-name>/README.md
```

예시:

```text
docs/110-records/evidence/v3/176-chatroom-outbox/README.md
docs/110-records/evidence/v3/183-email-outbox/README.md
docs/110-records/evidence/v3/191-auto-scaling/README.md
```

## Issue별 README 기본 양식

```markdown
# Issue #번호 제목 Evidence

## 검증 대상

## 측정 계약
- Primary KPI:
- Secondary KPI:
- 안전 확인:

## 기준 코드
- Before SHA:
- After SHA:

## 환경·데이터·실행 조건

## Before 결과

## 변경 내용

## After 결과

## 정합성 회귀 검증

## 구조화 로그·메트릭

## 결과 해석

## 검증 한계

## 관련
- Issue:
- PR:
- ADR:
- Troubleshooting:
```

필요하면 아래 결과 표를 추가한다.

```markdown
| 지표·현상 | Before | After | 판정 |
|---|---:|---:|---|
|  |  |  | PASS/FAIL |
```

## 기록 규칙

- 실행하지 않은 결과를 쓰지 않는다.
- 실제 측정 전 예시 숫자를 결과처럼 넣지 않는다.
- 재현하지 못한 문제를 `재현 성공`으로 쓰지 않는다.
- After가 좋아져도 기존 기능·정합성·멱등성이 깨지면 성공으로 판정하지 않는다.
- `유실 방지`, `무중단`, `확장성 개선` 같은 표현은 실제 검증 범위와 한계를 함께 적는다.
- Commit SHA와 실행 조건을 남겨 나중에 결과를 다시 해석할 수 있게 한다.
- 민감정보·JWT·Refresh Token·SMTP/PortOne/API 인증정보를 Evidence에 넣지 않는다.

## 원본 결과 저장

Git 저장소에 대용량 원본 로그를 무조건 Commit하지 않는다.

우선 저장할 것:

- 재현·실행 명령
- 주요 설정과 환경
- 핵심 결과 표
- 작은 CSV/JSON 결과
- 대표 로그 몇 줄
- 그래프가 필요하면 재생성 가능한 데이터 또는 생성 방법
- 결과 해석과 한계

대용량 로그·덤프가 필요하면 외부 보관 위치를 사용하고 저장소 문서에는 위치와 요약만 연결한다.

## PR 연결

PR 본문에는 Evidence 전체를 복사하지 않고 다음만 요약한다.

```text
Evidence 판정
Evidence 경로
Before/After SHA
동일 조건 여부
Primary KPI 핵심 Before/After
필요한 Secondary KPI
정합성 안전 지표 결과
검증 한계
```

## ADR·Troubleshooting·Operations Lab과의 관계

```text
Evidence
→ 실제로 무슨 일이 일어났는가

Troubleshooting
→ 어떤 문제를 발견했고 어떻게 원인을 좁혀 해결했는가

ADR
→ 여러 대안 중 왜 이 설계를 선택했는가

Operations Lab
→ 실제 구현·장애·복구·관측 흐름을 어떻게 이해하고 보여줄 것인가
```

같은 내용을 네 군데에 복사하지 않는다. 각 문서는 자기 목적에 맞게 요약하고 Evidence를 원본 근거로 연결한다.
