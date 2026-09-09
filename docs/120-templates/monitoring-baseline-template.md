# BobFull 단일 EC2 모니터링 기준 데이터 기록 양식

## 측정 환경

| 항목 | 값 |
|---|---|
| 측정일시 |  |
| App EC2 유형 |  |
| Monitoring EC2 유형 |  |
| DB 위치·유형 |  |
| Redis 위치·유형 |  |
| 배포 Commit SHA |  |
| Spring Profile | prod |
| k6 실행 위치 |  |

## k6 조건

| 항목 | 값 |
|---|---|
| 대상 Base URL |  |
| 시나리오 |  |
| VU 수 |  |
| 지속 시간 |  |
| 주요 API |  |
| 인증 사용 여부 |  |
| 테스트 데이터 조건 |  |

## 결과

| 지표 | 값 | 확인 위치 |
|---|---:|---|
| 전체 요청량 |  | k6 / Grafana |
| 평균 응답시간 |  | k6 / Grafana |
| p95 응답시간 |  | k6 / Grafana |
| HTTP 오류율 |  | k6 / Grafana |
| 5xx 발생 수 |  | Grafana |
| JVM CPU 사용률 |  | Grafana |
| JVM Heap 사용률 |  | Grafana |
| DB Active Connection |  | Grafana |
| DB Idle Connection |  | Grafana |
| DB Pending Connection |  | Grafana |
| `LOGIN_FAILED` 테스트 증가 |  | Grafana |
| 1순위 비즈니스 테스트 Alert |  | Grafana / Slack |

## 관찰과 조정 후보

- 

## 후속 비교 조건

ALB + EC2 2대 등으로 확장할 때 같은 k6 조건으로 재측정한다. API 경로, VU, 지속 시간, 테스트 데이터가 달라지면 이 표에 차이를 기록한다.
