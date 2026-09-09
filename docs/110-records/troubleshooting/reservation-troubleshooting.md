# 예약 트러블슈팅

예약 도메인에서 확인한 문제, 원인, 해결 과정과 검증 상태를 동일한 양식으로 기록한다.

---

# 1. 예약 취소·환불의 트랜잭션 경계와 부분 성공 정합성

> 큰 예약 취소 트랜잭션 안에서 참여자별 환불을 REQUIRES_NEW로 처리하면, 외부 환불이 먼저 성공한 참여자와 예약 상태가 어긋날 수 있는 문제를 접수·외부 실행·완료 확정 3단계 분리로 해결했다.

---

## 기본 정보

- 날짜: 2026-08-04
- 담당자: 김현승
- 도메인: 예약·결제
- 관련 Issue: #44, #45, #131, #141
- 관련 PR: #135(병합됨), #144
- 관련 브랜치: `feature/45-payment-refund-execution`
- 상태: `검토 중`

---

## 문제 상황

최초 예약자가 취소하면 예약 전체가 취소되며 유효 참여자 전원이 환불 대상이 된다. 초기 설계는 `ReservationCancellationService.cancel()`의 하나의 `@Transactional` 안에서 Reservation을 잠그고, 그 안에서 참여자별 환불 outbound port를 순회 호출한 뒤 성공하면 곧바로 Participant·Reservation을 `CANCELLED`로 확정했다.

참여자가 여럿인 경우 환불 처리 도중 일부만 성공하고 일부는 실패할 수 있는데, 이때 이미 외부(PortOne)에 성공적으로 접수된 환불은 되돌릴 수 없다. 그런데 예약 트랜잭션은 예외 발생 시 통째로 롤백되므로, 외부에서는 이미 환불이 완료된 참여자의 `Participant` 상태가 DB에서는 `RESERVED`로 남는 불일치가 생길 수 있었다.

## 원인 분석

### 원인

- Payment/Refund에 대한 외부 PortOne 호출과 예약 도메인의 로컬 DB 트랜잭션을 하나의 원자적 단위로 묶을 수 없다(Dual-write 문제).
- 참여자별 환불을 `REQUIRES_NEW`로 즉시 커밋하면 외부 성공 결과 자체는 보존되지만, 그 성공을 예약·참여 상태에 반영하는 것은 여전히 바깥의 큰 트랜잭션에 묶여 있어 그 트랜잭션이 롤백되면 함께 사라진다.
- Reservation 락을 잡은 채로 PortOne 네트워크 호출을 기다리면, 결제 완료 흐름(Payment → Reservation 순서로 락 획득)과 정반대 순서가 되어 락 순서 역전·교착 위험도 함께 있었다(ADR 0001).

### 원인을 어떻게 확인했는가?

- 코드 확인: `ReservationCancellationService.cancel()`의 원래 구조와 `RefundTransactionService`의 `REQUIRES_NEW` 경계를 확인했다.
- AI 분석: Dual-write 문제, REQUIRES_NEW의 보존 범위, 조건부 UPDATE 기반 멱등성 등 분산 시스템 패턴과 대조해 원인과 해법 후보를 정리했다.
- 외부 참고: 대규모 예매 시스템 사례(대기열에 Kafka 대신 Redis Sorted Set을 쓰는 이유)를 참고해 "기술 먼저 결정"이 아니라 "문제를 먼저 정확히 분리"하는 접근으로 조정했다.
- 팀 리뷰: 담당 튜터가 스케줄러·웹훅 동시 처리 경쟁, PG 조회 실패 정책, 부분 실패 알림 기준 등 추가 리스크를 지적했다.

## 해결 과정

### 시도 1

**내용**

참여자별 환불 처리(`RefundTransactionService.createRequested`/`reflectExternalResult`/`markFailed`)를 `REQUIRES_NEW`로 분리해, 예약 트랜잭션이 롤백돼도 이미 성공한 외부 환불 기록(Refund·Payment)만은 보존되도록 했다.

**결과**

외부 환불 성공 "기록"은 보존되지만, 그 성공을 예약·참여자 상태에 반영하는 일 자체는 여전히 바깥 예약 트랜잭션 안에 있어서, 트랜잭션이 롤백되면 `Participant`가 `RESERVED`로 남는 문제는 그대로였다. REQUIRES_NEW는 "외부에 이미 벌어진 일을 지우지 않는" 방어일 뿐, 예약·참여 상태까지 정합하게 맞춰주는 해결책은 아니었다.

### 시도 2

**내용**

큰 트랜잭션을 유지한 채 예외 발생 시 전체를 실패 응답으로 처리하는 방식을 검토했다.

**결과**

이미 성공한 외부 환불은 예외 처리로도 되돌릴 수 없어, "일부는 성공했는데 전체는 실패로 응답"하는 것 자체가 실제 상태와 응답이 어긋나는 근본 해결이 아니었다.

### 최종 해결 또는 현재 판단

**적용한 해결 방법 또는 후보안**

- 취소 흐름을 접수(짧은 트랜잭션) · 외부 환불 실행(트랜잭션 밖) · 완료 확정(짧은 트랜잭션) 3단계로 분리했다.
- 중간 상태 `ReservationStatus.CANCELLING`, `ParticipationStatus.CANCEL_REQUESTED`를 추가해, 접수 시점에는 "취소 처리 중"임을 먼저 커밋하고 Reservation 락을 즉시 반환한다.
- 환불 실행은 Reservation 락과 무관하게 참여자별로 수행하고, 완료 시 `RefundCompletionService`가 즉시 응답·PortOne 웹훅 양쪽에서 공통으로 호출하는 완료 경로를 통해 자신이 소유한 `ReservationCancellationCompletionPort`(구현체 `ReservationCancellationCompletionAdapter`)로 예약 도메인 전용 완료 서비스 `ReservationCancellationCompletionService.complete()`를 부른다(V2, #45).
- 최초 구현에서는 취소 시작을 담당하는 `ReservationCancellationService`가 이 완료 호출까지 함께 담당했다. 그 결과 `ReservationCancellationService → ReservationCancellationRefundPort → (결제) → RefundCompletionService → ReservationCancellationService`로 이어지는 Spring Bean 순환이 생겨 애플리케이션 Context가 기동하지 못했고, `ObjectProvider<ReservationCancellationService>`로 Bean 조회를 늦추는 임시 우회를 거쳤다. 최종적으로는 완료 책임을 `ReservationCancellationCompletionService`로 분리해, 결제→예약 호출이 Repository에서 끝나고 취소 시작 서비스로 되돌아가지 않도록 해 순환 자체를 제거했다.
- 완료 경로는 `completeCancelIfRequested()` 조건부 UPDATE(`WHERE participation_status = 'CANCEL_REQUESTED'`)로 동시 완료 요청 중 한쪽만 처리권을 갖도록 해, 즉시 응답과 웹훅이 동시에 도착해도 중복 반영되지 않는다.
- 마지막 `CANCEL_REQUESTED` 대상이면 Reservation을 `CANCELLED`로, 남은 대상이 있으면 `CANCELLING`으로 유지한다.

**선택 이유 또는 남은 검증**

- 외부 PortOne 결제와 내부 DB를 하나의 원자적 트랜잭션으로 만들 수 없다는 전제를 받아들이고, 대신 중간 상태를 명시적으로 남겨 불일치가 생겨도 "정직하게 드러나는" 상태로 만들었다.
- 참여자별 성공 결과를 독립적으로 보존하는 대신, 일부 실패·결과 불명확 시 Reservation이 `CANCELLING`에 머무를 수 있다 — 이 잔여 건은 Issue #141의 정합성 확인 스케줄러가 재조회해 완료 경로를 재호출하도록 별도 분리했다.
- 실제 PortOne 환경 검증과 MySQL 전용 동시성 테스트는 아직 `NOT_RUN`이다.

## 검증

- 재현 테스트: `RefundTransactionIntegrationTest`에서 "앞선 참여자 환불 성공 → 다음 참여자 환불 실패 → 바깥 트랜잭션 예외" 시나리오로 재현·확인.
- 자동 테스트: 부분 성공 보존, 동일 Payment 동시 환불 1건 수렴, timeout/connection reset 결과 불명확 처리, Cancelled 웹훅과 즉시 응답 동시 완료의 멱등성, 마지막 대상 여부에 따른 Reservation 상태 재계산을 `RefundTransactionIntegrationTest`·`ReservationCancellationServiceTest`·`RefundWebhookServiceTest`로 검증(H2 기준 `PASS`).
- 직접 검증: 실제 PortOne 테스트 환경 환불·웹훅 검증은 `NOT_RUN`. MySQL 전용 동시성 테스트도 환경상 `NOT_RUN`(현재 동시성 증거는 H2 통합 테스트).
- 재발 방지: 오래된 `REQUESTED/PROCESSING` 재조회, 명확한 `FAILED`의 관리자 알림, 부분 실패 긴급 기준·CS 대응 절차는 Issue #141에서 별도 구현 예정.

## 배운 점

- 외부에서 실제로 벌어진 부수효과(결제 취소)는 로컬 트랜잭션 롤백으로 지울 수 없다. REQUIRES_NEW는 그 사실을 "보존"하는 방어이지, 그것만으로 상태 정합성이 자동으로 맞춰지지는 않는다.
- 큰 트랜잭션 하나로 묶어 "다 되거나 다 안 되거나"를 노리기보다, 접수·외부 실행·완료 확정을 분리하고 중간 상태를 명시적으로 두는 편이 부분 실패를 안전하게 드러낸다.
- 두 도메인이 서로 다른 지점에서 서로를 호출하는 것 자체는 문제가 아니다. 문제는 한 클래스가 어떤 방향 호출의 시작점이면서 동시에 반대 방향 호출의 도착점이 되는 경우다. 취소 시작과 환불 완료 확정이 같은 서비스에 있었기 때문에 Bean 순환이 생겼고, 그 서비스를 시작 전용·완료 전용으로 나눠 완료 쪽 호출이 Repository에서 끝나게 만들자 순환이 사라졌다.
- 즉시 응답·웹훅·스케줄러처럼 같은 결과에 도달하는 여러 경로가 있다면, 반드시 하나의 공통 완료 로직을 재사용해야 경로별로 다른 결과가 나오는 걸 막을 수 있다.
- 기술 도입(카프카 등)은 실제 병목을 측정·진단한 뒤에 판단해야 하며, 이번 문제는 상태 모델과 트랜잭션 경계 재설계만으로 해결 가능했다.
