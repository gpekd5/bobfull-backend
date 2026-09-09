# BobFull API 명세

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

현재 BobFull의 Application HTTP API는 **71개**, 운영 Actuator Endpoint는 **2개**다.

이 폴더는 브로셔와 GitHub에서 사람이 읽기 편하도록 도메인별 상세 명세를 분리한다.
각 도메인 문서는 **Endpoint 요약 → API별 권한 → Request/Query/Path → Response JSON → Error → 주요 계약** 순서로 구성한다.

## 도메인별 API

| 도메인 | API 수 | 상세 문서 |
|---|---:|---|
| 인증 (Auth) | 5 | [auth-api.md](auth-api.md) |
| 회원 (Member) | 2 | [member-api.md](member-api.md) |
| 식당 (Restaurant) | 9 | [restaurant-api.md](restaurant-api.md) |
| 합석 테이블 / 회차 | 12 | [table-session-api.md](table-session-api.md) |
| 예약 (Reservation) | 10 | [reservation-api.md](reservation-api.md) |
| 결제 / 환불 / 정산 | 8 | [payment-api.md](payment-api.md) |
| 노쇼 (No-show) | 5 | [no-show-api.md](no-show-api.md) |
| 채팅 / 신고 | 3 | [chat-api.md](chat-api.md) |
| 관리자 / Moderation | 16 | [admin-api.md](admin-api.md) |
| 운영 Endpoint / Webhook | 1 | [operations-api.md](operations-api.md) |
| **합계** | **73** | Application 71 + Actuator 2 |

## 공통 응답

일반 REST 성공 응답:

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

실패 응답:

```json
{
  "success": false,
  "message": "에러 메시지",
  "code": "ERROR_CODE"
}
```

페이징 `data`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

## 권한 표기

| 표기 | 의미 |
|---|---|
| `PUBLIC` | 인증 없이 허용 |
| `AUTHENTICATED` | 유효한 JWT Access Token 필요 |
| `OWNER` | `ROLE_OWNER` 필요 |
| `ADMIN` | `ROLE_ADMIN` 필요 |

## Error 문서화 원칙

- ErrorCode enum에 정의돼 있다는 이유만으로 Endpoint Error 표에 넣지 않는다.
- **실제 Controller/Service 실행 경로에서 도달 가능한 Error만** Endpoint별 Error에 기재한다.
- `ResponseEntity<Void>`처럼 에러코드 본문을 반환하지 않는 Endpoint는 코드명을 임의로 붙이지 않는다.

## 정합성 유지 규칙

1. 실제 코드가 최종 기준이다.
2. Controller `Method + Path` 변경 시 해당 도메인 문서도 같은 PR에서 수정한다.
3. DTO/Validation 변경 시 해당 API의 Request/Response 예시와 필드 계약도 수정한다.
4. Security/ErrorCode 변경 시 권한·Error 섹션도 함께 수정한다.
5. 최종 QA에서 전체 Controller와 문서를 `Method + Path` 기준으로 다시 비교한다.

> WebSocket/STOMP 메시지 계약과 내부 Kafka/Outbox/Redis 흐름은 HTTP API 명세가 아니라 Architecture/ADR/Evidence에서 관리한다.
