# 결제 / 환불 / 정산 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `POST` | `/api/payments/{paymentId}/complete` | `AUTHENTICATED` | 결제 완료 검증 | 200 |
| `GET` | `/api/members/me/payments` | `AUTHENTICATED` | 내 결제 목록 조회 | 200 |
| `GET` | `/api/payments/{paymentId}` | `AUTHENTICATED` | 결제 상세 조회 | 200 |
| `GET` | `/api/members/me/refunds` | `AUTHENTICATED` | 내 환불 목록 조회 | 200 |
| `GET` | `/api/refunds/{refundId}` | `AUTHENTICATED` | 환불 상세 조회 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}/settlements/expected` | `OWNER` | 지급 예정 금액 조회 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}/settlements/reservations` | `OWNER` | 예약별 지급 예정 내역 조회 | 200 |
| `GET` | `/api/owner/settlements/reservations/{reservationId}` | `OWNER` | 예약별 지급 예정 상세 조회 | 200 |

## 최신 계약 메모

- 결제/환불/정산 비즈니스 API만 이 문서에 둔다. PortOne Webhook은 `operations-api.md`에서 관리한다.
- 과거 관리자 결제 retry / 환불 retry / 정산 recalculate API는 현재 코드에 존재하지 않는다.
- Payment/Refund 시간 필드는 상태에 따라 null일 수 있다.

---

# 상세 명세

## POST /api/payments/{paymentId}/complete — 결제 완료 검증

**권한** `AUTHENTICATED`


### 개요

- 설명: 인증된 결제 당사자가 PortOne 결제 결과를 서버에서 검증하고 결제 완료를 확정한다. `Payment.memberId`와 인증 사용자 ID가 일치해야 한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `paymentId` | String | Y | 결제 식별자 |

### Response

- Status: `200 OK`
- 이미 완료된 Payment를 다시 요청하면 기존 완료 결과를 담아 `200 OK`로 멱등 응답한다.

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "paymentId": "PAY-20260725-0001",
    "paymentStatus": "PAID",
    "reservationId": 1,
    "participationId": 10
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `PAYMENT_ACCESS_DENIED` | Payment.memberId와 인증 사용자 ID가 다름 |
| `404` | `PAYMENT_NOT_FOUND` | paymentId에 해당하는 대상을 찾을 수 없음 |
| `409` | `PAYMENT_VERIFICATION_FAILED` | 결제 검증 실패 |
| `409` | `PAYMENT_EXPIRED` | 결제 가능 시간이 만료됨 |

---


## GET /api/members/me/payments — 내 결제 목록 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 페이징 적용

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `paymentStatus` | String | N | PaymentStatus 조건 |
| `page` | Integer | N | 페이지 번호 |
| `size` | Integer | N | 페이지 크기 |

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
        "paymentId": "PAY-20260725-0001",
        "reservationId": 1,
        "participationId": 10,
        "paymentPurpose": "CREATE",
        "partySize": 2,
        "amount": 30000,
        "currency": "KRW",
        "paymentStatus": "PAID",
        "paidAt": "2026-07-25T17:30:00+09:00"
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


## GET /api/payments/{paymentId} — 결제 상세 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 상태를 포함한 결제 당사자만 조회

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `paymentId` | String | Y | PortOne 결제 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "paymentId": "PAY-20260725-0001",
    "reservationId": 1,
    "participationId": 10,
    "paymentPurpose": "CREATE",
    "partySize": 2,
    "paymentStatus": "PAID",
    "amount": 30000,
    "currency": "KRW",
    "expiresAt": "2026-07-25T17:40:00+09:00",
    "paidAt": "2026-07-25T17:30:00+09:00"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `PAYMENT_NOT_FOUND` | paymentId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/members/me/refunds — 내 환불 목록 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 환불 상태 포함

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `refundStatus` | String | N | RefundStatus 조건 |
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
        "refundId": 1,
        "paymentId": "PAY-20260725-0001",
        "reservationId": 1,
        "amount": 30000,
        "refundStatus": "COMPLETED",
        "requestedAt": "2026-07-25T19:00:00+09:00",
        "completedAt": "2026-07-25T19:05:00+09:00"
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


## GET /api/refunds/{refundId} — 환불 상세 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 상태를 포함한 대상 사용자만 조회

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `refundId` | Long | Y | refundId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "refundId": 1,
    "paymentId": "PAY-20260725-0001",
    "reservationId": 1,
    "amount": 30000,
    "refundStatus": "COMPLETED",
    "requestedAt": "2026-07-25T19:00:00+09:00",
    "completedAt": "2026-07-25T19:05:00+09:00"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `REFUND_ID_NOT_FOUND` | refundId에 해당하는 대상을 찾을 수 없음 |


## GET /api/owner/restaurants/{restaurantId}/settlements/expected — 지급 예정 금액 조회

**권한** `OWNER`


### 개요

- 설명: `paidAt`이 존재하는 결제 완료 이력 합계－`COMPLETED` 환불 금액 합계. 기간은 예약 회차(`diningSessionAt`)의 Asia/Seoul 날짜를 양 끝 포함으로 적용하며, 한쪽만 전달하면 열린 구간이다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startDate` | LocalDate | N | startDate 조건 |
| `endDate` | LocalDate | N | endDate 조건 |

`startDate`가 `endDate`보다 늦으면 `400 INVALID_INPUT_VALUE`를 반환한다.

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "totalPaidAmount": 1500000,
    "totalRefundedAmount": 300000,
    "expectedSettlementAmount": 1200000
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


## GET /api/owner/restaurants/{restaurantId}/settlements/reservations — 예약별 지급 예정 내역 조회

**권한** `OWNER`


### 개요

- 설명: 예약 회차(`diningSessionAt`)의 Asia/Seoul 날짜 기준 기간·페이징 적용. 시작일·종료일은 양 끝 포함이며, 한쪽만 전달하면 열린 구간이다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startDate` | LocalDate | N | startDate 조건 |
| `endDate` | LocalDate | N | endDate 조건 |
| `page` | Integer | N | page 조건 |
| `size` | Integer | N | size 조건 |

`startDate`가 `endDate`보다 늦으면 `400 INVALID_INPUT_VALUE`를 반환한다.

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
        "reservationId": 101,
        "diningSessionAt": "2026-07-25T18:00:00+09:00",
        "totalPaidAmount": 90000,
        "totalRefundedAmount": 0,
        "expectedSettlementAmount": 90000
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


## GET /api/owner/settlements/reservations/{reservationId} — 예약별 지급 예정 상세 조회

**권한** `OWNER`


### 개요

- 설명: 결제·환불 내역 포함

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
    "expectedSettlementAmount": 90000,
    "payments": [
      {
        "paymentId": "PAY-20260725-0001",
        "paymentStatus": "PAID",
        "amount": 30000
      }
    ],
    "refunds": [
      {
        "refundId": 1,
        "refundStatus": "COMPLETED",
        "amount": 0
      }
    ]
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |
