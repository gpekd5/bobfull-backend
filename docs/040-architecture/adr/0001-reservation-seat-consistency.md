# ADR 0001: 예약 좌석 정합성과 임시 선점 전략

- 상태: `Accepted`
- 작성일: `2026-07-24`
- 관련 Issue: `#18`

## 배경

최초 예약과 추가 참여는 결제 완료 전에도 남은 좌석을 고려해야 하며, 같은 TimeSlot에 대한 동시 요청이 정원을 초과하지 않아야 한다.

## 문제

결제 대기 중인 요청을 좌석 계산에서 제외하면 초과 참여가 가능하고, TimeSlot에 단순 UNIQUE를 두면 취소 이력 보존과 재사용이 어려워진다.

## 고려한 대안

- 별도 `SeatHold` 엔티티로 임시 선점을 관리한다.
- `reservation.time_slot_id`에 단순 UNIQUE를 둔다.
- 만료되지 않은 `PaymentStatus.READY`를 임시 선점으로 사용하고 TimeSlot 기준으로 확인한다.

## 결정

별도 `SeatHold` 엔티티를 만들지 않고 `expiresAt > now`인 `PaymentStatus.READY`로 10분 임시 선점을 표현한다. 결제 성공 전에는 `Reservation` 또는 `ReservationParticipant`를 생성하지 않는다. 만료 시 `Payment.expireIfNeeded(now)`가 `READY && expiresAt <= now`만 `EXPIRED`로 정규화하며, 상태 정규화 전에도 좌석 계산은 즉시 만료 READY를 제외한다.

`availableCapacity`는 테이블 정원에서 PAID 유효 참여 인원과 만료되지 않은 READY 선점 인원을 차감해 계산한다. 동일 TimeSlot의 최초 예약은 TimeSlot 행 비관적 락과 활성 Reservation·유효 CREATE READY 확인으로 직렬화하며, 활성 Reservation 또는 유효한 CREATE READY는 동시에 최대 1건만 허용한다.

만료 스케줄러는 좌석 반환이나 예약 확정을 수행하지 않는다. `READY && expiresAt <= cutoff`을 `expiresAt ASC, paymentId(내부 PK) ASC`로 최대 100건 조회하고, 각 내부 PK를 별도 REQUIRED 트랜잭션의 Payment 행 비관적 락으로 다시 읽어 EXPIRED만 반영한다. 기본 실행 주기는 fixed delay 60초이며 test 환경에서는 비활성화한다.

## 복수 비관적 락의 획득 순서

저장소 전체의 `@Lock(PESSIMISTIC_WRITE)`/`findWithLock*` 호출 지점을 모두 확인한 결과, 한 트랜잭션이 둘 이상의 락을 잡는 흐름은 다음과 같다.

```text
결제 완료 JOIN(PaymentCompletionTransactionService → ReservationConfirmationService.confirm): Payment → Reservation
예약 준비 JOIN(ReservationPreparationService.resolveJoinTarget): Reservation → TimeSlot
예약 준비 CREATE(ReservationPreparationService.resolveCreateTarget): TimeSlot 단독
만료 Processor(PaymentExpirationProcessor): Payment 단독
예약 취소 접수(ReservationCancellationTransactionService.accept, Issue #131/#44): Reservation 단독
```

이 다섯 흐름은 모두 다음 공통 순서의 부분열이며, 어떤 흐름도 이 순서를 역행하지 않는다.

```text
Payment → Reservation → TimeSlot
```

**규칙**: 새로운 흐름이 이 중 두 개 이상의 락을 같은 트랜잭션에서 잡아야 하면, 반드시 위 순서(부분열)로만 획득한다. 역순으로 락을 잡지 않는다(예: TimeSlot을 먼저 잡고 그다음 Reservation을 잡는 흐름을 추가하지 않는다). 이 순서를 벗어나야 하는 요구가 생기면 이 ADR을 먼저 갱신하고, 저장소 전체의 락 경로를 다시 확인한다.

**예약 취소 흐름과 #45 환불 Adapter에 대한 제약(Issue #44 최종 계약)**: 이전 버전에서는 `ReservationCancellationService.cancel`이 Reservation을 잠근 트랜잭션 안에서 곧바로 `ReservationCancellationRefundPort`를 호출했다. 이 방식은 참여자가 여럿인 최초 예약자 취소에서, 참여자 A의 환불이 외부에 성공적으로 접수된 뒤 B의 환불이 실패하면 예약 트랜잭션만 롤백되어 `Refund=COMPLETED / Payment=REFUNDED / Participant=RESERVED` 같은 불일치가 생길 수 있었다(Issue #44).

 이를 해결하기 위해 취소 흐름을 접수·외부 실행·완료 확정 세 단계로 분리했다. `ReservationCancellationTransactionService.accept`가 Reservation을 잠그고 권한·기한·상태를 검증한 뒤 참여자를 `CANCEL_REQUESTED`, Reservation을 `CANCELLING`으로 전이해 커밋하고 락을 반환한다. 환불 outbound port 호출은 이 트랜잭션이 끝난 뒤 `ReservationCancellationService.cancel`(파사드)이 수행하므로, 환불 Adapter(#45)가 Payment를 잠그는 조회를 하더라도 이미 Reservation 락이 반환된 상태라 결제 완료 흐름(Payment → Reservation)과의 락 순서 역전·교착 위험이 없다. 각 참여자의 실제 `CANCELLED` 확정은 환불이 개별적으로 완료될 때마다 결제 도메인의 공통 완료 경로(`RefundCompletionService`)가 자신이 소유한 `ReservationCancellationCompletionPort`를 통해 예약 도메인 전용 완료 서비스 `ReservationCancellationCompletionService`를 호출해 담당하며(V2, #45), 취소 접수 후 완료되지 않고 남은 건은 #141의 정합성 확인 스케줄러가 재확인한다. 취소 시작(`ReservationCancellationService`)과 완료 확정(`ReservationCancellationCompletionService`)의 책임을 분리한 이유는 순수한 정리가 아니라, 두 책임이 한 클래스에 있으면 결제→예약 완료 호출이 예약→결제 환불 호출과 만나 Spring Bean 생성자 의존 그래프가 순환해 애플리케이션 Context가 기동하지 못했기 때문이다.

`ReservationPreparationService.resolveJoinTarget`이 Reservation을 잠금 없는 일반 조회 대신 `findWithLockById`로 트랜잭션의 첫 쿼리이자 잠금 조회로 만든 이유는, MySQL 기본 격리수준(REPEATABLE_READ)에서 트랜잭션의 첫 조회가 이후 모든 일반 SELECT의 스냅샷 시점을 고정하기 때문이다. 잠금 없는 조회를 먼저 실행하면 TimeSlot 락을 기다렸다 획득해도, 그 뒤의 잔여 좌석 계산(`AvailableCapacityCalculator`)은 락 획득 이전 스냅샷을 그대로 사용해 상대방이 방금 커밋한 결과를 보지 못한다(Issue #36에서 실제 MySQL로 재현·확인).

**처리량 저하와 데드락 가능성**: 이 순서 규칙을 지키는 한, 서로 다른 흐름끼리 순환 대기(circular wait)가 생기지 않아 데드락 위험은 없다. 다만 각 락은 해당 트랜잭션이 끝날 때까지 다른 트랜잭션의 같은 행 접근을 막으므로, 동시 요청이 몰리는 인기 회차·인기 예약에서는 락 대기로 처리량이 떨어질 수 있다(기존 단점 항목과 동일). 검증 방법은 `ReservationPreparationConcurrencyIntegrationTest`(`BOBFULL_MYSQL_CONCURRENCY_TEST=true`)로, 위 순서를 지키는 두 흐름을 실제 MySQL에서 동시 실행해 데드락 없이 하나만 성공하고 나머지는 정상적으로 대기 후 실패하는지 확인한다. 이 테스트는 `ddl-auto=create-drop`으로 대상 DB를 매번 지우고 새로 만들므로, `BOBFULL_TEST_MYSQL_URL`은 반드시 로컬 개발 DB와 분리된 별도 스키마(예: `bobfull_concurrency_test`)를 가리켜야 한다.

## 선택 이유

현재 결제 상태와 예약 흐름을 이용해 임시 선점을 표현하면서, 취소 이력을 보존하는 TimeSlot 재사용 요구와 동시 최초 예약 방지 요구를 함께 만족한다.

## 장점

- 임시 선점 상태를 현재 Payment 모델과 함께 관리한다.
- 결제 성공 전 예약·참여 데이터 생성을 피한다.
- 활성 예약과 유효 CREATE READY의 중복 생성을 방지한다.

## 단점과 위험

- READY Payment의 만료·정리·추적 책임이 중요하다. 외부 PAID가 내부 EXPIRED 뒤에 도착하면 자동 보상 없이 운영 확인이 필요하다.
- TimeSlot 비관적 락은 처리량 증가 시 병목이 될 수 있다.
- 다중 인스턴스 환경에서 별도 선점 저장소가 필요해질 수 있다.

## 검증 방법

동일 TimeSlot의 동시 최초 예약 요청에서 활성 Reservation 또는 유효 CREATE READY의 성공 건수가 최대 1건인지 검증한다. 좌석 계산과 상태의 상세 계약은 [project-context.md](../../010-product/project-context.md), [erd.md](../../030-data/erd.md), [API 명세](../../020-api/bobfull-api-spec-complete.md)를 따른다.

## 재검토 조건

- READY Payment만으로 선점 만료·정리·추적이 어려워질 때
- TimeSlot 비관적 락 병목이 확인될 때
- 다중 인스턴스에서 별도 선점 저장소가 필요할 때
