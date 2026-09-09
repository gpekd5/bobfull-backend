# 채팅 / 신고 API

> 최종 기준: 실제 `Controller / DTO / Validation / SecurityConfig / ErrorCode`

이 문서는 현재 `develop`의 실제 HTTP 계약을 사람이 읽기 쉬운 상세 명세 형태로 풀어쓴 문서다.
수정 전 전체 API 명세의 상세 설명·JSON 예시를 참고하되, 최신 코드에 존재하지 않는 API는 제외하고 현재 도메인 문서 계약을 우선 반영했다.

## Endpoint 한눈에 보기

| Method | Path | 권한 | 기능 | Status |
|---|---|---|---|---:|
| `GET` | `/api/reservations/{reservationId}/chat-room` | `AUTHENTICATED` | 예약 채팅방 조회 | 200 |
| `GET` | `/api/chat/rooms/{chatRoomId}/messages` | `AUTHENTICATED` | 채팅 메시지 목록 조회 | 200 |
| `POST` | `/api/chat-rooms/{chatRoomId}/members/{reportedMemberId}/reports` | `AUTHENTICATED` | 채팅방 참여자 신고 | 200 |

## 최신 계약 메모

- 채팅 메시지 조회 `size` 기본값은 50, 허용 범위는 1..100이다.
- 사용자 신고 생성은 현재 Controller 구현대로 `200 OK`다.
- WebSocket/STOMP, Redis Pub/Sub, Kafka AI 분석은 HTTP 계약과 분리해 Architecture/ADR/Evidence에서 관리한다.

---

# 상세 명세

## GET /api/reservations/{reservationId}/chat-room — 예약 채팅방 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 예약별 채팅방 1개를 조회한다. 최초 예약자와 결제 완료 참여자만 접근할 수 있다.

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
    "chatRoomId": 1,
    "reservationId": 101
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 해당 예약의 결제 완료 참여자가 아님 |
| `503` | `CHAT_ROOM_NOT_READY` | 채팅방이 아직 생성되지 않았고 조회 시 복구 생성도 실패함 |

---


## GET /api/chat/rooms/{chatRoomId}/messages — 채팅 메시지 목록 조회

**권한** `AUTHENTICATED`


### 개요

- 설명: 유효 참여자가 채팅방 단위로 저장된 과거 메시지를 커서 기반 조회한다.

### Request

### Path Variables

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `chatRoomId` | Long | Y | chatRoomId 식별자 |

### Query Parameters

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `cursor` | Long | N | 이전 메시지 기준 커서. 전달 시 `1` 이상이어야 함 |
| `size` | Integer | N | 조회 개수. 기본값 `50`, 허용 범위 `1..100` |

### Response

- Status: `200 OK`

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "messageId": 1001,
        "senderMemberId": 15,
        "senderName": "밥풀러",
        "content": "곧 도착합니다.",
        "sentAt": "2026-07-25T17:50:00+09:00"
      }
    ],
    "nextCursor": 1001
  }
}
```

### Error

| Status | Code | 설명 |
|---:|---|---|
| `401` | `UNAUTHORIZED` | 인증되지 않은 사용자 |
| `403` | `ACCESS_DENIED` | 해당 예약의 결제 완료 참여자가 아님 |
| `404` | `CHAT_ROOM_ID_NOT_FOUND` | chatRoomId에 해당하는 대상을 찾을 수 없음 |
| `400` | `INVALID_INPUT_VALUE` | `cursor`가 0 이하이거나, `size`가 `1..100` 범위를 벗어나거나, 요청 파라미터 형식이 올바르지 않음 |

---

- 최초 예약 결제 완료 시 예약당 채팅방 1개를 생성한다. 별도 채팅방 생성 API는 없다.
- 유효 참여자는 결제 완료 참여자 중 `CANCELLED`가 아닌 참여자다. 유효 참여자만 접근하며, `CANCELLED` 참여자는 즉시 접근이 종료된다. OWNER와 ADMIN은 참여하지 않는다.
- 예약이 `CANCELLED` 또는 `CLOSED`가 되면 신규 메시지 전송은 종료하지만 기존 메시지는 조회할 수 있다.
- `now >= TimeSlot.endAt`(노쇼 처리 허용과 동일한 경계, Issue #175)부터는 예약 상태가 아직 `CONFIRMED`여도 신규 메시지 전송을 즉시 차단한다. CLOSED 전이 스케줄러의 처리 지연과 무관하게 이 시간 비교가 우선 적용된다.
- 메시지는 DB에 저장한다. DB 커밋 뒤 Redis Pub/Sub이 각 인스턴스의 Simple Broker로 실시간 전달한다. Redis는 메시지를 저장하거나 다시 보내주지 않으므로, Redis 장애로 놓친 메시지는 이 HTTP cursor 조회로 다시 가져온다.
- WebSocket 연결 Endpoint는 `/ws`다.
- STOMP 전송 경로는 `/pub/chat/rooms/{chatRoomId}/messages`, 구독 경로는 `/sub/chat/rooms/{chatRoomId}`다.
- 읽음 처리, 이미지·파일, 메시지 수정·삭제, 사용자 차단, Redis Streams·Redis Pub/Sub 재전송 Outbox는 범위에서 제외한다. AI Moderation의 Kafka는 실시간 전파와 독립된 분석 경로다. 사용자 신고는 V3 Issue #218 범위에 포함하며, AI Moderation과 사용자 신고는 관리자 Human Review 참고 신호일 뿐 자동 제재 점수·자동 BAN/정지/퇴장에 사용하지 않는다.

---


## POST /api/chat-rooms/{chatRoomId}/members/{reportedMemberId}/reports — 채팅방 참여자 신고

**권한** `AUTHENTICATED`


### 개요

- Success: `200 OK`

### Request

| 구분 | 필드 | 타입 | 필수 | 검증·설명 |
|---|---|---|---:|---|
| Path | `chatRoomId` | Long | Y | 신고 대상 채팅방 ID |
| Path | `reportedMemberId` | Long | Y | 신고 대상 회원 ID |
| Body | `reason` | `ABUSE \| SPAM \| PERSONAL_INFORMATION \| OTHER` | Y | `@NotNull` |
| Body | `anchorMessageId` | Long | N | 지정 시 해당 메시지는 같은 방에 있고 신고 대상 회원이 작성해야 함 |
| Body | `detail` | String | N | 최대 500자. `reason=OTHER`이면 공백이 아닌 값 필수 |

### Response `data`

| 필드 | 타입 |
|---|---|
| `reportId`, `chatRoomId`, `reporterMemberId`, `reportedMemberId`, `anchorMessageId` | Long |
| `reason` | `ReportReason` |
| `detail` | String |
| `status` | `PENDING` |
| `createdAt` | Instant |

### Error

| Status | Code | 조건 |
|---:|---|---|
| `400` | `INVALID_INPUT_VALUE` | 요청 DTO 검증 실패, `OTHER`의 detail 누락, anchor가 다른 방·다른 작성자임 |
| `400` | `CHAT_ROOM_REPORT_SELF_FORBIDDEN` | 자기 자신 신고 |
| `401` | `UNAUTHORIZED` | 미인증 또는 유효하지 않은 JWT |
| `403` | `ACCESS_DENIED` | 신고자 또는 피신고자가 해당 채팅방 예약의 참여자가 아님 |
| `404` | `CHAT_ROOM_ID_NOT_FOUND` | 채팅방 없음 |
| `404` | `CHAT_MESSAGE_ID_NOT_FOUND` | anchor 메시지 없음 |
| `409` | `CHAT_ROOM_REPORT_DUPLICATE` | 같은 신고자가 같은 방의 같은 회원을 이미 신고함 |
