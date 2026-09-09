# 관리자 / Moderation API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/api/admin/members` | `ADMIN` | 회원 목록 조회 | 200 |
| `GET` | `/api/admin/members/{memberId}` | `ADMIN` | 회원 상세 조회 | 200 |
| `GET` | `/api/admin/restaurants` | `ADMIN` | 식당 목록 조회 | 200 |
| `GET` | `/api/admin/restaurants/{restaurantId}` | `ADMIN` | 식당 상세 조회 | 200 |
| `GET` | `/api/admin/reservations` | `ADMIN` | 전체 예약 현황 조회 | 200 |
| `GET` | `/api/admin/payments` | `ADMIN` | 전체 결제 현황 조회 | 200 |
| `GET` | `/api/admin/refunds` | `ADMIN` | 전체 환불 현황 조회 | 200 |
| `GET` | `/api/admin/no-shows` | `ADMIN` | 전체 노쇼 현황 조회 | 200 |
| `GET` | `/api/admin/statistics/overview` | `ADMIN` | 전체 운영 지표 조회 | 200 |
| `GET` | `/api/admin/statistics/restaurants` | `ADMIN` | 식당별 예약 성사율 조회 | 200 |
| `GET` | `/api/admin/statistics/members/no-show-rates` | `ADMIN` | 사용자별 노쇼율 조회 | 200 |
| `GET` | `/api/admin/moderation/members` | `ADMIN` | 채팅 moderation 회원별 집계 조회 | 200 |
| `GET` | `/api/admin/moderation/members/{memberId}` | `ADMIN` | 채팅 moderation 회원별 상세 조회 | 200 |
| `GET` | `/api/admin/moderation/reports` | `ADMIN` | 관리자 신고 목록 조회 | 200 |
| `GET` | `/api/admin/moderation/reports/{reportId}` | `ADMIN` | 관리자 신고 상세 조회 | 200 |
| `PATCH` | `/api/admin/moderation/reports/{reportId}/review` | `ADMIN` | 관리자 신고 검토 | 200 |

## 최신 계약 메모

- 모든 Endpoint는 `ROLE_ADMIN`이 필요하다.
- PENDING 신고의 `decision`, `reviewedByMemberId`, `reviewedAt`은 null일 수 있다.
- 신고 상세 `context[].moderation`은 분석 레코드가 없으면 null일 수 있다.

---

# 상세 명세

## GET /api/admin/members — 회원 목록 조회

**권한** `ADMIN`


### 개요

- 설명: 검색·페이징

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `keyword` | String | N | keyword 조건 |
| `role` | String | N | MemberRole 조건(`MEMBER`, `OWNER`, `ADMIN`) |
| `deleted` | Boolean | N | 탈퇴 여부 조건. `true`는 `deletedAt`이 있는 회원, `false`는 활성 회원 |
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
        "memberId": 1,
        "email": "user@example.com",
        "name": "홍길동",
        "role": "MEMBER",
        "noShowCount": 1,
        "createdAt": "2026-07-21T10:00:00+09:00",
        "deletedAt": null
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/members/{memberId} — 회원 상세 조회

**권한** `ADMIN`


### 개요

- 설명: 노쇼 정보 포함

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `memberId` | Long | Y | memberId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "phoneNumber": "01012345678",
    "role": "MEMBER",
    "noShowCount": 1,
    "createdAt": "2026-07-21T10:00:00+09:00",
    "deletedAt": null
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |
| `404` | `MEMBER_ID_NOT_FOUND` | memberId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/admin/restaurants — 식당 목록 조회

**권한** `ADMIN`


### 개요

- 설명: 검색·페이징

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `keyword` | String | N | keyword 조건 |
| `restaurantStatus` | String | N | RestaurantStatus 조건(현재 `ACTIVE`) |
| `deleted` | Boolean | N | 삭제 여부 조건. `true`는 `deletedAt`이 있는 식당, `false`는 활성 식당 |
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
        "restaurantId": 1,
        "ownerMemberId": 3,
        "ownerName": "사장님",
        "name": "밥풀식당",
        "category": "한식",
        "status": "ACTIVE",
        "createdAt": "2026-07-21T10:00:00+09:00"
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/restaurants/{restaurantId} — 식당 상세 조회

**권한** `ADMIN`


### 개요

- 설명: 등록 정보 확인

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "restaurantId": 1,
    "ownerMemberId": 3,
    "ownerName": "사장님",
    "name": "밥풀식당",
    "address": "제주시 애월읍 1",
    "category": "한식",
    "description": "합석 예약이 가능한 식당입니다.",
    "keyword": "흑돼지,혼밥",
    "depositPerPerson": 10000,
    "status": "ACTIVE",
    "createdAt": "2026-07-21T10:00:00+09:00",
    "deletedAt": null
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/admin/reservations — 전체 예약 현황 조회

**권한** `ADMIN`


### 개요

- 설명: 상태별 검색

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationStatus` | String | N | ReservationStatus 조건 |
| `startDate` | LocalDate | N | startDate 조건 |
| `endDate` | LocalDate | N | endDate 조건 |
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
        "creatorMemberId": 15,
        "startAt": "2026-07-25T18:00:00+09:00",
        "reservationStatus": "CONFIRMED",
        "recruitmentStatus": "OPEN",
        "currentParticipantCount": 3,
        "capacity": 4
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/payments — 전체 결제 현황 조회

**권한** `ADMIN`


### 개요

- 설명: 결제 상태 필터

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `paymentStatus` | String | N | PaymentStatus 조건 |
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
        "paymentId": "PAY-20260725-0001",
        "memberId": 15,
        "reservationId": 1,
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/refunds — 전체 환불 현황 조회

**권한** `ADMIN`


### 개요

- 설명: 실패 환불 확인

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
        "memberId": 15,
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/no-shows — 전체 노쇼 현황 조회

**권한** `ADMIN`


### 개요

- 설명: 사용자·식당 조건

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `memberId` | Long | N | memberId 조건 |
| `restaurantId` | Long | N | restaurantId 조건 |
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
        "noShowHistoryId": 1,
        "memberId": 15,
        "memberName": "김○○",
        "restaurantId": 1,
        "restaurantName": "밥풀식당",
        "reservationId": 101,
        "participationId": 501,
        "partySize": 2,
        "processedAt": "2026-07-25T21:00:00+09:00"
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/statistics/overview — 전체 운영 지표 조회

**권한** `ADMIN`


### 개요

- 설명: 예약 성사율·노쇼율

### Request

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "totalReservationCount": 1000,
    "reservationConfirmationRate": 78.0,
    "noShowRate": 3.5
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/statistics/restaurants — 식당별 예약 성사율 조회

**권한** `ADMIN`


### 개요

- 설명: 기간 조건에 따라 식당별 예약 성사율 통계를 조회한다.

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startDate` | LocalDate | N | startDate 조건 |
| `endDate` | LocalDate | N | endDate 조건 |
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
        "restaurantId": 1,
        "restaurantName": "밥풀식당",
        "totalReservationCount": 120,
        "confirmedReservationCount": 90,
        "confirmationRate": 75.0
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/statistics/members/no-show-rates — 사용자별 노쇼율 조회

**권한** `ADMIN`


### 개요

- 설명: 누적 노쇼율

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
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
        "memberId": 1,
        "name": "홍○동",
        "totalReservationCount": 10,
        "noShowCount": 2,
        "noShowRate": 20.0
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

- 이름은 마스킹하며 이메일·전화번호 등 신규 개인정보는 포함하지 않는다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없음 |

---


## GET /api/admin/moderation/members — 채팅 moderation 회원별 집계 조회

**권한** `ADMIN`


### 개요

- 설명: `FLAGGED` 채팅 분석 결과를 발신 회원별로 집계한다. 목록에는 원문을 포함하지 않는다.

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `status` | String | N | `NORMAL` 또는 `REVIEW_REQUIRED`. 생략 시 FLAGGED 이력이 있는 회원 전체 |
| `page` | Integer | N | 0부터 시작하는 페이지 번호 |
| `size` | Integer | N | 페이지 크기(기본 20) |

`totalFlaggedCount`는 LOW/MEDIUM/HIGH FLAGGED 메시지를, `reviewTargetCount`는 MEDIUM/HIGH 메시지만 각각 `COUNT(DISTINCT messageId)`로 계산한다. `reviewTargetCount >= 3`이면 `REVIEW_REQUIRED`다. SAFE와 ANALYSIS_FAILED는 집계하지 않는다.

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
        "memberId": 27,
        "profanityCount": 4,
        "personalInformationCount": 1,
        "spamCount": 2,
        "totalFlaggedCount": 7,
        "reviewTargetCount": 4,
        "reviewStatus": "REVIEW_REQUIRED",
        "lastFlaggedAt": "2026-08-11T00:00:00Z"
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
| `400` | `INVALID_INPUT_VALUE` | status 또는 page/size 값이 올바르지 않음 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | ADMIN 권한이 없음 |

---


## GET /api/admin/moderation/members/{memberId} — 채팅 moderation 회원별 상세 조회

**권한** `ADMIN`


### 개요

- 설명: 회원의 moderation 집계와 FLAGGED 근거 메시지를 함께 조회한다. 이 상세 응답에서만 원문을 노출한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `memberId` | Long | Y | 조회할 회원 식별자 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "memberId": 27,
    "reviewStatus": "REVIEW_REQUIRED",
    "totalFlaggedCount": 4,
    "reviewTargetCount": 3,
    "riskCounts": { "LOW": 1, "MEDIUM": 2, "HIGH": 1 },
    "evidences": [
      {
        "messageId": 382,
        "content": "실제 ChatMessage 원문",
        "categories": ["PROFANITY"],
        "riskLevel": "MEDIUM",
        "countedForReview": true,
        "sentAt": "2026-08-11T00:00:00Z",
        "analyzedAt": "2026-08-11T00:00:01Z"
      }
    ]
  }
}
```

- evidences는 FLAGGED 메시지만 포함하며 SAFE와 ANALYSIS_FAILED는 제외한다.
- REVIEW_REQUIRED은 자동 제재나 처리 완료 상태를 뜻하지 않으며, 관리자 확인 대상이라는 누적 신호다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | ADMIN 권한이 없음 |
| `404` | `MEMBER_ID_NOT_FOUND` | memberId에 해당하는 회원이 없음 |

---


## GET /api/admin/moderation/reports — 관리자 신고 목록 조회

**권한** `ADMIN`


### 개요

- Success: `200 OK`, `ApiResponse<PageResponse<AdminModerationReportResponse>>`

### Request

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `status` | `PENDING \| REVIEWED` | N | 생략 시 `PENDING` |
| `page` | int | N | Spring pageable 페이지, 기본 `0` |
| `size` | int | N | 기본 `20` |

### Response `data`

`content`의 각 항목은 `reportId`, `chatRoomId`, `reporterMemberId`, `reportedMemberId`, `reason`, `status`, `anchorMessageId`, `createdAt`, `decision`, `reviewedByMemberId`, `reviewedAt`을 반환한다.

### Error

| Status | Code | 조건 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 미인증 |
| `403` | `ACCESS_DENIED` | `ADMIN` 역할 아님 |
| `400` | `INVALID_INPUT_VALUE` | enum·pageable 파라미터 형식 오류 |


## GET /api/admin/moderation/reports/{reportId} — 관리자 신고 상세 조회

**권한** `ADMIN`

### Request

| 구분 | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---:|---|
| Path | `reportId` | Long | Y | 신고 ID |

### 개요

- Success: `200 OK`, `ApiResponse<AdminModerationReportDetailResponse>`

### Response `data`

기본 신고 정보(`reportId`, `chatRoomId`, `reason`, `detail`, `reporterMemberId`, `reportedMemberId`, `anchorMessageId`, `createdAt`, `status`)와 다음을 반환한다.

- `context`: anchor가 있으면 해당 메시지를 포함한 전후 최대 5건씩, 없으면 신고 생성 시각 이하의 최근 최대 20건. 각 메시지는 `messageId`, `senderMemberId`, `content`, `sentAt`, 선택적 `moderation`을 포함한다.
- `moderationSignals`: 신고 대상 회원의 moderation 집계(`totalFlaggedCount`, `reviewTargetCount`, `profanityCount`, `personalInformationCount`, `spamCount`).
- `reportSignals`: 신고 대상 회원의 `pendingReportCount`, `reviewedReportCount`, `confirmedViolationCount`.

### Error

| Status | Code | 조건 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 미인증 |
| `403` | `ACCESS_DENIED` | `ADMIN` 역할 아님 |
| `404` | `CHAT_ROOM_REPORT_NOT_FOUND` | 신고 없음 |


## PATCH /api/admin/moderation/reports/{reportId}/review — 관리자 신고 검토

**권한** `ADMIN`


### 개요

- Success: `200 OK`, `ApiResponse<AdminModerationReportResponse>`

### Request

| 구분 | 필드 | 타입 | 필수 | 검증·설명 |
|---|---|---|---:|---|
| Path | `reportId` | Long | Y | 신고 ID |
| Body | `decision` | `NO_VIOLATION \| VIOLATION_CONFIRMED` | Y | `@NotNull`; `PENDING` 상태만 `REVIEWED`로 전이 가능 |

### Response `data`

응답 항목은 신고 목록 항목과 같으며, 검토 성공 시 `status=REVIEWED`, `decision`, `reviewedByMemberId`, `reviewedAt`이 채워진다.

### Error

| Status | Code | 조건 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | `decision` 누락 또는 형식 오류 |
| `401` | `UNAUTHORIZED` | 미인증 |
| `403` | `ACCESS_DENIED` | `ADMIN` 역할 아님 |
| `404` | `CHAT_ROOM_REPORT_NOT_FOUND` | 신고 없음 |
| `409` | `CHAT_ROOM_REPORT_ALREADY_REVIEWED` | 이미 검토됐거나 동시 검토 경합에서 뒤처짐 |
