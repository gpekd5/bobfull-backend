# 예약 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/api/reservations/availability` | `AUTHENTICATED` | 예약 가능 여부 확인 | 200 |
| `POST` | `/api/reservations/prepare` | `AUTHENTICATED` | 예약 결제 준비 | 200 |
| `GET` | `/api/reservations/search` | `PUBLIC` | 참여 가능한 예약 검색 | 200 |
| `GET` | `/api/members/me/reservations` | `AUTHENTICATED` | 내 예약 목록 조회 | 200 |
| `GET` | `/api/members/me/reservations/{reservationId}` | `AUTHENTICATED` | 내 예약 상세 조회 | 200 |
| `POST` | `/api/reservations/{reservationId}/participations/me/cancel` | `AUTHENTICATED` | 내 예약 참여 취소 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}/reservations` | `OWNER` | 식당별 예약 목록 조회 | 200 |
| `GET` | `/api/owner/reservations/{reservationId}` | `OWNER` | 사장님용 예약 상세 조회 | 200 |
| `GET` | `/api/owner/reservations/{reservationId}/participations` | `OWNER` | 사장님용 예약 참여자 목록 조회 | 200 |
| `POST` | `/api/owner/reservations/{reservationId}/cancel` | `OWNER` | 식당 귀책 예약 취소 | 200 |

## 최신 계약 메모

- 현재 예약 HTTP API는 10개다.
- `ReservationCancellationRequest.reason`은 `@NotBlank`, `@Size(max=255)`다.

---

# 상세 명세

## GET /api/reservations/availability — 예약 가능 여부 확인

**권한** `AUTHENTICATED`


### 개요

- 설명: 최초 예약 생성과 기존 예약 추가 참여 가능 여부를 하나의 API에서 확인한다.

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `type` | `PaymentPurpose` | Y | `CREATE` 또는 `JOIN` |
| `targetId` | Long | Y | `CREATE`는 sessionId, `JOIN`은 reservationId |
| `partySize` | Integer | Y | `CREATE`는 `1 <= partySize <= table.capacity`, `JOIN`은 `1 <= partySize <= availableCapacity` |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "available": true,
    "availableCapacity": 4,
    "reason": null
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_PARTY_SIZE` | partySize가 1 이상이 아니거나 CREATE의 테이블 정원을 초과함 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `RESOURCE_NOT_FOUND` | 대상 회차 또는 예약을 찾을 수 없음 |
| `409` | `ACTIVE_RESERVATION_ALREADY_EXISTS` | CREATE 대상 TimeSlot에 `RECRUITING` 또는 `CONFIRMED` Reservation, 또는 만료되지 않은 CREATE READY Payment가 이미 존재함 |
| `409` | `INSUFFICIENT_REMAINING_CAPACITY` | JOIN의 partySize가 availableCapacity를 초과함 |
| `409` | `INVALID_STATE` | 현재 상태에서 예약 또는 참여가 불가능함 |

---


## POST /api/reservations/prepare — 예약 결제 준비

**권한** `AUTHENTICATED`


### 개요

- 설명: 최초 예약 생성과 기존 예약 추가 참여의 결제 준비를 하나의 API에서 처리한다. 좌석을 10분간 임시 선점하고 PortOne 결제용 `paymentId`를 발급한다.

### Request

### Body

```json
{
  "type": "CREATE",
  "targetId": 10,
  "partySize": 3
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `type` | String | Y | `CREATE` 또는 `JOIN` |
| `targetId` | Long | Y | `CREATE`는 sessionId, `JOIN`은 reservationId |
| `partySize` | Integer | Y | `CREATE`는 `1 <= partySize <= table.capacity`, `JOIN`은 `1 <= partySize <= availableCapacity` |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "paymentId": "PAY-20260725-0001",
    "paymentStatus": "READY",
    "amount": 30000,
    "expiresAt": "2026-07-25T17:10:00+09:00"
  }
}
```

- 결제 성공 전에는 예약 또는 참여자를 생성하지 않는다.
- 결제 실패 시 `FAILED`, 시간 만료 시 `EXPIRED`로 정규화하며 좌석은 `expiresAt` 기준으로 즉시 반환한다.
- `CREATE`는 대상 TimeSlot을 잠근 뒤 활성 Reservation과 만료되지 않은 CREATE READY Payment를 차례로 확인한다. 둘 다 없을 때만 CREATE READY를 생성하며, 유효한 CREATE READY는 TimeSlot당 최대 1건이다.
- 유효한 CREATE READY가 있으면 `409 ACTIVE_RESERVATION_ALREADY_EXISTS`를 반환한다. 만료 또는 `FAILED` 처리 후에는 새 CREATE 요청을 허용한다. `JOIN`은 기존 Reservation의 `availableCapacity`를 기준으로 별도 처리한다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_PARTY_SIZE` | partySize가 1 이상이 아니거나 CREATE의 테이블 정원을 초과함 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `RESOURCE_NOT_FOUND` | 대상 회차 또는 예약을 찾을 수 없음 |
| `409` | `ACTIVE_RESERVATION_ALREADY_EXISTS` | CREATE 대상 TimeSlot에 `RECRUITING` 또는 `CONFIRMED` Reservation, 또는 만료되지 않은 CREATE READY Payment가 이미 존재함 |
| `409` | `INSUFFICIENT_REMAINING_CAPACITY` | JOIN의 partySize가 availableCapacity를 초과함 |
| `409` | `INVALID_STATE` | 현재 상태에서 요청을 처리할 수 없음 |

---


## GET /api/reservations/search — 참여 가능한 예약 검색

**권한** `PUBLIC`


### 개요

- 설명: QueryDSL 적용

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `keyword` | String | N | keyword 조건 |
| `date` | LocalDate | N | date 조건 |
| `time` | LocalTime | N | time 조건 |
| `capacity` | Integer | N | capacity 조건 |
| `minimumRemainingSeats` | Integer | N | minimumRemainingSeats 조건 |
| `page` | Integer | N | page 조건 |
| `size` | Integer | N | size 조건 |
| `sort` | String | N | sort 조건 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "reservationId": 1,
        "restaurantId": 1,
        "restaurantName": "밥풀식당",
        "sessionId": 1,
        "tableId": 1,
        "capacity": 4,
        "startAt": "2026-07-25T18:00:00+09:00",
        "endAt": "2026-07-25T20:00:00+09:00",
        "reservationStatus": "RECRUITING",
        "recruitmentStatus": "OPEN",
        "currentParticipantCount": 2,
        "availableCapacity": 2,
        "confirmationThreshold": 3
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### Error

- 별도 도메인 오류 없음


## GET /api/members/me/reservations — 내 예약 목록 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 최초·추가 참여 모두

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationStatus` | String | N | ReservationStatus 조건 |
| `page` | Integer | N | page 조건 |
| `size` | Integer | N | size 조건 |

요청 Body는 사용하지 않는다.

> 페이징 요청은 Spring `Pageable`의 `page`, `size`, `sort`를 지원하며 기본 `size=20`이다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "reservationId": 1,
        "restaurantId": 1,
        "restaurantName": "밥풀식당",
        "sessionId": 1,
        "startAt": "2026-07-25T18:00:00+09:00",
        "endAt": "2026-07-25T20:00:00+09:00",
        "reservationStatus": "RECRUITING",
        "recruitmentStatus": "OPEN",
        "participationId": 10,
        "partySize": 2,
        "participationStatus": "RESERVED",
        "paymentStatus": "PAID"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |

---


## GET /api/members/me/reservations/{reservationId} — 내 예약 상세 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 본인 참여 정보 포함

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1,
    "restaurantId": 1,
    "restaurantName": "밥풀식당",
    "sessionId": 1,
    "startAt": "2026-07-25T18:00:00+09:00",
    "endAt": "2026-07-25T20:00:00+09:00",
    "reservationStatus": "RECRUITING",
    "recruitmentStatus": "OPEN",
    "participationId": 10,
    "partySize": 2,
    "participationStatus": "RESERVED",
    "paymentId": "PAY-20260725-0001",
    "paymentStatus": "PAID"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |

---


## POST /api/reservations/{reservationId}/participations/me/cancel — 내 예약 참여 취소

**권한** `AUTHENTICATED`


### 개요

- 설명: 인증된 MEMBER가 본인의 `ReservationParticipant` 한 건에 신청한 `partySize` 전체를 취소한다. 부분 취소는 지원하지 않는다.

- 서버 시간 기준 식사 시작 2시간 전까지만 취소할 수 있다. 2시간 이내에는 `CANCELLATION_DEADLINE_PASSED`를 반환한다.
- 이 API는 취소를 **접수**만 하고 즉시 `CANCELLED`로 확정하지 않는다(#44, #45). 대상 참여자가 최초 예약자면 예약을 `CANCELLING`으로, 모든 유효 참여자를 `CANCEL_REQUESTED`로 전환해 커밋하고, 응답 반환 뒤 참여자별로 Payment 전체 금액의 PortOne 환불을 요청한다. 대상 참여자가 추가 참여자면 해당 `ReservationParticipant`만 `CANCEL_REQUESTED`로 전환하고 본인 Payment 전체 금액을 환불 요청한다.
- 참여자별 환불이 실제로 완료돼야 그 참여자가 `CANCEL_REQUESTED → CANCELLED`로 확정된다. 최초 예약자 취소는 모든 유효 참여자의 환불이 완료된 뒤에야 예약 전체가 `CANCELLED`로 확정되고, 그때 TimeSlot이 새 예약 가능 상태로 복구된다. 추가 참여자 취소는 본인 확정 시점에 `currentParticipantCount`·`availableCapacity`를 재계산하며, 모집이 `OPEN`이고 확정 기준 미달이면 Reservation은 `RECRUITING`, 기준 이상이면 `CONFIRMED`를 유지한다. 기존 채팅방은 `CANCELLING` 전환 시점부터 신규 메시지 전송을 종료하며 기존 메시지 조회는 유지한다.
- 이 API의 응답은 접수 결과(`CANCEL_REQUESTED`)만 나타내며, 최종 `CANCELLED` 확정은 이 응답 이후 비동기로 반영된다. 확정 여부는 예약·참여 조회 API로 확인한다.
- `NO_SHOW`, `CANCEL_REQUESTED` 또는 이미 `CANCELLED`인 참여자는 MEMBER 취소 대상이 아니다. 현재 ParticipationStatus에는 `VISITED` 상태가 없다.
- 동일 Payment에는 Refund를 한 건만 생성한다. 기존 환불이 있으면 새 환불을 만들지 않고 `REFUND_ALREADY_REQUESTED`를 반환한다. 환불 결과가 불명확하면(timeout 등) `Refund`는 `REQUESTED`를 유지하고 자동으로 재환불하지 않는다.
- 최초 예약자 취소로 예약 전체가 취소되는 경우 요청 사유는 각 유효 참여자의 `cancelReason`에 동일하게 기록한다. Reservation에는 별도 취소 사유 컬럼을 두지 않는다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |

### Body

```json
{
  "reason": "개인 일정 변경"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reason` | String | Y | reason 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1,
    "participationId": 10,
    "participationStatus": "CANCEL_REQUESTED",
    "cancellationScope": "PARTICIPATION",
    "refundStatus": "REQUESTED"
  }
}
```

- `participationStatus`는 이 응답 시점의 접수 상태 `CANCEL_REQUESTED`를 나타낸다. 실제 `CANCELLED` 확정은 환불 완료 후 비동기로 반영되며 이 API 응답에는 나타나지 않는다(#44, #45).
- `cancellationScope`는 추가 참여자 취소 시 `PARTICIPATION`, 최초 예약자 취소 시 `RESERVATION`이다.
- 최초 예약자 취소의 Response `refundStatus`는 최초 예약자 Payment의 환불 요청 상태다. 다른 유효 참여자 Payment의 환불도 참여자별로 요청한다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 해당 예약의 본인 참여가 아님 |
| `403` | `CANCELLATION_NOT_ALLOWED` | 본인 참여이지만 `NO_SHOW`·`CANCEL_REQUESTED` 등 현재 참여 상태에서 MEMBER 취소가 허용되지 않음 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |
| `404` | `PARTICIPATION_NOT_FOUND` | 본인 ReservationParticipant를 찾을 수 없음 |
| `409` | `CANCELLATION_DEADLINE_PASSED` | 서버 시간 기준 식사 시작 2시간 이내임 |
| `409` | `PARTICIPATION_ALREADY_CANCELLED` | 대상 참여자가 이미 `CANCELLED` 상태임 |
| `409` | `RESERVATION_ALREADY_CANCELLED` | 대상 Reservation이 이미 `CANCELLED` 또는 `CANCELLING`(취소 접수·환불 대기 중) 상태임 |
| `409` | `REFUND_ALREADY_REQUESTED` | 대상 Payment에 대한 Refund가 이미 존재함 |

---


## GET /api/owner/restaurants/{restaurantId}/reservations — 식당별 예약 목록 조회

**권한** `OWNER`


### 개요

- 설명: 날짜·상태 조건

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `date` | LocalDate | N | date 조건 |
| `reservationStatus` | String | N | ReservationStatus 조건 |
| `page` | Integer | N | page 조건 |
| `size` | Integer | N | size 조건 |

요청 Body는 사용하지 않는다.

> 페이징 요청은 Spring `Pageable`의 `page`, `size`, `sort`를 지원하며 기본 `size=20`이다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "reservationId": 1,
        "sessionId": 1,
        "tableId": 1,
        "capacity": 4,
        "startAt": "2026-07-25T18:00:00+09:00",
        "endAt": "2026-07-25T20:00:00+09:00",
        "reservationStatus": "RECRUITING",
        "recruitmentStatus": "OPEN",
        "currentParticipantCount": 2,
        "availableCapacity": 2,
        "confirmationThreshold": 3
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/owner/reservations/{reservationId} — 사장님용 예약 상세 조회

**권한** `OWNER`


### 개요

- 설명: 본인 식당 예약만

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1,
    "restaurantId": 1,
    "sessionId": 1,
    "tableId": 1,
    "capacity": 4,
    "startAt": "2026-07-25T18:00:00+09:00",
    "endAt": "2026-07-25T20:00:00+09:00",
    "reservationStatus": "RECRUITING",
    "recruitmentStatus": "OPEN",
    "currentParticipantCount": 2,
    "availableCapacity": 2,
    "confirmationThreshold": 3
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/owner/reservations/{reservationId}/participations — 사장님용 예약 참여자 목록 조회

**권한** `OWNER`


### 개요

- 설명: 신청 인원·참여 상태

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |

요청 Body는 사용하지 않는다.

> 페이징 요청은 Spring `Pageable`의 `page`, `size`, `sort`를 지원하며 기본 `size=20`이다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "participationId": 10,
        "memberId": 15,
        "name": "홍길동",
        "partySize": 2,
        "participationStatus": "RESERVED"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |

---


## POST /api/owner/reservations/{reservationId}/cancel — 식당 귀책 예약 취소

**권한** `OWNER`


### 개요

- 설명: OWNER가 본인 식당 사유로 예약 전체를 취소하고 유효 참여자 전액 환불을 요청한다. MEMBER 취소 API와 권한·처리 범위가 다르다.

- 이 API는 취소를 접수만 하고 즉시 `CANCELLED`로 확정하지 않는다(#44, #45, #46). Reservation을 `CANCELLING`으로, 유효 참여자를 모두 `CANCEL_REQUESTED`로 전환하며, 참여자별 환불이 모두 완료되면 `CANCELLED`로 확정된다. 참여자를 `NO_SHOW`로 처리하지 않는다.
- OWNER 강제 취소는 MEMBER 취소(회원 본인 취소 API)와 달리 취소 기한(2시간) 제약을 두지 않는다(2026-08-06 Human 확정). 식사가 이미 시작했거나 끝난 뒤에도 식당 귀책으로 전체 취소·전액 환불을 접수할 수 있다.
- TimeSlot은 예약이 `CANCELLED`로 확정되고 다른 제약이 없는 경우만 새 예약 가능 상태로 복구된다.
- 전체 취소 사유는 각 유효 참여자의 `cancelReason`에 동일하게 기록한다. Reservation에는 별도 취소 사유 컬럼을 두지 않는다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |

### Body

```json
{
  "reason": "식당 내부 사정"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reason` | String | Y | reason 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |
| `409` | `INVALID_STATE` | 현재 상태에서 요청을 처리할 수 없음 |

---
