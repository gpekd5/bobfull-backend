# Experiment Lab Evidence

| Experiment | 실제 채택 근거 | Test | 비교용 가상 대안의 의미 | 한계 |
|---|---|---|---|---|
| 마지막 좌석 | `ReservationPreparationService.prepare`, Reservation → TimeSlot 잠금, develop `a0d5195` | `ReservationPreparationConcurrencyIntegrationTest` | 락 없음·단순 잠금은 결과 비교 모델 | 락 wait·K6 수치 없음 |
| 취소·환불 | `ReservationCancellationService`, `RefundCompletionService`, develop `a0d5195` | `RefundTransactionIntegrationTest`, refund service tests | 긴 Transaction·전체 일괄 처리는 비교 모델 | 운영 PortOne E2E·자동 재환불 없음 |
| ChatRoom | `ChatRoomCreationEventListener.handle`, AFTER_COMMIT, develop `a0d5195` | `ChatRoomCreationEventListenerTest` | 핵심 트랜잭션 내부 저장은 비교 모델 | 조회 시 복구를 제외한 운영 재처리 없음 |
| 시간 경계 | `ReservationClosingProcessor`, `ChatMessageCommandService`, develop `a0d5195` | closing/chat command tests | Scheduler 완료 뒤 차단은 비교 모델 | 스케줄러 대량 지연 실측 없음 |
| 이메일 후속 처리 경계 | PR #177 Head `33b4036`의 `NotificationAsyncConfig`, `RecruitmentDeadlineNotificationEventListener`, `ReservationPaymentCompletionNotificationEventListener` | `RecruitmentDeadlineNotificationIntegrationTest`, 두 listener test, `NotificationAsyncConfigTest` | Transaction 내부 SMTP·Commit 뒤 동기 SMTP는 비교 모델, Outbox/Kafka는 V3 후속 | `AFTER_COMMIT`은 커밋 경계, `@Async`가 비동기 경계. 서버 종료 시 메모리 Event/Async 유실과 포화 시 discard-and-log task drop은 별개 한계 |
