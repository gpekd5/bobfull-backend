# Issue #183 — 이메일 Transactional Outbox Evidence

## 검증 대상

V2의 `AFTER_COMMIT + @Async`는 커밋 뒤 작업이 메모리에서 사라질 수 있고, 여러 수신자 중 일부 성공을 영속적으로 구분하지 못했다. V3는 #176의 공통 Outbox를 재사용해 발송 의도와 수신자별 성공을 DB에 남긴다.

| 구분 | V2 | V3 |
|---|---|---|
| 발송 의도 | 메모리 이벤트 | 핵심 상태와 동일 트랜잭션의 `OutboxEvent(PENDING)` |
| 부분 성공 | 재시도 근거 없음 | `email_outbox_delivery`의 `SENT` 보존, `PENDING`만 재시도 |
| 최종 실패 | 로그만 남김 | 공통 Outbox `FAILED`와 error code |
| SMTP 재시도 | Adapter 내부 즉시 3회 | Processor의 최초 1회 + 공통 Outbox 5회 backoff 재시도 |

## 공통 기반

#176 Evidence의 원자 Claim·stale 회수·backoff·`FAILED` 검증을 재사용한다. #183은 이메일별 수신자 멱등성과 SMTP 실패 격리에 집중한다.

공통 `outbox_event`에는 ChatRoom과 `EMAIL_*` 이벤트가 함께 저장된다. ChatRoom·Email Processor는 각각 due/stale 조회와 claim에 자기 eventType만 지정하며, 혼재된 PENDING·stale PROCESSING 회귀 테스트로 상대 이벤트 상태가 바뀌지 않음을 확인했다.

## 실행 명령

```bash
./gradlew :test --tests com.bobfull.payment.service.PaymentReservationConfirmationTransactionIntegrationTest --tests com.bobfull.reservation.service.RecruitmentDeadlineNotificationIntegrationTest --rerun-tasks
./gradlew :test --tests com.bobfull.outbox.service.EmailOutboxProcessorTest --tests com.bobfull.notification.adapter.SmtpReservationNotificationAdapterTest --rerun-tasks
```

## 한계

SMTP 요청 성공과 DB `SENT` 기록 사이에 프로세스가 종료되는 극소 구간까지 포함해 외부 SMTP가 정확히 한 번만 보내진다고 말할 수는 없다. 다만 DB에 성공 기록된 수신자는 중복 발송하지 않으며, 실제 SMTP 대량 장애 실험은 수행하지 않는다.
