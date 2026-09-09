# ADR 0017: AI Consumer를 별도 Worker/MSA로 분리하지 않고 통합 실행 유지

- 상태: `Accepted`
- 작성일: `2026-08-18`
- 결정 유형: `MEASURED_AND_REJECTED — Worker/MSA 분리 미도입`
- 관련 Issue·PR: #192, PR #243
- 주요 Evidence: `docs/evidence/v3/192-ai-worker-scaling/README.md`

## 배경

BobFull의 AI Moderation은 ChatMessage + Outbox Commit 이후 Kafka Consumer가 `ChatModerationService`를 호출하는 구조다. 초기에는 HTTP/WebSocket, Outbox Kafka Publisher, AI Consumer가 하나의 Spring Boot 실행 단위 안에 함께 존재한다.

AI 부하가 커지면 외부 LLM 대기, Consumer Thread, Retry, DB 처리 등이 Web/API와 자원을 경쟁할 수 있고, 반대로 Web 서버를 Scale-out할 때 AI Consumer 수도 불필요하게 함께 늘 수 있다. 따라서 Consumer를 별도 실행 단위로 분리할 필요가 있는지 측정했다.

## 문제

다음 세 가지 구조 중 현재 프로젝트에 필요한 경계를 결정해야 했다.

### A. 통합 Spring Boot 유지

```text
BobFull App
├ REST / WebSocket
├ Outbox Kafka Publisher
└ Kafka AI Consumer
```

### B. 같은 코드베이스에서 실행 역할 분리

```text
bobfull-web
├ REST / WebSocket
├ ChatMessage + Outbox
└ Outbox Kafka Publisher

bobfull-ai-worker
├ Kafka AI Consumer
├ ChatModerationService
└ AI Adapter
```

### C. 별도 AI Moderation MSA

독립 서비스 계약·릴리스·운영·데이터 소유 경계까지 분리한다.

## 결정

현재는 **A안, 통합 Spring Boot 실행을 유지한다.**

B안은 같은 코드베이스에서 Web과 AI Consumer의 실행 역할만 분리하는 후보로 남기지만, 현재 측정에서 분리 착수 기준이 확인되지 않아 구현하지 않는다. C안 MSA는 더 강한 독립 배포·팀 소유권·데이터 소유권 요구가 생기기 전까지 도입하지 않는다.

Consumer를 분리하더라도 Outbox Kafka Publisher는 Web/Core에 남겨야 한다. Outbox는 ChatMessage DB Transaction에서 생성된 producer-side 전달 의도이고, AI Worker는 이미 Kafka에 들어온 이벤트를 소비하는 consumer-side 책임이기 때문이다.

## 측정 근거

#192에서는 Fake AI와 경량 workload를 사용해 현재 통합 구조의 영향과 Consumer 확장 가능성을 확인했다.

- Fake AI latency를 100ms → 1s → 3s로 늘려도 `ChatMessageCommandService.send()` p95가 약 12~18ms 범위로 관측되어 AI 처리 지연이 application service send 경로에 직접 전파되지 않았다.
- 이 값은 실제 HTTP/STOMP end-to-end p95가 아니라 application service 측정값이다.
- Consumer 중단/복구 시 15/15 메시지가 처리되고 유실 0건을 확인했다.
- 반복 실패 시 5/5가 Retry/DLT 경계로 격리되는 것을 확인했다.
- Consumer concurrency와 Partition 분포에 따라 처리량이 달라짐을 확인했으며, 후속 #258에서 Moderation key를 `messageId`로 변경해 Hot-Key를 줄였다.
- 실제 OpenAI Provider 429/Rate Limit, production peak 규모, CPU/Heap/DB Pool 경쟁은 이 실험에서 직접 측정하지 않았다.

Human 기준으로 다음 중 하나가 반복 측정될 때 B안 실험을 시작하기로 했다.

- Consumer Lag이 피크 종료 후 5분 이상 0으로 회복되지 않고 누적
- AI 처리 p99가 3초를 반복 초과
- Kafka 유입 증가 시 HTTP/WebSocket p95가 20% 이상 함께 악화

현재 Evidence에서는 위 분리 필요성을 확정할 근거가 부족했다.

## Consumer 분리와 MSA의 구분

같은 Git Repository와 코드베이스·공통 DB를 사용하면서 profile/실행 역할만 나눠 독립 배포·인스턴스 수 조절을 하는 것은 **독립 Worker**이지 자동으로 MSA가 아니다.

MSA는 추가로 다음 수준의 독립성이 실제로 필요할 때 검토한다.

- 독립 릴리스 주기
- 명확한 서비스 계약
- 별도 팀/소유권
- 데이터 소유권 분리 필요
- 공통 DB가 변경·장애 격리를 실제로 방해

## 선택 이유

분리는 기술적으로 가능하다는 이유만으로 적용하지 않는다. 현재 구조에서 AI 지연이 핵심 send 경로를 직접 지연시키는 현상이 확인되지 않았고, Kafka 자체가 Consumer 장애·적체를 Core/Web 요청과 분리하는 경계를 제공한다.

별도 Worker를 추가하면 배포 대상, Health Check, 설정, 로그·메트릭, 비용과 운영 복잡도가 늘어난다. 현재 문제를 해결하는 데 필요하다는 실측 근거가 부족하므로 통합 구조를 유지한다.

## 장점

- 배포·설정·관측 대상이 단순하다.
- 현재 서비스 규모에서 별도 Worker 인프라 비용이 없다.
- Outbox + Kafka로 요청 경로와 AI 처리 실패 경계는 이미 분리되어 있다.
- 실제 필요가 생기면 Consumer enable flag와 실행 역할을 기준으로 B안으로 발전시킬 수 있다.

## 단점과 위험

- AI 부하가 커지면 Web/API와 Consumer가 CPU·Thread·DB Pool을 경쟁할 가능성이 남는다.
- Web 인스턴스 수와 Consumer 수가 같은 실행 단위에 묶여 있다.
- 실제 Provider Rate Limit·비용·production peak 조건은 별도 운영 관측이 필요하다.

## 검증 방법

- AI latency 변화에 따른 send application service latency 비교
- Consumer 중단 후 backlog 복구와 유실 확인
- 반복 AI 실패의 Retry/DLT 격리 확인
- Consumer concurrency/Partition 관계 측정
- Spring AI 내부 Retry 1 × Kafka Retry 3으로 숨은 Retry 증폭 없음 확인

## 재검토 조건

- 위 분리 착수 기준 중 하나 이상이 운영/부하 측정에서 반복 확인될 때
- Web Scale-out과 AI Consumer Scale-out을 서로 다른 지표로 운영해야 할 때
- AI Consumer의 독립 배포 주기가 Core/Web과 실제로 달라질 때
- 별도 팀·데이터 소유권 등 MSA 수준의 경계가 필요해질 때

재검토 시에는 B안 독립 Worker부터 실험하고, B안으로 충분하면 MSA로 확대하지 않는다.
