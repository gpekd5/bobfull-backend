# 트러블슈팅 기록

도메인별 기술 문제의 원인, 후보 해결안, 검증 상태를 누적한다.

## 기록 원칙

- 실제 해결과 검증이 끝난 항목은 근거를 함께 기록한다.
- 구현 전 예상 위험은 `검토 중`으로 기록하고, 해결된 것처럼 표현하지 않는다.
- 성능·신뢰성·동시성·인프라·캐시·Kafka/Outbox·AI처럼 **개선 효과를 주장하는 트러블슈팅은 `docs/110-records/evidence/v3/...`를 원본 근거로 연결**한다.
- 트러블슈팅 문서에는 Evidence 전체를 복사하지 않고 문제 이해에 필요한 핵심 Before/After 지표·현상과 해석만 요약한다.
- 응답시간이 의미 없는 문제에 억지로 ms를 붙이지 않는다. 문제 유형에 맞는 지표를 선택한다.
- 실행하지 않은 반복 실험이나 과거 완료 작업의 수치를 추정해 소급 작성하지 않는다. 추가 반복 검증을 실제로 수행한 경우에만 새 Evidence로 보강한다.

## Evidence와의 역할 구분

```text
Evidence
→ 실제로 어떤 조건에서 무엇이 발생했고 숫자·현상이 어떻게 달라졌는가

Troubleshooting
→ 그 문제를 어떻게 발견하고 원인을 좁혀 어떤 해결을 선택했는가
```

권장 흐름:

```text
Issue에서 측정 계약
→ Before 재현/기준값
→ 구현
→ 동일 조건 After 검증
→ Evidence에 원본 기록
→ Troubleshooting에 핵심 수치·원인·해결·한계 요약
→ PR/발표에서 같은 Evidence를 다시 인용
```

### 문제 유형별 대표 지표 예시

| 유형 | 대표 지표·현상 예시 |
|---|---|
| 성능/SQL | p50/p95/p99, 처리량, 오류율, 쿼리 시간, rows examined |
| 캐시 | p95/p99, DB Query 수, cache hit ratio, stale/TTL/무효화 |
| 동시성 | 요청 수, 성공/충돌/초과 처리 수, lock wait, deadlock, timeout |
| Outbox | PENDING/backlog, 복구 성공 수, Retry/FAILED, 중복 side effect |
| Kafka | Consumer Lag, consume rate, 처리 p95/p99, Retry/DLT, 중복 소비 |
| 알림 | 대상 수, 성공/실패/중복 발송 수, 재처리 성공 수 |
| 인프라 | 실패 요청 수, downtime, health 전환, scale-out 시간 |
| AI | 처리 latency, 오류율, Retry/DLT, token/cost, 기본 서비스 영향 |

위 표는 예시이며 실제 Issue의 문제와 성공 기준에 맞는 지표만 선택한다.

## 도메인 문서

- [결제 트러블슈팅](payment-troubleshooting.md)
- [예약 트러블슈팅](reservation-troubleshooting.md)
- [AI Moderation 트러블슈팅](ai-moderation-troubleshooting.md)

## 새 기록 작성

[트러블슈팅 양식](../../120-templates/troubleshooting-template.md)을 복사해 사용한다.
