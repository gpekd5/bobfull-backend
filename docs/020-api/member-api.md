# 회원 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/api/members/me` | `AUTHENTICATED` | 내 정보 조회 | 200 |
| `PATCH` | `/api/members/me` | `AUTHENTICATED` | 내 정보 수정 | 200 |

## 최신 계약 메모

- `MemberResponse.businessNumber`는 MEMBER에서는 null이며 `NON_NULL` 직렬화 정책으로 응답에서 생략된다.
- 현재 회원 HTTP API는 내 정보 조회/수정 2개다.

---

# 상세 명세

## GET /api/members/me — 내 정보 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 로그인 사용자 기준

### Request

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
    "role": "MEMBER"
  }
}
```

- OWNER 본인이 조회하는 경우 `businessNumber`를 추가로 반환한다. 일반 MEMBER 응답에는 `businessNumber`를 포함하지 않는다.

OWNER 응답 예시:

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "memberId": 2,
    "email": "owner@example.com",
    "name": "김사장",
    "phoneNumber": "01012345678",
    "role": "OWNER",
    "businessNumber": "1234567890"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `MEMBER_NOT_FOUND` | 인증 정보는 유효하지만 DB에서 회원을 찾을 수 없음 |

---


## PATCH /api/members/me — 내 정보 수정

**권한** `AUTHENTICATED`


### 개요

- 설명: 전화번호 중복 검증

### Request

### Body

```json
{
  "name": "새이름",
  "phoneNumber": "01098765432"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `name` | String | Y | name 값 |
| `phoneNumber` | String | Y | phoneNumber 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "result": true
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `404` | `MEMBER_NOT_FOUND` | 인증 정보는 유효하지만 DB에서 회원을 찾을 수 없음 |
| `409` | `DUPLICATE_PHONE_NUMBER` | 이미 사용 중인 phoneNumber. 본인 기존 phoneNumber는 중복으로 보지 않음 |

---
