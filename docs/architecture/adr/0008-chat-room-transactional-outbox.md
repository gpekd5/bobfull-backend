# ADR 0008: ChatRoom 생성 의도의 Transactional Outbox

- 상태: `Accepted`
- 작성일: `2026-08-08`
- 관련 Issue·PR: #176, #183

## 배경

최초 예약 결제 확정 뒤 ChatRoom은 핵심 결제·예약 트랜잭션 밖에서 생성해야 한다. 기존 `AFTER_COMMIT` 메모리 리스너는 저장 실패가 핵심 데이터를 되돌리지 않게 했지만, 커밋 직후 프로세스가 종료되면 재시작 뒤 실행할 영속 근거가 없었다.

## 결정

CREATE 확정 트랜잭션에서 `OutboxEvent(CHAT_ROOM_CREATION_REQUESTED, PENDING)`를 Payment·Reservation·ReservationParticipant와 함께 저장한다. 커밋 뒤 즉시 signal은 fast path이고, scheduler가 due `PENDING`과 5분 stale `PROCESSING`을 보정한다. Processor는 조건부 `PENDING → PROCESSING` claim만 짧은 트랜잭션에서 수행하고, ChatRoom 생성은 잠금 밖의 별도 트랜잭션에서 `createIfAbsent(reservationId)`로 처리한다.

최초 처리 실패 뒤 5·10·20·40·80초 backoff로 5회 재시도하고, 그 다음(여섯 번째) 실패에서 `FAILED`로 남긴다. `FAILED`는 운영 확인 후 `PENDING`으로 안전하게 재등록할 수 있으나, 이번 범위에서 UI/API는 만들지 않는다.

이메일은 같은 공통 `OutboxEvent`와 claim/retry 정책을 재사용한다. 이메일 주소·본문은 Outbox에 저장하지 않고 처리 시점에 조회하며, `email_outbox_delivery`에 `eventId + recipientMemberId` 고유 수신자 이력만 저장한다. 이미 `SENT`인 수신자는 재시도에서 제외하고, 한 수신자 실패는 다른 수신자의 성공을 되돌리지 않는다.
SMTP Adapter는 수신자별로 한 번만 호출하고 실패를 Processor에 전달한다. 따라서 최초 시도와 최대 5회 backoff 재시도의 책임은 공통 Outbox에만 있으며, 계층별 재시도가 중첩되지 않는다. 이메일 Outbox와 수신자 이력은 핵심 상태 변경과 원자적으로 저장되어야 하므로 enqueue는 활성 트랜잭션을 필수로 요구한다.

`outbox_event`는 `CHAT_ROOM_CREATION_REQUESTED`와 네 종류의 `EMAIL_*` 이벤트가 함께 사용하는 공통 테이블이다. 각 Processor는 due·stale 조회와 조건부 claim 모두에 자신이 담당하는 eventType 집합을 명시해, 다른 Processor의 이벤트를 PROCESSING·COMPLETED·FAILED로 바꾸지 않는다.

scheduler는 정상 사용자가 기다리는 메인 경로가 아니라 즉시 signal과 조회 시 자가복구(`ChatRoomQueryService`)가 모두 실패했을 때의 안전망이다. 다만 backoff 최솟값(5초)이 실제 재시도 간격으로 동작하려면 scheduler 주기도 같은 크기여야 하므로 `outbox.chat-room.fixed-delay` 기본값을 5초로 낮췄다. 단일 인스턴스 기준 초당 조회 1건, 오토스케일링 시 인스턴스 수에 비례해 늘어나는 수준이라 1초까지 낮추지 않고 5초로 정했다. 이 polling 비용을 낮추기 위해 `outbox_event(status, next_attempt_at, outbox_event_id)` 복합 INDEX를 함께 추가했다.

## 선택 이유

DB Outbox는 핵심 데이터와 생성 의도를 원자적으로 보관하면서도 ChatRoom 저장 실패를 핵심 결제 트랜잭션에서 분리한다. at-least-once 전달을 전제로 하며, `chat_room.reservation_id` UNIQUE와 `createIfAbsent`가 중복 부작용을 막는다.

## 대안과 제외

- `AFTER_COMMIT`만 유지: 커밋 뒤 프로세스 종료 유실을 복구할 수 없다.
- 핵심 트랜잭션 안에서 ChatRoom 저장: ChatRoom 저장 실패가 Payment·Reservation을 롤백시킨다.
- Kafka/RabbitMQ·범용 Outbox Framework: 현재 단일 ChatRoom 후속 처리에는 운영·구현 비용이 과도해 별도 요구가 생길 때 검토한다.

## 검증 방법

대표 Before/After 실패 경계와 Outbox 원자 저장·롤백, PENDING 처리, 멱등성, 5회 재시도 후 FAILED, 단일 Claim, stale 회수를 자동 테스트와 `docs/evidence/v3/176-chatroom-outbox/README.md`로 확인한다.
