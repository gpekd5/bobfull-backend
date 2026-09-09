# Issue #176 — ChatRoom Transactional Outbox Evidence

## 검증 대상

`AFTER_COMMIT` 메모리 후속 처리만으로는 핵심 커밋 뒤 실행이 사라졌을 때 복구 근거가 없고, 같은 경계에서 Transactional Outbox는 DB `PENDING`을 남겨 후속 처리로 복구할 수 있는지 확인했다.

| 시나리오 | Before (V2 AFTER_COMMIT) | After (Transactional Outbox) | 판정 |
|---|---|---|---|
| AFTER_COMMIT ChatRoom 생성 실패 | Payment·Reservation·Participant는 커밋, ChatRoom 없음, 영속 Event 없음 | Payment·Reservation·Participant와 Outbox `PENDING`이 원자 커밋 | PASS |
| 새 처리 사이클 재처리 | DB에 복구 후보 없음 | Processor가 ChatRoom 1건 생성 후 `COMPLETED` | PASS |
| 중복 처리 | N/A | 기존 ChatRoom이 있어도 `COMPLETED`, 최종 1건 | PASS |
| 반복 실패 | N/A | 최초 처리 뒤 5회 재시도 후 다음 실패에서 `FAILED`, 자동 재시도 중단 | PASS |
| stale `PROCESSING` | N/A | 5분 초과 작업을 `PENDING`으로 회수 후 처리 | PASS |

## 실행 근거

- Before SHA: `b090a2ba1cd2375859f33417908769ab3c6f811b` (최신 develop)
- After SHA: `b3a5e4f2433728b26c3ceb653d4327493692f3f4`
- DB: H2 `MODE=MySQL`, `ddl-auto=create-drop`
- Profile: Spring Boot 통합 테스트, scheduler는 Outbox Processor 단독 테스트에서 비활성화하고 명시적으로 새 처리 사이클을 호출
- 장애 주입 위치: Before는 `AFTER_COMMIT` ChatRoom 생성 예외, After는 ChatRoom 생성 예외 또는 Processor 실행 전 PENDING 잔존

### Before 대표 실행 (1회)

명령:

```bash
/private/tmp/bobfull-176-before/gradlew -p /private/tmp/bobfull-176-before :test \
  --tests com.bobfull.payment.service.PaymentReservationConfirmationTransactionIntegrationTest.ChatRoom_생성_실패는_이미_커밋된_Payment_Reservation_Participant를_되돌리지_않는다 \
  --rerun-tasks
```

결과: `BUILD SUCCESSFUL`. baseline 테스트는 `AFTER_COMMIT`의 `createIfAbsent` 예외 뒤에도 Payment `PAID`, Reservation 1건, Participant 1건, ChatRoom 0건을 확인한다. 해당 baseline에는 Outbox 엔티티·저장소·재처리기가 없으므로 재처리할 영속 Event가 없다.

### After 대표 실행 (1회)

명령:

```bash
./gradlew :test --tests com.bobfull.outbox.service.ChatRoomOutboxProcessorIntegrationTest --rerun-tasks
./gradlew :test --tests com.bobfull.payment.service.PaymentReservationConfirmationTransactionIntegrationTest --rerun-tasks
```

결과: 두 명령 모두 `BUILD SUCCESSFUL`.

- `PENDING_이벤트를_처리하면_ChatRoom을_생성하고_COMPLETED로_기록한다`: 남은 PENDING을 새 Processor 호출이 읽어 ChatRoom 1건과 COMPLETED로 복구한다.
- `최초_처리_뒤_5회_재시도는_5_10_20_40_80초_backoff를_적용하고_다음_실패에서_FAILED가_된다`, `동시에_Claim하면_같은_이벤트는_한_Processor만_선점한다`, `stale_PROCESSING은_회수한_뒤_다시_처리한다`를 함께 실행했다.
- `PaymentReservationConfirmationTransactionIntegrationTest`는 핵심 성공 시 Outbox 저장, 핵심 롤백 시 Outbox 미저장, ChatRoom 실패 시 핵심 데이터 유지와 PENDING 재시도를 확인한다.

## 한계

실제 JVM kill/restart 반복·유실률 통계·성능 측정은 수행하지 않았다. 이 Evidence는 대표 결정론 시나리오와 자동 회귀 테스트로 영속 복구 경계를 증명하며, 다중 애플리케이션 인스턴스의 장기 운영 성능이나 운영자 수동 재처리 UI/API는 범위 밖이다.
