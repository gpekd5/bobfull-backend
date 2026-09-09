# 노쇼 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/api/owner/reservations/{reservationId}/participations/no-show-candidates` | `OWNER` | 노쇼 처리 대상 참여자 조회 | 200 |
| `POST` | `/api/owner/reservations/{reservationId}/participations/{participationId}/no-show` | `OWNER` | 참여자 노쇼 처리 | 200 |
| `DELETE` | `/api/owner/reservations/{reservationId}/participations/{participationId}/no-show` | `OWNER` | 노쇼 처리 해제 | 200 |
| `GET` | `/api/owner/reservations/{reservationId}/no-show-histories` | `OWNER` | 예약별 노쇼 이력 조회 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}/no-shows` | `OWNER` | 식당 노쇼 고객 조회 | 200 |

## 최신 계약 메모

- 노쇼 API는 모두 `OWNER` 경로다.
- 사장님 노쇼 조회 응답의 이름은 마스킹된다.

---

# 상세 명세

## GET /api/owner/reservations/{reservationId}/participations/no-show-candidates — 노쇼 처리 대상 참여자 조회

**권한** `OWNER`


### 개요

- 설명: 식사 종료 후에만 조회 가능. 종료 경계는 `now >= TimeSlot.endAt`이며(Issue #175), 채팅 신규 메시지 전송 차단과 동일한 경계를 사용한다.

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
        "participationId": 501,
        "memberId": 15,
        "name": "김○○",
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
| `409` | `INVALID_STATE` | 식사 종료 전이라 노쇼 처리 대상을 조회할 수 없음 |

---


## POST /api/owner/reservations/{reservationId}/participations/{participationId}/no-show — 참여자 노쇼 처리

**권한** `OWNER`


### 개요

- 설명: 신청 인원 전체 처리. 노쇼는 사유 없이 방문하지 않은 상태이므로 처리 사유를 저장하지 않는다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |
| `participationId` | Long | Y | participationId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1,
    "participationId": 1
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
| `404` | `PARTICIPATION_ID_NOT_FOUND` | participationId에 해당하는 대상을 찾을 수 없음 |
| `409` | `INVALID_STATE` | 이미 처리된 참여 등 현재 상태에서 노쇼 처리를 할 수 없음 |

---


## DELETE /api/owner/reservations/{reservationId}/participations/{participationId}/no-show — 노쇼 처리 해제

**권한** `OWNER`


### 개요

- 설명: RESERVED로 복구

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `reservationId` | Long | Y | reservationId 식별자 |
| `participationId` | Long | Y | participationId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "reservationId": 1,
    "participationId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |
| `404` | `PARTICIPATION_ID_NOT_FOUND` | participationId에 해당하는 대상을 찾을 수 없음 |
| `409` | `INVALID_STATE` | NO_SHOW 상태가 아니어서 해제할 수 없음 |

---


## GET /api/owner/reservations/{reservationId}/no-show-histories — 예약별 노쇼 이력 조회

**권한** `OWNER`


### 개요

- 설명: 처리자·처리 시각

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
        "noShowHistoryId": 1,
        "participationId": 501,
        "memberId": 15,
        "name": "김○○",
        "partySize": 2,
        "isMarked": true,
        "processedByMemberId": 3,
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
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESERVATION_ID_NOT_FOUND` | reservationId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/owner/restaurants/{restaurantId}/no-shows — 식당 노쇼 고객 조회

**권한** `OWNER`


### 개요

- 설명: 해당 식당에서 노쇼 처리된 고객 목록을 기간 조건과 함께 조회한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startDate` | LocalDate | N | 노쇼 기간 시작일 |
| `endDate` | LocalDate | N | 노쇼 기간 종료일 |
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
        "memberId": 15,
        "name": "김○○",
        "noShowCount": 2,
        "latestNoShowAt": "2026-07-25T18:00:00+09:00",
        "reservationId": 101,
        "participationId": 501,
        "partySize": 2
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

- 본인 식당에서 노쇼 처리된 고객만 조회한다.
- 이름은 마스킹하고 이메일·전화번호는 제공하지 않는다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | `startDate`가 `endDate`보다 늦음 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 본인 식당이 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | 식당을 찾을 수 없음 |

---
