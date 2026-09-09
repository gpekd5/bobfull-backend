# ADR 0002: 결제 완료 API와 PortOne 웹훅의 멱등성 경계

- 상태: `Accepted`
- 작성일: `2026-07-24`
- 관련 Issue: `#18`, `#93`

## 배경

사용자의 결제 완료 검증 API와 PortOne 웹훅은 같은 Payment의 결과를 반영할 수 있으며, 동시에 또는 반복해서 도착할 수 있다.

## 문제

두 경로가 각각 결과를 반영하면 예약·참여·결제 상태가 중복 반영될 수 있다.

## 고려한 대안

- 완료 검증 API와 웹훅이 각각 독립적으로 결과를 반영한다.
- 두 경로를 동일 Payment 결과 반영 경계로 수렴시킨다.
- 별도 이벤트 저장소를 먼저 도입해 중복 요청을 추적한다.

### 이미 PAID인 완료 재요청의 HTTP 응답

#### 대안 A: `409 Conflict`

- 중복 요청임을 명시적으로 알릴 수 있다.
- 응답 유실 뒤 정상적으로 재시도한 요청도 오류처럼 처리될 수 있다.
- 클라이언트가 최초 요청의 성공 여부를 별도로 판단해야 한다.

#### 대안 B: `200 OK` 멱등 성공

- 같은 요청을 반복해도 최종 결과가 같다.
- 최초 응답 유실 뒤 재시도해도 기존 완료 결과를 받을 수 있다.
- 실제 결제 완료와 예약 확정은 한 번만 수행된다.

## 결정

완료 검증 API와 PortOne 웹훅은 동일 Payment의 결제 결과 반영 경계로 수렴한다. 두 요청이 동시에 또는 반복해서 도착해도 예약·참여·결제 상태는 한 번만 반영한다.

사용자 완료 검증에서는 인증 사용자와 `Payment.memberId` 소유권을 확인하고, 웹훅에서는 `permitAll`·JWT 필터 우회 뒤 사용자 인증 대신 원본 Body와 `webhook-id`·`webhook-signature`·`webhook-timestamp`의 PortOne 공식 SDK 서명을 검증한다. 검증 성공 뒤에만 이벤트를 해석한다. 두 입구는 외부 결제 상태·금액·통화 재조회 뒤 동일 내부 Payment PK의 비관적 락과 상태·`expiresAt` 재검증으로 수렴한다.

동일 `paymentId`가 이미 `PAID`인 완료 요청은 `200 OK` 멱등 성공으로 처리하고 기존 완료 결과를 반환한다. 중복 요청은 새로운 상태 전이를 만들지 않으며, PortOne 재조회와 `ReservationConfirmationPort` 호출을 반복하지 않는다. 이 응답은 새로운 완료 처리가 아니라 이미 달성된 목표 상태의 기존 결과 반환이다.

PAID 전환, `Reservation`·`ReservationParticipant` 생성, Payment 결과 ID 연결은 하나의 트랜잭션이다. `ReservationConfirmationService`는 `MANDATORY`로 이 트랜잭션에 참여하며 `REQUIRES_NEW`는 사용하지 않는다. 내부 Payment가 `EXPIRED` 또는 락 획득 뒤 만료된 READY이면 완료 API는 `409 PAYMENT_EXPIRED`로 거절하고, 웹훅은 PortOne 재조회 후 상태 전이·예약 확정을 하지 않은 채 영구 업무 실패로 200을 반환한다.

외부 PortOne 상태가 PAID인데 내부 Payment가 EXPIRED 또는 만료 READY면 `event=PAYMENT_COMPENSATION_REQUIRED` 구조화 로그에 `paymentId`, `externalStatus`, `internalStatus`, `expiresAt`, `reason`을 남긴다. 이 Issue는 자동 취소·환불·보상 트랜잭션 및 `WebhookEvent` 저장을 도입하지 않는다.

## 선택 이유

외부 결제 결과가 들어오는 두 경로의 책임을 같은 결과 반영 기준으로 맞춰 중복 처리 위험을 줄인다.

## 장점

- 완료 검증과 웹훅의 결과가 같은 Payment 상태로 수렴한다.
- 사용자 경로의 소유권 검증과 웹훅 경로의 서명 검증을 분리한다.
- 결제 상태·금액·통화 검증 후 내부 상태를 반영한다.
- Payment 행 락과 단일 트랜잭션으로 예약 확정의 부분 성공을 막는다.

## 단점과 위험

- 실제 구현에서 멱등성 경계가 충분하지 않으면 중복 반영 위험이 남는다.
- 중복 웹훅 추적이나 재처리 요구가 생기면 별도 저장이 필요할 수 있다.

## 검증 방법

완료 검증 API와 웹훅이 동시에 도착하거나 반복될 때 결과가 한 번만 반영되는지 검증한다. 상세 API·상태 계약은 [project-context.md](../../10-product/project-context.md), [API 명세](../../20-api/bobfull-api-spec-complete.md), [erd.md](../../30-data/erd.md)를 따른다.

## 재검토 조건

- 중복 웹훅 추적이나 재처리를 위한 별도 이벤트 저장이 필요할 때
- 결제 공급자 추가로 공통 결제 이벤트 계층이 필요할 때
- 현재 트랜잭션 경계에서 중복 반영 위험이 제거되지 않을 때

`WebhookEvent` 저장, 자동 취소·환불·보상은 후속 요구가 생길 때 별도 계약으로 검토한다.
