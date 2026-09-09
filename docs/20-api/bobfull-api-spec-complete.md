# BobFull 전체 API 통합 요약

> 이 문서는 BobFull HTTP API의 **통합 요약 및 상세 명세 진입점**이다.
> Request/Response DTO, Validation, 응답 예시, Endpoint별 Error 계약은 [API 상세 명세 목차](README.md) 및 각 도메인 상세 문서를 **최종 기준**으로 관리한다.

현재 문서화 대상은 Application HTTP API **71개**와 Actuator Endpoint **2개**, 총 **73개**다. 실제 코드의 `Controller / DTO / Validation / SecurityConfig / ErrorCode`를 최종 기준으로 하며, 이 문서는 상세 계약을 중복하지 않는다.

## #245 정적 계약 검증 기준선

- 검증 기준 `develop` SHA: `8e17ffc1b61626ff7f4b6fd2186eaf2341f0bbd2` (2026-08-13)
- 대상: Application HTTP API 70개(PortOne Webhook 포함), Actuator Endpoint 2개
- 대조: Controller의 Method + Path, Request DTO와 Validation, Response DTO의 중첩·nullable 구조, `SecurityConfig` 인가 경계, 성공 Status, 실제 실행 경로의 ErrorCode, Webhook·Actuator의 비-`ApiResponse` 계약
- 결과: 코드 전용·문서 전용 HTTP endpoint와 API Spec ↔ ERD 간 명백한 정적 계약 모순을 확인하지 못했다. BLOCKER / MAJOR / MINOR는 0건이다.

이 기준선은 현재 구현의 정적 계약만 대상으로 하며, 성능·신뢰성·AWS·Kafka·K6 검증 자료와 최종 주장 검토는 #67에서 별도로 관리한다.

## 상세 명세로 이동

| 도메인 | API 수 | 상세 명세 |
|---|---:|---|
| 인증 | 5 | [auth-api.md](auth-api.md) |
| 회원 | 2 | [member-api.md](member-api.md) |
| 식당 | 9 | [restaurant-api.md](restaurant-api.md) |
| 합석 테이블 / 회차 | 12 | [table-session-api.md](table-session-api.md) |
| 예약 | 10 | [reservation-api.md](reservation-api.md) |
| 결제 / 환불 / 정산 | 8 | [payment-api.md](payment-api.md) |
| 노쇼 | 5 | [no-show-api.md](no-show-api.md) |
| 채팅 / 신고 | 3 | [chat-api.md](chat-api.md) |
| 관리자 / Moderation | 16 | [admin-api.md](admin-api.md) |
| 운영 Endpoint / Webhook | 1 | [operations-api.md](operations-api.md) |
| **합계** | **73** | [전체 상세 명세 목차](README.md) |

## 권한 표기

| 표기 | 의미 |
|---|---|
| `PUBLIC` | 인증 없이 허용 |
| `AUTHENTICATED` | 유효한 JWT Access Token 필요 |
| `OWNER` | `ROLE_OWNER` 필요 |
| `ADMIN` | `ROLE_ADMIN` 필요 |

## API 목록

### 인증 — [상세 명세](auth-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `POST` | `/api/auth/signup/users` | `PUBLIC` | 일반 사용자 회원가입 |
| `POST` | `/api/auth/signup/owners` | `PUBLIC` | 사장님 회원가입 |
| `POST` | `/api/auth/login` | `PUBLIC` | 로그인 |
| `POST` | `/api/auth/logout` | `AUTHENTICATED` | 로그아웃 |
| `POST` | `/api/auth/reissue` | `PUBLIC` | 토큰 재발급 |

### 회원 — [상세 명세](member-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/api/members/me` | `AUTHENTICATED` | 내 정보 조회 |
| `PATCH` | `/api/members/me` | `AUTHENTICATED` | 내 정보 수정 |

### 식당 — [상세 명세](restaurant-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `POST` | `/api/owner/restaurants` | `OWNER` | 식당 등록 |
| `GET` | `/api/owner/restaurants` | `OWNER` | 내 식당 목록 조회 |
| `GET` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 내 식당 상세 조회 |
| `GET` | `/api/owner/restaurants/{restaurantId}/feedback-insights` | `OWNER` | 최근 7일 익명 피드백 집계 조회 |
| `PATCH` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 식당 정보 수정 |
| `GET` | `/api/restaurants` | `PUBLIC` | 사용자용 식당 목록·검색 |
| `GET` | `/api/restaurants/{restaurantId}` | `PUBLIC` | 사용자용 식당 상세 조회 |
| `DELETE` | `/api/owner/restaurants/{restaurantId}` | `OWNER` | 식당 삭제 |
| `POST` | `/api/owner/restaurants/images/upload-url` | `OWNER` | 식당 이미지 업로드 URL 발급 |

### 합석 테이블 / 회차 — [상세 명세](table-session-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `POST` | `/api/owner/restaurants/{restaurantId}/tables` | `OWNER` | 합석 테이블 등록 |
| `POST` | `/api/owner/restaurants/{restaurantId}/tables/bulk` | `OWNER` | 합석 테이블 일괄 등록 |
| `GET` | `/api/owner/restaurants/{restaurantId}/tables` | `OWNER` | 합석 테이블 목록 조회 |
| `GET` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 상세 조회 |
| `PATCH` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 수정 |
| `DELETE` | `/api/owner/tables/{tableId}` | `OWNER` | 합석 테이블 삭제 |
| `POST` | `/api/owner/tables/{tableId}/dining-sessions` | `OWNER` | 합석 회차 등록 |
| `POST` | `/api/owner/tables/{tableId}/dining-sessions/bulk` | `OWNER` | 기존 테이블 합석 회차 일괄 등록 |
| `GET` | `/api/owner/restaurants/{restaurantId}/dining-sessions` | `OWNER` | 사장님용 회차 목록 조회 |
| `GET` | `/api/restaurants/{restaurantId}/dining-sessions` | `PUBLIC` | 사용자용 예약 가능 회차 조회 |
| `PATCH` | `/api/owner/dining-sessions/{sessionId}` | `OWNER` | 합석 회차 수정 |
| `DELETE` | `/api/owner/dining-sessions/{sessionId}` | `OWNER` | 합석 회차 삭제 |

### 예약 — [상세 명세](reservation-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/api/reservations/availability` | `AUTHENTICATED` | 예약 가능 여부 확인 |
| `POST` | `/api/reservations/prepare` | `AUTHENTICATED` | 예약 결제 준비 |
| `GET` | `/api/reservations/search` | `PUBLIC` | 참여 가능한 예약 검색 |
| `GET` | `/api/members/me/reservations` | `AUTHENTICATED` | 내 예약 목록 조회 |
| `GET` | `/api/members/me/reservations/{reservationId}` | `AUTHENTICATED` | 내 예약 상세 조회 |
| `POST` | `/api/reservations/{reservationId}/participations/me/cancel` | `AUTHENTICATED` | 내 예약 참여 취소 |
| `GET` | `/api/owner/restaurants/{restaurantId}/reservations` | `OWNER` | 식당별 예약 목록 조회 |
| `GET` | `/api/owner/reservations/{reservationId}` | `OWNER` | 사장님용 예약 상세 조회 |
| `GET` | `/api/owner/reservations/{reservationId}/participations` | `OWNER` | 사장님용 예약 참여자 목록 조회 |
| `POST` | `/api/owner/reservations/{reservationId}/cancel` | `OWNER` | 식당 귀책 예약 취소 |

### 결제 / 환불 / 정산 — [상세 명세](payment-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `POST` | `/api/payments/{paymentId}/complete` | `AUTHENTICATED` | 결제 완료 검증 |
| `GET` | `/api/members/me/payments` | `AUTHENTICATED` | 내 결제 목록 조회 |
| `GET` | `/api/payments/{paymentId}` | `AUTHENTICATED` | 결제 상세 조회 |
| `GET` | `/api/members/me/refunds` | `AUTHENTICATED` | 내 환불 목록 조회 |
| `GET` | `/api/refunds/{refundId}` | `AUTHENTICATED` | 환불 상세 조회 |
| `GET` | `/api/owner/restaurants/{restaurantId}/settlements/expected` | `OWNER` | 지급 예정 금액 조회 |
| `GET` | `/api/owner/restaurants/{restaurantId}/settlements/reservations` | `OWNER` | 예약별 지급 예정 내역 조회 |
| `GET` | `/api/owner/settlements/reservations/{reservationId}` | `OWNER` | 예약별 지급 예정 상세 조회 |

### 노쇼 — [상세 명세](no-show-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/api/owner/reservations/{reservationId}/participations/no-show-candidates` | `OWNER` | 노쇼 처리 대상 참여자 조회 |
| `POST` | `/api/owner/reservations/{reservationId}/participations/{participationId}/no-show` | `OWNER` | 참여자 노쇼 처리 |
| `DELETE` | `/api/owner/reservations/{reservationId}/participations/{participationId}/no-show` | `OWNER` | 노쇼 처리 해제 |
| `GET` | `/api/owner/reservations/{reservationId}/no-show-histories` | `OWNER` | 예약별 노쇼 이력 조회 |
| `GET` | `/api/owner/restaurants/{restaurantId}/no-shows` | `OWNER` | 식당 노쇼 고객 조회 |

### 채팅 / 신고 — [상세 명세](chat-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/api/reservations/{reservationId}/chat-room` | `AUTHENTICATED` | 예약 채팅방 조회 |
| `GET` | `/api/chat/rooms/{chatRoomId}/messages` | `AUTHENTICATED` | 채팅 메시지 목록 조회 |
| `POST` | `/api/chat-rooms/{chatRoomId}/members/{reportedMemberId}/reports` | `AUTHENTICATED` | 채팅방 참여자 신고 |

### 관리자 / Moderation — [상세 명세](admin-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/api/admin/members` | `ADMIN` | 회원 목록 조회 |
| `GET` | `/api/admin/members/{memberId}` | `ADMIN` | 회원 상세 조회 |
| `GET` | `/api/admin/restaurants` | `ADMIN` | 식당 목록 조회 |
| `GET` | `/api/admin/restaurants/{restaurantId}` | `ADMIN` | 식당 상세 조회 |
| `GET` | `/api/admin/reservations` | `ADMIN` | 전체 예약 현황 조회 |
| `GET` | `/api/admin/payments` | `ADMIN` | 전체 결제 현황 조회 |
| `GET` | `/api/admin/refunds` | `ADMIN` | 전체 환불 현황 조회 |
| `GET` | `/api/admin/no-shows` | `ADMIN` | 전체 노쇼 현황 조회 |
| `GET` | `/api/admin/statistics/overview` | `ADMIN` | 전체 운영 지표 조회 |
| `GET` | `/api/admin/statistics/restaurants` | `ADMIN` | 식당별 예약 성사율 조회 |
| `GET` | `/api/admin/statistics/members/no-show-rates` | `ADMIN` | 사용자별 노쇼율 조회 |
| `GET` | `/api/admin/moderation/members` | `ADMIN` | 채팅 moderation 회원별 집계 조회 |
| `GET` | `/api/admin/moderation/members/{memberId}` | `ADMIN` | 채팅 moderation 회원별 상세 조회 |
| `GET` | `/api/admin/moderation/reports` | `ADMIN` | 관리자 신고 목록 조회 |
| `GET` | `/api/admin/moderation/reports/{reportId}` | `ADMIN` | 관리자 신고 상세 조회 |
| `PATCH` | `/api/admin/moderation/reports/{reportId}/review` | `ADMIN` | 관리자 신고 검토 |

### 운영 Endpoint / Webhook — [상세 명세](operations-api.md)

| Method | Path | 권한 | 기능 |
|---|---|---|---|
| `GET` | `/actuator/prometheus` | `PUBLIC` | API 모니터링 |
| `GET` | `/actuator/health` | `PUBLIC` | 애플리케이션 상태 확인 |
| `POST` | `/api/webhooks/portone` | `PUBLIC` | PortOne 결제 웹훅 |

## 유지보수 기준

- 상세 Request/Response/Error 계약은 통합본에 복제하지 않고 각 도메인 상세 명세에서만 변경한다.
- Endpoint의 Method, Path, 권한 또는 기능이 바뀌면 통합본과 해당 상세 문서를 함께 갱신한다.
- WebSocket/STOMP 메시지 계약 및 내부 Kafka/Outbox/Redis 흐름은 HTTP API 명세 범위 밖이며 Architecture/ADR/Evidence에서 관리한다.
