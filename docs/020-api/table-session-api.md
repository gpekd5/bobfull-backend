# 합석 테이블 / 회차 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `POST` | `/api/owner/restaurants/{restaurantId}/tables` | `OWNER` | 합석 테이블 등록 | 201 |
| `POST` | `/api/owner/restaurants/{restaurantId}/tables/bulk` | `OWNER` | 합석 테이블 일괄 등록 | 201 |
| `GET` | `/api/owner/restaurants/{restaurantId}/tables` | `OWNER` | 합석 테이블 목록 조회 | 200 |
| `GET` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 상세 조회 | 200 |
| `PATCH` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 수정 | 200 |
| `DELETE` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 삭제 | 200 |
| `POST` | `/api/owner/tables/{tableId}/dining-sessions` | `OWNER` | 합석 회차 등록 | 201 |
| `POST` | `/api/owner/tables/{tableId}/dining-sessions/bulk` | `OWNER` | 기존 테이블 합석 회차 일괄 등록 | 201 |
| `GET` | `/api/owner/restaurants/{restaurantId}/dining-sessions` | `OWNER` | 사장님용 회차 목록 조회 | 200 |
| `GET` | `/api/restaurants/{restaurantId}/dining-sessions` | `PUBLIC` | 사용자용 예약 가능 회차 조회 | 200 |
| `PATCH` | `/api/owner/dining-sessions/{sessionId}` | `OWNER` | 합석 회차 수정 | 200 |
| `DELETE` | `/api/owner/dining-sessions/{sessionId}` | `OWNER` | 합석 회차 삭제 | 200 |

## 최신 계약 메모

- `capacity`의 2/4/6/8 허용값은 DTO annotation이 아니라 서비스/도메인에서 검증한다.
- `GET /api/restaurants/{restaurantId}/dining-sessions`는 `PUBLIC`이다.
- 목록형 API의 기본 페이지 크기는 20이다.

---

# 상세 명세

## POST /api/owner/restaurants/{restaurantId}/tables — 합석 테이블 등록

**권한** `OWNER`


### 개요

- 설명: 정원 2·4·6·8명
- 생성 시 서버가 합석 테이블 `status`를 `ACTIVE`로 적용한다. 상태 변경 API는 이번 범위에 없다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Body

```json
{
  "capacity": 8
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `capacity` | Integer | Y | capacity 값(2·4·6·8 중 하나) |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "tableId": 1,
    "displayNumber": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_TABLE_CAPACITY` | capacity가 `2`, `4`, `6`, `8` 중 하나가 아님 |
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## POST /api/owner/restaurants/{restaurantId}/tables/bulk — 합석 테이블 일괄 등록

**권한** `OWNER`


### 개요

- 설명: 동일 정원의 테이블을 한 번에 1~10개 등록한다. 표시 번호는 식당별 기존 최대 번호 다음부터 연속 발급한다.

### Request

```json
{
  "capacity": 4,
  "count": 3
}
```

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "createdTableCount": 3,
    "tables": [
      { "tableId": 1, "restaurantId": 1, "displayNumber": 1, "capacity": 4, "status": "ACTIVE" },
      { "tableId": 2, "restaurantId": 1, "displayNumber": 2, "capacity": 4, "status": "ACTIVE" },
      { "tableId": 3, "restaurantId": 1, "displayNumber": 3, "capacity": 4, "status": "ACTIVE" }
    ]
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_TABLE_CAPACITY` | capacity가 `2`, `4`, `6`, `8` 중 하나가 아님 |
| `400` | `INVALID_INPUT_VALUE` | count가 1~10 범위를 벗어나거나 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 본인 식당이 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/owner/restaurants/{restaurantId}/tables — 합석 테이블 목록 조회

**권한** `OWNER`


### 개요

- 설명: 본인 식당 권한 검증

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

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
        "tableId": 1,
        "restaurantId": 1,
        "displayNumber": 1,
        "capacity": 4,
        "status": "ACTIVE"
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


## GET /api/owner/tables/{tableId} — 합석 테이블 상세 조회

**권한** `OWNER`


### 개요

- 설명: 테이블 정원 포함

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `tableId` | Long | Y | tableId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "tableId": 1,
    "restaurantId": 1,
    "displayNumber": 1,
    "capacity": 4,
    "status": "ACTIVE"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `TABLE_ID_NOT_FOUND` | tableId에 해당하는 대상을 찾을 수 없음 |

---


## PATCH /api/owner/tables/{tableId} — 합석 테이블 수정

**권한** `OWNER`


### 개요

- 설명: 예약 존재 시 정원 변경 제한

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `tableId` | Long | Y | tableId 식별자 |

### Body

```json
{
  "capacity": 4
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `capacity` | Integer | Y | capacity 값(2·4·6·8 중 하나) |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "tableId": 1,
    "displayNumber": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_TABLE_CAPACITY` | capacity가 `2`, `4`, `6`, `8` 중 하나가 아님 |
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `TABLE_ID_NOT_FOUND` | tableId에 해당하는 대상을 찾을 수 없음 |
| `409` | `TABLE_HAS_RESERVATION` | 활성 예약이 있어 테이블 정원을 변경할 수 없음 |

---


## DELETE /api/owner/tables/{tableId} — 합석 테이블 삭제

**권한** `OWNER`


### 개요

- 설명: 소프트 딜리트 처리, 연결된 회차가 있으면 삭제 제한

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `tableId` | Long | Y | tableId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "tableId": 1,
    "displayNumber": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `TABLE_ID_NOT_FOUND` | tableId에 해당하는 대상을 찾을 수 없음 |
| `409` | `TABLE_HAS_DINING_SESSION` | 연결된 회차가 있어 삭제할 수 없음 |

---


## POST /api/owner/tables/{tableId}/dining-sessions — 합석 회차 등록

**권한** `OWNER`


### 개요

- 설명: 날짜·시작·종료 시간을 지정해 회차 1건을 등록한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `tableId` | Long | Y | tableId 식별자 |

### Body

```json
{
  "startAt": "2026-07-25T18:00:00",
  "endAt": "2026-07-25T20:00:00"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startAt` | String | Y | startAt 값 |
| `endAt` | String | Y | endAt 값 |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "sessionId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `TABLE_ID_NOT_FOUND` | tableId에 해당하는 대상을 찾을 수 없음 |

---


## POST /api/owner/tables/{tableId}/dining-sessions/bulk — 기존 테이블 합석 회차 일괄 등록

**권한** `OWNER`


### 개요

- 설명: 이미 등록된 합석 테이블(`tableId`)을 대상으로 여러 날짜의 시작·종료 시간 범위를 `intervalMinutes` 단위로 나누어 회차를 일괄 생성한다. 테이블 생성은 `POST /api/owner/restaurants/{restaurantId}/tables`에서 먼저 수행하고, 이 API는 기존 테이블의 예약 가능 시간 등록에만 사용한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `tableId` | Long | Y | 회차를 추가할 기존 합석 테이블 식별자 |

### Body

```json
{
  "dates": [
    "2026-07-25",
    "2026-07-26"
  ],
  "startTime": "18:00:00",
  "endTime": "20:00:00",
  "intervalMinutes": 30
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `dates` | Array&lt;LocalDate&gt; | Y | 회차를 생성할 날짜 목록 |
| `startTime` | LocalTime | Y | 각 날짜의 첫 회차 시작 시간 |
| `endTime` | LocalTime | Y | 각 날짜의 회차 생성 종료 기준 시간 |
| `intervalMinutes` | Integer | Y | 회차 길이와 다음 회차 시작 간격(분). `startTime`~`endTime` 범위를 나누어 떨어지게 해야 함 |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "tableId": 1,
    "createdSessionCount": 8
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `TABLE_ID_NOT_FOUND` | tableId에 해당하는 대상을 찾을 수 없음 |
| `409` | `DUPLICATE_DINING_SESSION` | 동일 시간대 회차가 중복됨 |

---


## GET /api/owner/restaurants/{restaurantId}/dining-sessions — 사장님용 회차 목록 조회

**권한** `OWNER`


### 개요

- 설명: 날짜 필터

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `date` | LocalDate | N | date 조건 |

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
        "sessionId": 1,
        "tableId": 1,
        "capacity": 4,
        "startAt": "2026-07-25T18:00:00+09:00",
        "endAt": "2026-07-25T20:00:00+09:00"
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


## GET /api/restaurants/{restaurantId}/dining-sessions — 사용자용 예약 가능 회차 조회

**권한** `PUBLIC`


### 개요

- 설명: 예약 가능한 회차만

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `date` | LocalDate | Y | date 조건 |
| `partySize` | Integer | N | partySize 조건 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "restaurantId": 1,
    "content": [
      {
        "sessionId": 1,
        "tableId": 1,
        "capacity": 4,
        "startAt": "2026-07-25T18:00:00+09:00",
        "endAt": "2026-07-25T20:00:00+09:00",
        "availableCapacity": 4,
        "reservationId": null,
        "currentParticipantCount": 0
      }
    ]
  }
}
```

- `reservationId`는 이 회차를 이미 점유한 활성(`RECRUITING`/`CONFIRMED`) Reservation이 없으면 `null`이다. `null`이면 새 예약 생성(`type=CREATE`), 값이 있으면 해당 예약 참여(`type=JOIN`)의 targetId로 사용한다.
- `currentParticipantCount`는 활성 Reservation의 결제 완료(`RESERVED`) 참여자 partySize 합계이며, 활성 Reservation이 없으면 `0`이다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | `partySize`가 0 이하이거나 요청 파라미터 형식이 올바르지 않음 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## PATCH /api/owner/dining-sessions/{sessionId} — 합석 회차 수정

**권한** `OWNER`


### 개요

- 설명: 예약 존재 시 수정 제한

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `sessionId` | Long | Y | sessionId 식별자 |

### Body

```json
{
  "startAt": "2026-07-25T18:30:00",
  "endAt": "2026-07-25T20:30:00"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `startAt` | String | Y | startAt 값 |
| `endAt` | String | Y | endAt 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "sessionId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `SESSION_ID_NOT_FOUND` | sessionId에 해당하는 대상을 찾을 수 없음 |
| `409` | `SESSION_HAS_RESERVATION` | 연결된 활성 예약이 있어 회차를 수정할 수 없음 |

---


## DELETE /api/owner/dining-sessions/{sessionId} — 합석 회차 삭제

**권한** `OWNER`


### 개요

- 설명: 소프트 딜리트 처리, 예약이 존재하면 삭제 제한

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `sessionId` | Long | Y | sessionId 식별자 |

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "sessionId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `SESSION_ID_NOT_FOUND` | sessionId에 해당하는 대상을 찾을 수 없음 |
| `409` | `SESSION_HAS_RESERVATION` | 연결된 예약이 있어 삭제할 수 없음 |

---
