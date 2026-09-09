# 운영 Endpoint / Webhook

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/actuator/prometheus` | `PUBLIC` | API 모니터링 | 200 |
| `GET` | `/actuator/health` | `PUBLIC` | 애플리케이션 상태 확인 | 200 |
| `POST` | `/api/webhooks/portone` | `PUBLIC` | PortOne 결제 웹훅 | 200 / 400 |

## 최신 계약 메모

- Actuator 2개와 PortOne Webhook은 `ApiResponse<T>`를 사용하지 않는다.
- Webhook header 3개는 Controller 파라미터상 optional이지만 Controller가 직접 누락을 검사해 400을 반환한다.
- SecurityConfig상 PUBLIC이며 실제 운영 노출 범위는 인프라 정책으로 별도 제한할 수 있다.

---

# 상세 명세

## GET /actuator/prometheus — API 모니터링

**권한** `PUBLIC`


### 개요

- 설명: Prometheus 연동. Spring Boot Actuator 표준 엔드포인트이며 **공통 응답(0.3~0.4) 포맷을 적용하지 않는다.**

### Request

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`
- Content-Type: `text/plain`(Prometheus text exposition format, JSON이 아니다)

```text
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.2345678E7
```

### Error

- 별도 도메인 오류 없음

---


## GET /actuator/health — 애플리케이션 상태 확인

**권한** `PUBLIC`


### 개요

- 설명: 배포 헬스 체크. Spring Boot Actuator 표준 엔드포인트이며 **공통 응답(0.3~0.4) 포맷을 적용하지 않는다.**

### Request

요청 Body는 사용하지 않는다.

### Response

- Status: `200 OK`

```json
{
  "status": "UP"
}
```

### Error

- 도메인 에러 코드를 사용하지 않는다. 하나 이상의 컴포넌트가 비정상이면 `503 Service Unavailable`과 함께 Actuator 표준 형식(`{"status": "DOWN", ...}`)을 그대로 반환한다.

---


## POST /api/webhooks/portone — PortOne 결제 웹훅

**권한** `PUBLIC`


### 개요

- 설명: PortOne 웹훅 서명을 검증한 뒤 `paymentId`로 결제 정보를 재조회한다. 완료 검증 API와 동시에 호출되어도 한 번만 반영한다.

### Request

- `webhook-id`, `webhook-signature`, `webhook-timestamp` 헤더는 필수다.
- PortOne API·Store·Webhook 시크릿과 `payment.expiration` 실행값은 공통 `application.yml`이 아닌 `application-local.yml` 또는 배포 환경변수로 주입한다. Webhook 시크릿에 기본값을 두지 않으며, 웹훅이 활성화된 환경에서 필수 값이 없으면 애플리케이션 시작이 실패한다.
- Controller는 JSON DTO 역직렬화 전에 원본 Body(`String` 또는 `byte[]`)와 위 헤더를 PortOne 공식 SDK 검증기에 전달한다. 검증 성공 후에만 이벤트를 해석한다.
- SecurityConfig는 이 POST 경로를 `permitAll`로 두고 JWT 필터도 이 경로를 인증 실패(`401`)로 차단하지 않는다. 접근 허용은 서명 검증을 대체하지 않는다.
- 결제 완료 이벤트는 `paymentId`를 기준으로 결제 완료 검증 경로에 전달한다.
- `CANCEL_PENDING` 이벤트는 `paymentId`와 `cancellationId`를 기준으로 환불을 처리 중 상태로 반영한다.
- `CANCELLED` 이벤트는 동일 식별자를 기준으로 환불 완료를 반영한다.
- `PARTIAL_CANCELLED`와 지원하지 않는 이벤트는 현재 처리 범위 밖이므로 `200 OK`로 확인 후 무시한다.

### Response

- 서명 헤더 누락·서명 검증 실패는 `400 Bad Request`다.
- `PARTIAL_CANCELLED`와 지원하지 않는 이벤트는 `200 OK`로 확인 처리한다.
- 결제 완료 이벤트의 알려진 영구 업무 실패(`PAYMENT_NOT_FOUND`, `PAYMENT_VERIFICATION_FAILED`, `PAYMENT_EXPIRED`)는 재시도해도 성공할 수 없는 것으로 보고 `200 OK`로 확인 처리한다.
- `CANCEL_PENDING`과 `CANCELLED`는 환불 상태 반영 서비스에서 처리한다.
- 지원하지 않는 이벤트와 알려진 영구 업무 실패의 확인 처리는 `200 OK`와 빈 본문으로 반환한다.
- 서명 헤더 누락·서명 검증 실패는 `400 Bad Request`와 빈 본문으로 반환한다.
- PortOne 네트워크 오류, DB 장애, 분류되지 않은 업무 오류, 예상하지 못한 서버 오류는 공통 예외 응답 구조의 `5xx`로 반환한다. 알려진 영구 업무 실패만 웹훅 입구에서 `200`으로 변환하며 예외를 무조건 삼키지 않는다.

### Error

| Status | Code | 설명 |
|---:|---|---|
| `400` | - | 필수 서명 헤더 누락 또는 서명 검증 실패. `ResponseEntity.badRequest().build()`로 빈 본문 반환 |
| `200` | - | 결제 완료, `CANCEL_PENDING`, `CANCELLED` 정상 처리 또는 `PARTIAL_CANCELLED`·미지원 이벤트 확인 처리 |
| `200` | - | 결제 완료 이벤트의 `PAYMENT_NOT_FOUND`·`PAYMENT_VERIFICATION_FAILED`·`PAYMENT_EXPIRED` 영구 업무 실패 확인 처리 |
| `5xx` | `INTERNAL_SERVER_ERROR` | PortOne 네트워크·DB·예상하지 못한 서버 오류 |

---
