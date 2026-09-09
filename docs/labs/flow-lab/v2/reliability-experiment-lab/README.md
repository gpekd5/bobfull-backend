# Reliability Experiment Lab

이 Lab은 실제 정상 흐름 재생기가 아니다. 5개 실험에서 `develop merged · 실제 채택`, `open PR basis · 실제 채택`, `비교용 가상 대안`, `V3 후속 개선`을 구분한다.

1. 마지막 좌석 동시성·락 순서
2. 취소·환불 Transaction 경계
3. ChatRoom AFTER_COMMIT 생성
4. Scheduler와 요청 시점 시간 경계
5. 이메일 후속 처리 경계

Experiment 5의 현재 채택안은 PR #177 Evidence Head `33b4036`의 Event → AFTER_COMMIT → bounded @Async → SMTP다. Executor는 core 2/max 8/queue 200이며 포화 시 CallerRuns 없이 task를 discard-and-log한다(`NotificationAsyncConfigTest`). 서버 종료로 인한 메모리 Event/Async 유실과 포화로 인한 의도적 task drop은 별개 V2 한계다. Transactional Outbox/Kafka는 두 한계를 완화하는 V3 후속 개선이며 현재 V2 구현이 아니다. 가상 대안은 실제 BobFull Java 코드나 성능 측정 결과가 아니다. K6, 검색 Redis cache, Prometheus/Grafana 수치는 V2의 완료 결과로 표시하지 않는다.
