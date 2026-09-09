# 인증 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `POST` | `/api/auth/signup/users` | `PUBLIC` | 일반 사용자 회원가입 | 201 |
| `POST` | `/api/auth/signup/owners` | `PUBLIC` | 사장님 회원가입 | 201 |
| `POST` | `/api/auth/login` | `PUBLIC` | 로그인 | 200 |
| `POST` | `/api/auth/logout` | `AUTHENTICATED` | 로그아웃 | 200 |
| `POST` | `/api/auth/reissue` | `PUBLIC` | 토큰 재발급 | 200 |

## 최신 계약 메모

- `POST /api/auth/logout`만 `/api/auth/**` 중 인증이 필요하다.
- `LoginRequest.email`은 현재 `@NotBlank`만 적용되며 회원가입 email에는 `@Email`이 적용된다.
- 로그아웃은 Request Body를 사용하지 않고 Bearer Access Token을 사용한다.

---

# 상세 명세

## POST /api/auth/signup/users — 일반 사용자 회원가입

**권한** `PUBLIC`


### 개요

- 설명: 이메일·전화번호 중복 검증, 이름 중복 허용

### Request

### Body

```json
{
  "email": "user@example.com",
  "password": "Password123!",
  "name": "홍길동",
  "phoneNumber": "01012345678"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `email` | String | Y | email 값 |
| `password` | String | Y | password 값 |
| `name` | String | Y | name 값 |
| `phoneNumber` | String | Y | phoneNumber 값 |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "memberId": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "role": "MEMBER"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `409` | `DUPLICATE_EMAIL` | 이미 사용 중인 email |
| `409` | `DUPLICATE_PHONE_NUMBER` | 이미 사용 중인 phoneNumber |

---


## POST /api/auth/signup/owners — 사장님 회원가입

**권한** `PUBLIC`


### 개요

- 설명: 이메일·전화번호·사업자등록번호 중복 검증 후 OWNER 권한 부여

### Request

### Body

```json
{
  "email": "owner@example.com",
  "password": "Password123!",
  "name": "김사장",
  "phoneNumber": "01012345678",
  "businessNumber": "1234567890"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `email` | String | Y | email 값 |
| `password` | String | Y | password 값 |
| `name` | String | Y | name 값 |
| `phoneNumber` | String | Y | phoneNumber 값 |
| `businessNumber` | String | Y | businessNumber 값 |

### Response

- Status: `201 Created`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "memberId": 1,
    "email": "owner@example.com",
    "name": "김사장",
    "role": "OWNER"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `409` | `DUPLICATE_EMAIL` | 이미 사용 중인 email |
| `409` | `DUPLICATE_PHONE_NUMBER` | 이미 사용 중인 phoneNumber |
| `409` | `DUPLICATE_BUSINESS_NUMBER` | 이미 사용 중인 businessNumber |

---


## POST /api/auth/login — 로그인

**권한** `PUBLIC`


### 개요

- 설명: Access Token과 Refresh Token을 함께 발급한다. Refresh Token은 Redis에만 저장하며(회원당 1건), 재발급마다 회전한다.

### Request

### Body

```json
{
  "email": "user@example.com",
  "password": "Password123!"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `email` | String | Y | email 값 |
| `password` | String | Y | password 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "accessToken": "access-token",
    "tokenType": "Bearer",
    "refreshToken": "refresh-token"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `INVALID_CREDENTIALS` | 이메일이 존재하지 않거나 비밀번호가 일치하지 않음 |

---


## POST /api/auth/logout — 로그아웃

**권한** `AUTHENTICATED`


### 개요

- 설명: 인증된 회원의 Refresh Token을 Redis Whitelist에서 즉시 삭제하고, 현재 요청에 사용된 Access Token의 `jti`를 남은 유효시간만큼 Redis Blacklist에 등록해 즉시 무효화한다(Issue #186). 이후 이 Access Token으로는 보호 API에 접근할 수 없다.

### Request

요청 Body는 사용하지 않는다.

`Authorization: Bearer {accessToken}` 헤더가 필요하다.

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
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |

---


## POST /api/auth/reissue — 토큰 재발급

**권한** `PUBLIC`


### 개요

- 설명: Refresh Token을 검증하고 회전(rotation)한다. 기존 Refresh Token은 즉시 삭제되고 새 Access·Refresh Token을 발급한다. Redis 조회 실패를 포함해 유효하지 않은 모든 경우는 새 토큰을 내주지 않고 401로 거부한다.

### Request

### Body

```json
{
  "refreshToken": "refresh-token"
}
```

### Request Fields

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `refreshToken` | String | Y | refreshToken 값 |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "accessToken": "new-access-token",
    "refreshToken": "new-refresh-token"
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청값 검증 실패 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
