# 식당 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `POST` | `/api/owner/restaurants` | `OWNER` | 식당 등록 | 201 |
| `GET` | `/api/owner/restaurants` | `OWNER` | 내 식당 목록 조회 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 내 식당 상세 조회 | 200 |
| `GET` | `/api/owner/restaurants/{restaurantId}/feedback-insights` | `OWNER` | 최근 7일 익명 피드백 집계 조회 | 200 |
| `PATCH` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 식당 정보 수정 | 200 |
| `GET` | `/api/restaurants` | `PUBLIC` | 사용자용 식당 목록·검색 | 200 |
| `GET` | `/api/restaurants/{restaurantId}` | `PUBLIC` | 사용자용 식당 상세 조회 | 200 |
| `DELETE` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 식당 삭제 | 200 |
| `POST` | `/api/owner/restaurants/images/upload-url` | `OWNER` | 식당 이미지 업로드 URL 발급 | 200 |

## 최신 계약 메모

- 사용자 식당 목록/상세 조회는 `PUBLIC`이다.
- `depositPerPerson`은 현재 DTO에서 `@NotNull`만 선언돼 있다.
- 이미지 조회 URL은 nullable일 수 있으며 업로드 URL 발급은 OWNER 전용이다.

---

# 상세 명세

## POST /api/owner/restaurants — 식당 등록

**권한** `OWNER`


### 개요

- 설명: OWNER 권한
- 생성 시 서버가 식당 `status`를 `ACTIVE`로 적용한다. 상태 변경 API는 이번 범위에 없다.

### Request

### Body

```json
{
  "name": "밥풀식당",
  "address": "서울특별시 강남구 테헤란로 123",
  "category": "KOREAN",
  "description": "합석 예약이 가능한 식당입니다.",
  "keyword": "흑돼지,혼밥",
  "depositPerPerson": 10000,
  "imageKey": "restaurants/1/11111111-1111-1111-1111-111111111111.png"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `name` | String | Y | name 값 |
| `address` | String | Y | address 값 |
| `category` | String | Y | category 값 |
| `description` | String | Y | description 값 |
| `keyword` | String | Y | 사장님이 직접 입력하는 식당 키워드 |
| `depositPerPerson` | Integer | Y | depositPerPerson 값 |
| `imageKey` | String | N | 식당 이미지 업로드 URL 발급 응답의 `finalImageKey`. 없으면 이미지 없이 등록 |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "restaurantId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `400` | `INVALID_RESTAURANT_IMAGE_KEY` | 식당 이미지 Key 형식 또는 소유자 경로가 올바르지 않음 |
| `400` | `RESTAURANT_IMAGE_NOT_FOUND` | Lambda 검증 완료 후 최종 경로로 이동된 이미지를 찾을 수 없음 |
| `400` | `RESTAURANT_IMAGE_ALREADY_USED` | 해당 imageKey가 다른 활성 식당에 이미 연결되어 있음 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |

---


## GET /api/owner/restaurants — 내 식당 목록 조회

**권한** `OWNER`


### 개요

- 설명: 본인 식당만 조회

### Request

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
        "name": "밥풀식당",
        "address": "제주시 애월읍 1",
        "category": "한식",
        "depositPerPerson": 10000,
        "status": "ACTIVE",
        "imageUrl": "https://s3-presigned-get-url.example"
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

---


## GET /api/owner/restaurants/{restaurantId} — 내 식당 상세 조회

**권한** `OWNER`


### 개요

- 설명: 식당 소유권 검증

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
    "name": "밥풀식당",
    "address": "제주시 애월읍 1",
    "category": "한식",
    "description": "합석 예약이 가능한 식당입니다.",
    "keyword": "흑돼지,혼밥",
    "depositPerPerson": 10000,
    "status": "ACTIVE",
    "imageUrl": "https://s3-presigned-get-url.example"
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

## GET /api/owner/restaurants/{restaurantId}/feedback-insights — 최근 7일 익명 피드백 집계 조회

**권한** `OWNER`


### 개요

- 설명: 식당 소유권 검증. ChatMessage를 `ChatRoom → Reservation → TimeSlot → SharedTable → Restaurant` 경로로
  역추적해 파생한 `RestaurantFeedbackAnalysis`/`RestaurantFeedbackItem`을 익명 집계로만 노출한다.
- 원문·닉네임·`memberId`·`messageId` 등 식별값은 응답에 포함하지 않는다. OWNER 자유 서술이 아니라
  구조화 필드 기반 서버 템플릿 문구(`summary`)만 제공한다.
- 집계 조건: 최근 7일(`analyzedAt >= now - 7d`) + `bobfull.restaurant-feedback.active-prompt-version`
  설정값과 일치하는 결과만 + `category+aspectType+normalizedAspect+opinionType+sentiment` 5-field
  동일 그룹에서 **distinct `senderMemberId` >= 3**인 그룹만 노출한다.
- 동일 발신자가 같은 그룹에 여러 번 기여해도 `count`(=distinct sender 수)에는 최대 1회만 반영된다.
- Kafka Consumer(`bobfull.kafka.restaurant-insight.consumer-enabled`)가 비활성인 환경(Production 기본값)에서는
  신규 결과가 쌓이지 않으므로 이 조회는 항상 빈 목록을 반환한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

요청 Body와 Query Parameter는 사용하지 않는다(기간·집계 조건은 서버 고정값).

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "restaurantId": 1,
    "from": "2026-08-09T00:00:00Z",
    "to": "2026-08-16T00:00:00Z",
    "insights": [
      {
        "category": "FOOD",
        "aspectType": "MENU",
        "normalizedAspect": "탕수육",
        "opinionType": "TEXTURE",
        "sentiment": "POSITIVE",
        "count": 8,
        "summary": "탕수육 식감에 대한 긍정 의견 8명"
      }
    ]
  }
}
```

### Response Fields

| 필드 | 타입 | 설명 |
|---|---|---|
| `restaurantId` | Long | 조회 대상 restaurantId |
| `from` / `to` | Instant | 집계 기간(항상 최근 7일, 서버 `Clock` 기준 UTC) |
| `insights[].category` | String | `FOOD` / `SERVICE` / `PRICE` / `CLEANLINESS` / `ETC` |
| `insights[].aspectType` | String | `MENU` / `SERVICE` / `PRICE` / `CLEANLINESS` / `ETC` |
| `insights[].normalizedAspect` | String | 정규화된 짧은 대상 텍스트(최대 40 Unicode code point, PII/재식별 단서 없음) |
| `insights[].opinionType` | String | `TASTE`/`TEXTURE`/`SALTINESS`/`SPICINESS`/`SWEETNESS`/`PORTION`/`FRESHNESS`/`TEMPERATURE`/`FRIENDLINESS`/`SERVICE_SPEED`/`PRICE_LEVEL`/`CLEANLINESS`/`WAITING`/`ETC` |
| `insights[].sentiment` | String | `POSITIVE` / `NEGATIVE` / `NEUTRAL` |
| `insights[].count` | Long | distinct `senderMemberId` 수(message 수 아님), 항상 3 이상 |
| `insights[].summary` | String | 구조화 필드 기반 서버 템플릿 문구(자유 서술 없음) |

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---

## PATCH /api/owner/restaurants/{restaurantId} — 식당 정보 수정

**권한** `OWNER`


### 개요

- 설명: 타인 식당 수정 시 403

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `restaurantId` | Long | Y | restaurantId 식별자 |

### Body

```json
{
  "name": "밥풀 한식당",
  "description": "수정된 식당 소개",
  "keyword": "한식,혼밥",
  "depositPerPerson": 12000,
  "imageKey": "restaurants/1/22222222-2222-2222-2222-222222222222.png"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `name` | String | Y | name 값 |
| `description` | String | Y | description 값 |
| `keyword` | String | Y | 사장님이 직접 입력하는 식당 키워드 |
| `depositPerPerson` | Integer | Y | depositPerPerson 값 |
| `imageKey` | String | N | 새 이미지로 교체할 `finalImageKey`. 필드를 보내지 않으면 기존 이미지 유지 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "restaurantId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `400` | `INVALID_RESTAURANT_IMAGE_KEY` | 식당 이미지 Key 형식 또는 소유자 경로가 올바르지 않음 |
| `400` | `RESTAURANT_IMAGE_NOT_FOUND` | Lambda 검증 완료 후 최종 경로로 이동된 이미지를 찾을 수 없음 |
| `400` | `RESTAURANT_IMAGE_ALREADY_USED` | 해당 imageKey가 다른 활성 식당에 이미 연결되어 있음 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## GET /api/restaurants — 사용자용 식당 목록·검색

**권한** `PUBLIC`


### 개요

- 설명: 운영 중인 식당을 목록 조회하며 검색어·카테고리·날짜·시간 조건으로 예약 가능한 식당을 동적 검색한다. 제주는 초기 운영 타깃이며 제주 외 지역을 시스템적으로 차단하지 않는다. V1은 별도 지역 검색·필터를 제공하지 않는다.

### Request

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `keyword` | String | N | 식당명·메뉴 검색어 |
| `category` | String | N | 음식 카테고리 필터 |
| `date` | LocalDate | N | 예약 희망 날짜 |
| `time` | LocalTime | N | 예약 희망 시간 |
| `page` | Integer | N | 페이지 번호 |
| `size` | Integer | N | 페이지 크기 |
| `sort` | String | N | 정렬 기준 |

- 검색 조건이 없으면 운영 중인 식당 전체 목록을 조회한다.
- 날짜·시간 조건이 있으면 해당 조건에 예약 가능한 합석 회차가 존재하는 식당만 조회한다.
- 요청 Body는 사용하지 않는다.
- `date`/`time`이 없는 검색(기본/`keyword`/`category`/`sort`/pagination 조합)은 Redis에 최대 60초(TTL) 캐시될 수 있다(Issue #62). 식당 정보 변경(등록·수정·삭제) 시 캐시를 즉시 무효화하지만, 무효화 시점과 재조회 시점이 겹치면 최대 TTL만큼 이전 값이 보일 수 있다. `date`/`time`이 있는 검색은 캐시하지 않는다. `imageUrl`은 캐시 여부와 무관하게 항상 요청 시점에 새로 발급한다.

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
        "name": "밥풀식당",
        "address": "제주시 애월읍 1",
        "category": "한식",
        "keyword": "흑돼지,혼밥",
        "depositPerPerson": 10000,
        "imageUrl": "https://s3-presigned-get-url.example"
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

---


## GET /api/restaurants/{restaurantId} — 사용자용 식당 상세 조회

**권한** `PUBLIC`


### 개요

- 설명: 예약금·카테고리 포함

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
    "name": "밥풀식당",
    "address": "제주시 애월읍 1",
    "category": "한식",
    "description": "합석 예약이 가능한 식당입니다.",
    "keyword": "흑돼지,혼밥",
    "depositPerPerson": 10000,
    "imageUrl": "https://s3-presigned-get-url.example"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

---


## DELETE /api/owner/restaurants/{restaurantId} — 식당 삭제

**권한** `OWNER`


### 개요

- 설명: 소프트 딜리트 처리

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
    "restaurantId": 1
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 접근 권한이 없거나 본인 리소스가 아님 |
| `404` | `RESTAURANT_ID_NOT_FOUND` | restaurantId에 해당하는 대상을 찾을 수 없음 |

> 현재 `RestaurantService.delete()`는 연결 테이블·회차·예약 존재 여부를 검사하지 않는다. `RESTAURANT_DELETE_NOT_ALLOWED`는 ErrorCode에는 존재하지만 현재 삭제 실행 경로에서는 발생하지 않는 TODO 계약이므로 활성 Error 표에서 제외한다.

---


## POST /api/owner/restaurants/images/upload-url — 식당 이미지 업로드 URL 발급

**권한** `OWNER`


### 개요

- 설명: OWNER가 식당 등록·수정 전에 S3 Presigned PUT URL을 발급받는다.

### Request

### Body

```json
{
  "extension": "png",
  "contentType": "image/png",
  "fileSize": 1048576
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `extension` | String | Y | `jpg`, `jpeg`, `png`만 허용. `webp`는 허용하지 않음 |
| `contentType` | String | Y | `image/jpeg` 또는 `image/png` |
| `fileSize` | Long | Y | 업로드 예정 파일 크기. 최대 5MB |

### Response

- Status: `200 OK`
- `uploadUrl` 만료 시간은 5분이다.
- `tempImageKey`는 클라이언트가 직접 저장하지 않는 임시 업로드 경로다.
- `finalImageKey`는 Lambda 검증 후 최종 경로에 객체가 존재할 때 식당 등록·수정 Request의 `imageKey`로 사용한다.

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "uploadUrl": "https://s3-presigned-put-url.example",
    "tempImageKey": "temp/restaurants/1/11111111-1111-1111-1111-111111111111.png",
    "finalImageKey": "restaurants/1/11111111-1111-1111-1111-111111111111.png"
  }
}
```

### 처리 정책

- 클라이언트는 `uploadUrl`로 S3 `PUT` 업로드를 수행한다.
- S3 ObjectCreated 이벤트가 Java Lambda를 실행하고, Lambda는 임시 객체의 경로·확장자·Content-Type·파일 크기·파일 시그니처를 검증한다.
- 검증 성공 시 Lambda는 `temp/restaurants/{ownerId}/{uuid}.{extension}` 객체를 `restaurants/{ownerId}/{uuid}.{extension}`로 복사한 뒤 임시 객체를 삭제한다.
- 검증 실패 시 Lambda는 임시 객체를 삭제한다.
- 별도 상태 조회 API는 제공하지 않는다. 식당 등록·수정 API가 `finalImageKey`의 최종 객체 존재 여부를 확인한다.
- 식당 조회 응답의 `imageUrl`은 DB 저장값이 아니라 5분 만료 Presigned GET URL이다. DB에는 `imageKey`만 저장한다.
- 이미지 교체 시 새 `imageKey` 등록이 성공한 뒤 기존 S3 객체를 삭제한다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `400` | `INVALID_IMAGE_EXTENSION` | 허용하지 않는 이미지 확장자 |
| `400` | `UNSUPPORTED_IMAGE_CONTENT_TYPE` | 허용하지 않는 이미지 Content-Type |
| `400` | `IMAGE_EXTENSION_CONTENT_TYPE_MISMATCH` | 확장자와 Content-Type 불일치 |
| `400` | `IMAGE_FILE_SIZE_EXCEEDED` | 파일 크기가 5MB를 초과하거나 0 이하 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | OWNER 권한 없음 |
| `500` | `IMAGE_STORAGE_NOT_CONFIGURED` | 이미지 저장소 설정 누락 |
| `500` | `IMAGE_STORAGE_REQUEST_FAILED` | 이미지 저장소 요청 실패 |

---
