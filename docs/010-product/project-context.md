# 밥풀(BobFull) 프로젝트 컨텍스트

> 기준일: 2026-08-18 Final QA 기준
> API 계약의 최우선 기준은 [`bobfull-api-spec-complete.md`](../020-api/bobfull-api-spec-complete.md)다. 이 문서가 API 명세와 충돌하면 API 명세를 따른다.

## 1. 프로젝트 개요

**밥풀**은 제주 평일 저녁의 혼자 여행하는 사용자를 핵심 타깃으로, 사용자가 식당의 합석 회차를 선택해 예약하거나 기존 합석 예약에 참여하는 서비스다. OWNER는 식당·합석 테이블·합석 회차를 관리하고, MEMBER는 `partySize`를 포함해 예약 결제를 준비한다. PortOne 결제 검증 또는 웹훅 검증이 성공하면 예약·참여·결제 결과를 반영한다. 제주는 초기 운영 타깃이며 시스템적으로 제주 외 지역을 차단하지 않는다. V1에는 지역 검색·필터 기능을 제공하지 않는다.

## 2. 역할과 권한

역할은 `MEMBER`, `OWNER`, `ADMIN`이다.

### MEMBER

- 인증 없이 사용자용 식당·예약 가능 회차·참여 가능한 예약을 조회한다.
- 인증 후 본인 정보·예약·참여·결제·환불을 조회하고 예약 결제를 준비한다.
- 최초 예약자는 본인 예약의 모집 상태를 `CLOSED`로 변경한다.
- V2에서 서버 시간 기준 식사 시작 2시간 전까지 본인 참여 전체를 취소한다. 부분 `partySize` 취소와 2시간 이내 MEMBER 취소는 지원하지 않는다.
- 결제 완료 검증은 해당 Payment를 생성한 당사자만 호출한다. `Payment.memberId`와 인증 사용자 ID가 일치해야 한다.
- V1은 참여자 상세 목록 대신 예약 집계 정보만 조회한다. V2에서는 해당 예약의 유효 참여자만 `name`, `partySize` 목록을 조회한다.

### OWNER

- 본인 식당의 식당·합석 테이블·합석 회차를 등록·조회·수정·삭제한다.
- 본인 식당 이미지를 S3 Presigned URL로 업로드하고 식당 등록·수정에 연결한다.
- 식당·테이블 생성 시 서버가 `ACTIVE`를 기본 적용한다. 등록·수정 Request에 `status`는 없다.
- 본인 식당의 예약·참여자 정보를 조회한다.
- V1에서 지급 예정 금액과 예약별 지급 예정 내역·상세를 조회한다.
- V2에서 식당 귀책 예약 취소와 식사 종료 후 노쇼 처리·해제·이력 조회를 수행한다.

#### 식당 이미지

- V1 식당 이미지는 S3 Presigned PUT URL로 클라이언트가 직접 업로드한다.
- 업로드 허용 형식은 `jpg`, `jpeg`, `png`이며 `webp`는 허용하지 않는다. 파일 크기는 최대 5MB다.
- 임시 업로드 경로는 `temp/restaurants/{ownerId}/{uuid}.{extension}`, 최종 경로는 `restaurants/{ownerId}/{uuid}.{extension}`다.
- Java Lambda가 S3 임시 객체의 확장자, Content-Type, 크기, 파일 시그니처를 검증하고 성공 시 최종 경로로 이동한다.
- 식당 등록·수정 API는 최종 경로 객체가 존재하는지 확인한 뒤 `restaurant.image_key`만 저장한다. 조회 응답은 DB 저장값이 아니라 만료 시간이 있는 Presigned GET URL을 반환한다.

### ADMIN

- V2에서 회원·식당·예약·결제·환불·노쇼 현황 및 운영 지표를 조회한다.
- V3에서 실패 결제·환불과 정산 데이터를 재처리하거나 재집계한다.
- 별도 ADMIN 회원가입 API는 두지 않는다. 초기 ADMIN 계정은 일반 회원가입 후 운영자가 DB에서 `role=ADMIN`으로 수동 변경한다.

## 3. 테이블·인원 정책

- `capacity`는 `2`, `4`, `6`, `8`만 허용한다. 단건 테이블 등록·수정과 테이블·회차 일괄 등록에 동일하게 적용한다.
- 합석 테이블의 `displayNumber`는 식당별 1부터 서버가 자동 발급하며, 삭제된 번호도 재사용하지 않는다.
- 테이블 일괄 등록은 동일 `capacity`로 1회 최대 10개까지 허용한다.
- 허용 범위 밖 `capacity`의 ErrorCode는 `INVALID_TABLE_CAPACITY`다.
- 예약 생성(`CREATE`)은 `1 <= partySize <= table.capacity`여야 한다.
- 추가 참여(`JOIN`)은 `1 <= partySize <= availableCapacity`여야 한다.
- 잘못된 `partySize`는 `INVALID_PARTY_SIZE`, 추가 참여 가능 인원 초과는 `INSUFFICIENT_REMAINING_CAPACITY`다.

```text
currentParticipantCount
= PAID 상태 유효 참여자의 partySize 합계

임시 선점 인원
= 만료되지 않은 READY 결제의 partySize 합계

availableCapacity
= capacity - currentParticipantCount - 임시 선점 인원
```

- V1 예약 상세는 `currentParticipantCount`, `availableCapacity`, `confirmationThreshold`, `reservationStatus`, `recruitmentStatus`의 집계 정보를 제공한다.

## 4. 예약·모집 상태

### 상태 enum

```text
ReservationStatus: RECRUITING, CONFIRMED, CANCELLING, CANCELLED, CLOSED
RecruitmentStatus: OPEN, CLOSED
ParticipationStatus: RESERVED, NO_SHOW, CANCEL_REQUESTED, CANCELLED
PaymentStatus: READY, PAID, EXPIRED, FAILED, REFUNDED
RefundStatus: REQUESTED, PROCESSING, COMPLETED, FAILED
```

`CANCELLING`(Reservation)·`CANCEL_REQUESTED`(Participant)는 취소가 접수됐지만 참여자별 외부 환불이 아직 완료되지 않은 중간 상태다(#44). `isActive()`는 `RECRUITING`·`CONFIRMED`만 참으로 취급해 `CANCELLING`도 신규 JOIN·결제 확정 대상에서 제외한다.

**2026-08-05 Human 확정**: Issue #44 완료 조건은 원래 `RefundStatus.UNKNOWN` 추가를 요구했으나, `UNKNOWN`처럼 애매한 상태를 늘리지 않기로 확정했다. timeout·connection reset 등 결과 불명확은 `REQUESTED` 유지로 표현하는 것이 최종 정책이다.

### 확정 기준과 흐름

| 테이블 정원 | confirmationThreshold |
|---:|---:|
| 2 | 2 |
| 4 | 3 |
| 6 | 5 |
| 8 | 7 |

1. 최초 결제 완료 시 예약은 `RECRUITING + OPEN`으로 시작한다.
2. 확정 기준 도달 시 `CONFIRMED + OPEN`이 된다.
3. 정원 도달 시 `CONFIRMED + CLOSED`가 된다.
4. 식사 시작 2시간 전에 모집은 `CLOSED`가 된다.
5. 모집 마감 시 확정 기준 미달이면 예약은 `CANCELLED`가 되고 참여자 전액을 환불한다.

`CONFIRMED`는 모집 종료를 의미하지 않는다.

### 최초 예약자 수동 모집 마감

- 최초 예약자만 `reservationStatus=CONFIRMED`와 `recruitmentStatus=OPEN`을 모두 만족할 때 모집을 `CLOSED`로 변경할 수 있다.
- `RECRUITING` 예약은 수동 마감할 수 없고, 이미 `CLOSED`인 모집을 중복 마감하거나 다시 `OPEN`으로 변경할 수 없다.
- 수동 마감 자체는 TimeSlot을 복구하지 않는다. TimeSlot 재사용은 Reservation 전체가 `CANCELLED`된 뒤에만 판단한다.

## 5. 예약·결제·환불

### 예약 결제 준비

1. 클라이언트는 `/api/reservations/prepare`에 `type`, `targetId`, `partySize`를 전송한다.
2. `CREATE`의 `targetId`는 `sessionId`, `JOIN`의 `targetId`는 `reservationId`다.
3. 서버는 좌석을 10분간 임시 선점하고 `PaymentStatus.READY`와 PortOne `paymentId`를 생성한다.
4. 결제 성공 전에는 예약 또는 참여자를 생성하지 않는다. 검증 실패는 `FAILED`, 시간 만료는 `EXPIRED`로 구분한다. 좌석은 만료된 `READY`를 `expiresAt` 계산에서 제외해 즉시 반환한다.

### 결제 완료와 웹훅

- 결제 당사자는 `/api/payments/{paymentId}/complete`로 결제 완료를 검증한다.
- 당사자가 아니면 `PAYMENT_ACCESS_DENIED`, 대상 결제가 없으면 `PAYMENT_NOT_FOUND`, 내부 `EXPIRED` 또는 만료된 `READY`면 `409 PAYMENT_EXPIRED`를 반환한다. 이미 완료 처리된 Payment는 기존 완료 결과를 담아 멱등 응답한다.
- PortOne 웹훅은 `/api/webhooks/portone`의 `permitAll`·JWT 필터 우회와 별개로, JSON 해석 전 원본 Body 및 `webhook-id`·`webhook-signature`·`webhook-timestamp`를 공식 SDK로 검증한다.
- 완료 API와 웹훅은 입구 인증·검증만 분리하고 PortOne 재조회, 상태·금액·통화 검증, 동일 Payment 행 비관적 락, 결과 반영을 공통 처리로 수렴한다. PAID 전환과 Reservation·ReservationParticipant 생성은 하나의 트랜잭션이다.
- 웹훅의 서명 실패는 `400`, 알려진 영구 업무 실패는 오류 로그 후 `200`, PortOne 네트워크·DB·예상하지 못한 오류는 `5xx`다.
- 외부 PAID인데 내부가 `EXPIRED` 또는 만료 `READY`면 `event=PAYMENT_COMPENSATION_REQUIRED`와 `paymentId`, `externalStatus`, `internalStatus`, `expiresAt`, `reason`을 기록하고 예약 확정은 하지 않는다. 자동 취소·환불·보상 트랜잭션은 이번 범위에서 제외한다.

### READY 만료 정규화

- `Payment.expireIfNeeded(now)`는 `READY && expiresAt <= now`일 때만 멱등적으로 `EXPIRED`로 전이한다. `PAID`, `FAILED`, `REFUNDED`, `EXPIRED` 또는 만료 전 `READY`는 변경하지 않는다.
- 스케줄러는 `application-local.yml` 또는 배포 환경변수의 `fixedDelay=60s`, `batchSize=100`으로 local·운영에서 활성화하고 test에서는 비활성화한다. 후보는 `READY && expiresAt <= cutoff`을 `expiresAt ASC, paymentId(내부 PK) ASC`로 최대 100건 조회한다.
- Processor는 각 내부 PK를 같은 Payment 행 비관적 락으로 다시 읽어 별도 REQUIRED 트랜잭션에서 정규화한다. 스케줄러는 예약 확정·외부 취소·환불을 호출하지 않는다.

### 취소·환불

- V2에서 MEMBER는 서버 시간 기준 식사 시작 2시간 전까지만 본인 `ReservationParticipant`의 신청 인원 전체를 취소한다. 부분 취소·부분 환불·마감 이후 무환불 취소는 이번 범위에 없다.
- 취소는 접수·외부 환불 실행·완료 확정 세 단계로 나뉜다(#44, #45). **접수(짧은 트랜잭션)**: 최초 예약자 취소는 예약을 `CANCELLING`으로, 유효 참여자를 모두 `CANCEL_REQUESTED`로 전환해 커밋한다. 추가 참여자 취소는 해당 `ReservationParticipant`만 `CANCEL_REQUESTED`로 전환한다. 이 시점의 좌석은 계속 점유한 것으로 계산한다. **외부 실행(트랜잭션 밖)**: 접수 커밋 뒤 참여자별로 Payment 전체 금액의 Refund를 생성해 PortOne 환불을 요청한다. **완료 확정(짧은 트랜잭션)**: 환불이 완료되면 해당 참여자만 `CANCEL_REQUESTED → CANCELLED`로 확정한다.
- 최초 예약자 취소는 모든 유효 참여자의 환불이 각각 완료될 때마다 그 참여자만 `CANCELLED`로 확정되고, 남은 `CANCEL_REQUESTED`가 없어야 예약 전체가 `CANCELLED`로 확정된다. 예약이 `CANCELLED`로 확정되고 현재 시간이 식사 시작 2시간 전보다 이전이며 다른 활성 예약 또는 OWNER·시스템 사용 제한이 없을 때만 TimeSlot을 새 예약 가능 상태로 복구한다.
- 추가 참여자 취소는 본인 환불이 완료되면 해당 `ReservationParticipant`만 `CANCELLED`로 확정한다. `currentParticipantCount`와 `availableCapacity`를 재계산하며, 모집이 `OPEN`일 때 확정 기준 미달이면 `RECRUITING`, 기준 이상이면 `CONFIRMED`를 유지한다.
- 수동 마감된 `CONFIRMED + CLOSED` 예약에서 추가 참여자 취소 확정 후 기준 이상이면 `CONFIRMED + CLOSED`를 유지한다. 기준 미달이면 남은 유효 참여자도 같은 접수·실행·확정 절차로 전체 취소한다. 모집은 다시 `OPEN`으로 변경하지 않으며, `CANCELLING` 전환 시점부터 ChatRoom 신규 메시지 전송을 종료한다.
- 모집 마감은 정원 도달 또는 식사 시작 2시간 전에 `CLOSED`가 된다. 마감 시 확정 기준 미달이면 예약 전체를 같은 절차로 취소하며, 취소 좌석을 재모집하지 않는다.
- V2에서 OWNER 또는 시스템 귀책으로 예약을 진행할 수 없으면 예약 전체를 같은 접수·실행·확정 절차로 취소한다. 참여자를 `NO_SHOW`로 처리하지 않는다. TimeSlot 재사용은 예약이 `CANCELLED`로 확정되고 현재 시간이 식사 시작 2시간 전보다 이전이며 다른 활성 예약 또는 OWNER·시스템 사용 제한이 없는 경우만 가능하다.
- MEMBER 취소는 `NO_SHOW`, `CANCEL_REQUESTED` 또는 이미 `CANCELLED`인 참여자에게 허용되지 않는다. 현재 ParticipationStatus에는 `VISITED` 상태가 없다.
- Refund는 Payment 전체 금액을 대상으로 하며 Payment당 하나만 생성한다. 환불 완료 시 `RefundStatus.COMPLETED`와 `PaymentStatus.REFUNDED`를 반영한다. 결과가 불명확하면(timeout·connection reset 등) `RefundStatus.REQUESTED`를 유지하고 자동으로 재환불하지 않는다(#45). READY 결제의 명시적 사용자 취소는 현재 범위에 없다.
- 사용자는 환불 처리 API를 직접 호출하지 않으며, V1에서 본인 환불 목록·상세를 조회한다.

### TimeSlot 활성 예약 정합성

- TimeSlot은 취소 이력을 포함해 여러 Reservation을 연결할 수 있지만, `RECRUITING` 또는 `CONFIRMED` 활성 Reservation은 동시에 최대 1건이다.
- `reservation.time_slot_id` 단순 UNIQUE는 사용하지 않는다. 새 최초 예약 또는 READY Payment 생성은 대상 TimeSlot 행을 비관적 락으로 조회하고, 활성 Reservation 존재 여부를 확인한 뒤 트랜잭션 종료까지 잠금을 유지한다.
- `CREATE` 결제 준비는 활성 Reservation뿐 아니라 동일 TimeSlot의 만료되지 않은 `paymentPurpose=CREATE`, `paymentStatus=READY` Payment도 확인한다. 둘 다 없을 때만 새 CREATE READY를 생성하며, 유효한 CREATE READY는 TimeSlot당 최대 1건이다.
- 유효한 CREATE READY가 있으면 `409 ACTIVE_RESERVATION_ALREADY_EXISTS`로 거절한다. 해당 Payment가 만료되거나 `FAILED`가 된 뒤에는 새 CREATE 요청을 허용한다. 기존 Reservation에 대한 `JOIN READY`는 `availableCapacity`를 기준으로 별도 처리한다.
- 실제 구현 Issue에서는 동일 TimeSlot에 대한 동시 최초 예약 생성 시 활성 Reservation 또는 유효한 CREATE READY의 성공이 최대 1건인지 검증한다.

## 6. 노쇼·지급 예정 금액

- V2에서 OWNER는 식사 종료 후 노쇼 처리 대상 참여자를 조회하고, 참여자를 노쇼 처리·해제하며 이력을 조회한다. 식사 종료 경계는 `now >= TimeSlot.endAt`이며, 채팅 신규 메시지 전송 차단과 동일한 경계를 사용한다(Issue #175).
- V1 지급 예정 금액은 `paidAt`이 존재하는 결제 완료 이력의 금액 합계에서 `COMPLETED` 환불 금액 합계를 뺀 조회 계산값이다. 완료 환불로 Payment의 현재 상태가 `REFUNDED`여도 원결제 금액은 결제 완료액에 포함한다.

## 7. 채팅

- V2에서 최초 예약 결제 완료 시 예약당 채팅방 1개를 생성한다. 별도 생성 API는 없다.
- 유효 참여자는 결제 완료 참여자 중 `CANCELLED`가 아닌 참여자다. 유효 참여자만 접근하며, `CANCELLED` 참여자는 즉시 접근이 종료되고 OWNER와 ADMIN은 참여하지 않는다.
- 예약이 `CANCELLED` 또는 `CLOSED`면 새 메시지 전송을 종료하고, 기존 메시지는 조회할 수 있다. `CONFIRMED → CLOSED` 전이는 스케줄러가 처리하지만, 스케줄러 지연과 무관하게 `now >= TimeSlot.endAt`부터는 새 메시지 전송을 즉시 차단한다(Issue #175).
- 메시지는 DB에 저장하며 과거 메시지는 cursor 기반으로 조회한다. V3 #170은 DB 커밋 후 Redis Pub/Sub(`bobfull:chat:messages`)으로 각 애플리케이션 인스턴스에 실시간 전파한다. Pub/Sub은 메시지를 저장하거나 다시 보내주지 않으므로, 단절 중 놓친 메시지는 DB cursor 조회가 공식 복구 경로다. Redis 발행 실패는 저장된 메시지를 롤백하지 않는다.
- WebSocket 연결 Endpoint는 `/ws`다.
- STOMP 전송 경로는 `/pub/chat/rooms/{chatRoomId}/messages`, 구독 경로는 `/sub/chat/rooms/{chatRoomId}`다.
- HTTP 조회 경로는 `GET /api/chat/rooms/{chatRoomId}/messages`다.
- 읽음 처리, 이미지·파일, 메시지 수정·삭제, 차단, Redis Streams·Redis Pub/Sub 재전송 Outbox는 범위에서 제외한다. AI Moderation의 Kafka는 채팅 실시간 전파와 분리된 비동기 분석·재처리 경로다. V3 #218 사용자 신고는 채팅방의 상대 회원을 대상으로 하며, AI Moderation과 신고 누적은 관리자 Human Review 참고 신호일 뿐 자동 제재 점수·자동 BAN/정지/퇴장에 사용하지 않는다.

## 8. 버전 범위

### V1

- 회원·인증, 식당·테이블·회차 관리 및 사용자 조회
- 식당 이미지 Presigned URL 업로드와 조회용 Presigned GET URL 반환
- 예약 가능 여부, 결제 준비, 예약·참여 집계 조회, 모집 마감
- PortOne 결제 완료 검증·웹훅, 결제·환불 조회
- 지급 예정 금액·예약별 지급 예정 내역·상세

### V2

- 로그아웃·토큰 재발급
- 참여 취소·식당 귀책 취소·모집 마감/실패/환불/회차 복구 내부 처리
- 유효 참여자 최소 목록, 노쇼, 관리자 현황·통계
- 예약 참여자 채팅과 API·비즈니스 로그, 소프트 딜리트·배포 운영 정책

### V3

- Actuator 모니터링·헬스 체크
- 실패 결제·환불 재처리, 정산 데이터 재집계
- 부하 테스트, 검색 성능·이벤트 처리·AI Moderation·Restaurant Feedback Insight·모니터링·배포 고도화

Redis는 ERD 엔티티가 아니며 인증·검색 캐시와 V3 채팅 Pub/Sub에 사용한다. 채팅은 DB 커밋 뒤 Redis Pub/Sub으로 실시간 전달만 수행하고, 메시지 저장·재전송·AI 분석은 담당하지 않는다. Kafka는 V3 AI Moderation의 Outbox 기반 비동기 분석·재처리 경로이며, Restaurant Feedback Insight는 같은 ChatMessage Event를 별도 Consumer Group으로 재사용한다. Redis Pub/Sub과 Kafka는 서로 대체하지 않는다.

Restaurant Feedback Insight는 코드·테스트·Evidence 기준으로 구현됐지만 production 기본 설정은 `bobfull.kafka.restaurant-insight.consumer-enabled=false`, `bobfull.ai.restaurant-insight.enabled=false`다. 배포 스크립트의 환경변수 주입 경로가 존재하더라도 실제 운영에서 enabled 상태라고 단정하지 않는다.

V3 인프라 판단은 Evidence 기준으로 구분한다. ALB 뒤 Active App EC2 2대, Blue-Green 전환, Inactive App EC2 평상시 STOP, Prometheus target 갱신은 저장소 Evidence로 검증된 구조다. App EC2 Auto Scaling은 #191에서 측정했으나 현재 부하 기준으로 App CPU/처리량 포화 근거가 부족해 미도입으로 정리한다.

## 9. API 공통 계약

- 일반 서비스 API는 `/api/**`를 사용하며 URL에 버전을 넣지 않는다.
- Actuator는 `/actuator/**`, WebSocket 연결 Endpoint는 `/ws`다.
- 성공 응답은 `success`, `message`, `data`를, 실패 응답은 `success`, `code`, `message`를 사용한다.
- OWNER·ADMIN API는 `/api/owner`, `/api/admin`으로 분리한다.

## 10. 역할 분배

| 이름 | 핵심 도메인 | 프로젝트 책임 범위 |
|---|---|---|
| 김현승 | 예약금 결제·환불·지급 예정 예약금 | AI·채팅 |
| 김홍기 | 합석 테이블·예약 시간·검색 | 배포·인프라·모니터링 전반 |
| 배지현 | 예약·참여·좌석 재고·동시성 | 프론트엔드 전반 |
| 정용태 | 회원·인증·사장님·식당·관리자 | 캐시·조회 성능·K6 |

배지현은 BobFull Frontend 전반과 Backend API 연동 영역을 담당한다. 이 Backend 저장소에서 세부 Frontend 구현 내역을 확인할 수 없는 항목은 기능별로 임의 확정하지 않는다.

김홍기는 AWS 운영 환경, EC2/RDS/ALB 기반 인프라, Docker/ECR, GitHub Actions CI/CD, SSM 기반 배포, Blue-Green 배포, App EC2 다중화, Redis/Kafka 운영 인프라 구성·연결, Prometheus/Grafana, Health Check와 운영 모니터링, 운영 장애 분석, EC2 메모리 병목·Hikari Connection Pool 병목 검증, Auto Scaling 필요성 측정과 미도입 판단, 배포/인프라 Evidence 정리를 담당한다. 이는 운영 인프라 관점의 책임이며 Redis/Kafka의 모든 비즈니스 로직 구현 책임을 뜻하지 않는다.

## 11. 관련 문서

- 제품 방향·초기 타깃·MVP 범위: [`prd.md`](prd.md)
- API 명세: [`bobfull-api-spec-complete.md`](../020-api/bobfull-api-spec-complete.md)
- 데이터 모델: [`erd.md`](../030-data/erd.md)
- 논리 책임 경계: [`architecture.md`](../040-architecture/architecture.md)
- 도메인 변경 영향: [`domain-dependencies.md`](../040-architecture/domain-dependencies.md)
