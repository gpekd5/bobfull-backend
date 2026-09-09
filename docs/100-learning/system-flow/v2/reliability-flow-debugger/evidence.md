# Flow Debugger Evidence

## Evidence 상태

- `develop merged`: `origin/develop`에 병합된 실제 코드·테스트
- `open PR basis`: 아직 미병합이지만 현재 PR Head의 실제 코드·테스트
- `OPEN PR TARGET · IN PROGRESS`: Human 확정 목표지만 아직 해당 PR Head에 구현되지 않은 구조
- `가상 대안` / `후속 개선` / `측정 전`: 실제 현재 구현과 구분한 비교·향후·미측정 항목

PR #177 Evidence 기준은 `33b403649a3c093719b97644ab4a1edb8d140d8b`이다. Event, AFTER_COMMIT listener, 전용 bounded `emailTaskExecutor`, 관련 테스트가 이미 구현되어 있어 아래 이메일 항목은 `OPEN PR TARGET · IN PROGRESS`가 아니라 `open PR basis`다. `NotificationAsyncConfigTest`는 포화 거부 task가 호출 스레드에서 실행되지 않고 `RESERVATION_NOTIFICATION_TASK_REJECTED` ERROR를 남기며 CallerRunsPolicy를 쓰지 않음을 검증한다. 서버 종료 시 메모리 Event/Async 작업 유실과 Executor 포화 시 의도적 task drop은 서로 다른 V2 한계다. 다음 PR #177 Head 변경 또는 Merge 시 Class·Test·SHA를 다시 동기화한다.

| Scenario | Evidence type / SHA | 실제 Class · Method | 관련 Test | 확인 결과 · 한계 |
|---|---|---|---|---|
| Ch1 Refresh Token·logout | develop merged `a0d5195` | `AuthService.reissue`, `AuthService.logout`, `RefreshTokenStore.rotate/deleteByMember` | `AuthServiceTest`, `RefreshTokenStoreIntegrationTest` | Redis TTL·회전·로그아웃 삭제. 실제 Redis 통합 테스트는 환경 변수 선택 실행 |
| Ch2 좌석 경쟁 | develop merged `a0d5195` | `ReservationPreparationService.prepare`, `findReservationWithLockOrThrow` | `ReservationPreparationConcurrencyIntegrationTest` | Reservation → TimeSlot 고정 락 순서·가용 인원 재확인 |
| Ch3 완료 확정 | develop merged `a0d5195` | `PaymentCompletionTransactionService.complete`, `ReservationConfirmationService.confirm` | `PaymentCompletionIdempotencyIntegrationTest`, `PaymentReservationConfirmationTransactionIntegrationTest` | Payment·Reservation 확정 원자성 |
| Ch3 CREATE/JOIN 접수·참여 이메일 | open PR basis #177 `33b4036` | `ReservationConfirmationService.confirm`, `ReservationPaymentCompletionNotificationEventListener.handle`, `NotificationAsyncConfig` | `ReservationConfirmationServiceTest`, `ReservationPaymentCompletionNotificationEventListenerTest`, `NotificationAsyncConfigTest` | CREATE 접수는 `RECRUITING`일 수 있어 최종 확정과 구분. bounded executor(core 2/max 8/queue 200)는 포화 시 task를 버리고 ERROR 로그를 남겨 호출 스레드를 막지 않음 |
| Ch4 다중 참여자 취소·환불 | develop merged `a0d5195` | `ReservationCancellationRefundAdapter.requestRefunds/requestFromPortOne`, `RefundReconciliationScheduler/Processor` | `RefundTransactionIntegrationTest`, reconciliation tests | A/C 성공은 보존. B 명시적 PG 실패는 `FAILED`로 종료되어 자동 재조정 제외·운영 확인, 결과 불명/timeout은 `REQUESTED` 유지 후 scheduler 재조회. 운영 PortOne E2E는 미확인 |
| Ch5 ChatRoom/STOMP | develop merged `a0d5195` | `ChatRoomCreationEventListener.handle`, `ChatStompInterceptor`, `ChatMessageCommandService.send`, `ChatOutboundAuthorizationInterceptor` | listener/interceptor/command tests | CONNECT·SUBSCRIBE·SEND·outbound 차단·endAt을 분리. 실제 STOMP E2E는 제한 |
| Ch6 마감·CLOSED·노쇼 해제·로그 | develop merged `a0d5195` | `ReservationClosingProcessor`, `NoShowService.unmarkNoShow` | closing/no-show integration tests | PR #179 구조화 로그도 merged. Grafana 등은 미구현 |
| Ch6 모집 마감 결과 이메일 | open PR basis #177 `33b4036` | `ReservationCancellationTransactionService.acceptRecruitmentDeadline`, `RecruitmentDeadlineNotificationEventListener`, `RecruitmentDeadlineCancellationService.process`, `NotificationAsyncConfig` | `RecruitmentDeadlineNotificationIntegrationTest`, `RecruitmentDeadlineNotificationEventListenerTest`, `RecruitmentDeadlineCancellationServiceTest`, `NotificationAsyncConfigTest` | Event는 상태 Transaction 안에서 발행하고 COMMIT 뒤 bounded executor에서 이메일 처리. 환불 예외는 Scheduler 경로에 유지되며 이메일 시도와 독립. 서버 종료 유실과 포화 task drop은 분리 |
