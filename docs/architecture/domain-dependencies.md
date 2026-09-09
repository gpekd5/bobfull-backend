# BobFull 도메인 의존성과 변경 영향

## 1. 목적

이 문서는 정책이나 상태 전이를 새로 정의하지 않는다.

예약·모집·참여·좌석·결제·취소·환불·노쇼·지급 예정 예약금이 서로 어떻게 연결되는지 확인하고, 한 영역의 변경이 다른 담당 영역과 문서·테스트에 누락되지 않도록 사용하는 변경 영향 기준이다.

정책이 이 문서와 충돌하면 [`bobfull-api-spec-complete.md`](../api/bobfull-api-spec-complete.md), [`project-context.md`](../product/project-context.md), [`erd.md`](../data/erd.md)의 순서로 확인한다. 이 세 문서가 충돌하면 임의로 선택하지 않고 Human 판단을 요청한다.

## 2. 도메인 연결 요약

```text
회원·인증
→ 식당·사장님 권한
→ 식당 이미지 업로드·검증
→ 합석 테이블·예약 회차
→ 최초 예약 생성 또는 추가 참여
→ 좌석 10분 임시 선점과 Payment READY
→ PortOne 예약금 결제·서버 검증
→ Payment PAID 후 참여 인원·예약 상태·모집 상태 반영
→ 취소·환불·회차 복구
→ 사장님의 참여자별 노쇼 처리·해제
→ 지급 예정 예약금 조회
→ 채팅 저장·Redis Pub/Sub 실시간 전파
→ Outbox 기반 후속 처리와 Kafka AI/Insight Consumer
```

| 도메인 | 선행 입력 | 주요 결과 | 직접 영향을 받는 도메인 | 핵심 담당 |
|---|---|---|---|---|
| 회원·인증 | 회원 정보, 권한 | 인증 사용자와 역할 | 식당, 예약, 관리자 | 정용태 |
| 식당·사장님 | 인증된 사장님 | 본인 식당·이미지 Key와 소유권 | 테이블·회차, 검색, 노쇼, 지급 예정금 | 정용태 |
| 테이블·예약 회차 | 식당, 정원, 날짜·시간 | 예약 가능한 테이블·시간 | 예약, 좌석, 검색 | 김홍기 |
| 예약 | 회차, 최초 예약자 | 예약 상태·모집 상태 | 참여, 취소, 노쇼 | 배지현 |
| 참여자 | 사용자, partySize, 결제 성공 | 참여자 상태·현재 참여 인원 | 좌석, 예약 상태, 환불 | 배지현 |
| 좌석·동시성 | 테이블 정원, 현재 참여 인원 | 남은 참여 가능 인원과 정원 보호 | 예약, 결제 실패 보상 | 배지현 |
| 결제 | 사용자, partySize, 예약금 | `READY/PAID/FAILED/EXPIRED/REFUNDED` | 예약·참여 등록, 환불, 지급 예정금 | 김현승 |
| 취소·환불 | 예약·참여자·모집 상태, 마감 시각 | 참여자·예약·결제 상태 변경 | 좌석, 회차 복구, 지급 예정금 | 정용태·김현승·배지현 |
| 노쇼 | 사장님 소유권, `RESERVED` 참여자 | `NO_SHOW/RESERVED`, 처리 이력 | 지급 예정금, 노쇼율 | 정용태·김현승 |
| 채팅 | 예약, 결제 완료 참여자 | 예약별 채팅방과 접근 권한 | 예약, 인증 | 김현승 |
| Outbox | 핵심 트랜잭션의 후속 처리 의도 | ChatRoom 생성, 이메일, ChatMessage Kafka 발행 | 예약, 결제, 알림, AI | 김현승 |
| AI Moderation | ChatMessage Event, Provider 결과 | 메시지별 검수 결과와 관리자 참고 신호 | 채팅, 관리자 Human Review | 김현승 |
| Restaurant Feedback Insight | ChatMessage Event 재사용, 식당 역추적 | OWNER용 익명 피드백 집계 | 채팅, 식당, Kafka | 정용태·김현승 |
| Redis Chat Pub/Sub | 커밋된 ChatMessage payload | 다중 App 인스턴스 실시간 전달 | 채팅, 배포 인프라 | 김현승·김홍기 |
| Kafka 후속 처리 | Outbox가 발행한 ChatMessageCreatedEvent | AI Moderation과 Restaurant Insight Consumer Group 처리 | 채팅, AI, 운영 재처리 | 김현승 |
| 지급 예정 예약금 | 결제·환불·취소·노쇼 결과 | 사장님 조회용 예상 금액 | 사장님 권한, 관리자 조회 | 김현승 |

구현 데이터 모델은 `Member`, `Restaurant`, `SharedTable`, `TimeSlot`, `Reservation`, `ReservationParticipant`, `Payment`, `Refund`, `ChatRoom`, `ChatMessage`, `ChatModeration`, `ChatRoomMemberReport`, `OutboxEvent`, `EmailOutboxDelivery`, `RestaurantFeedbackInsight`, `RestaurantFeedbackItem`으로 연결한다. `NoShowHistory`는 V2 OWNER의 노쇼 처리·해제 이력을 보존하기 위해 `ReservationParticipant`와 OWNER 처리자를 연결한다. `Settlement`, `SeatHold`, `WebhookEvent`는 현재 확정 엔티티가 아니다.

담당자는 단독 소유권을 뜻하지 않는다. 여러 도메인이 연결되는 Issue는 관련 담당자가 계약과 실패 결과를 함께 확인한다.

### 운영·인프라 책임

아래 표는 Backend 기능 도메인 담당 표와 별개로 운영·인프라 관점의 책임만 정리한다. 김홍기는 배포·인프라·모니터링 전반을, 배지현은 프론트엔드 전반과 Backend API 연동을 담당한다. Redis/Kafka 운영 구성 책임은 해당 인프라의 배포·연결·운영 검증을 뜻하며, 모든 비즈니스 로직 구현 책임을 뜻하지 않는다.

| 영역 | 주요 범위 | 담당 |
|---|---|---|
| Deployment / Infrastructure | AWS, EC2/RDS/ALB, Blue-Green, App EC2 다중화, 운영 환경 | 김홍기 |
| CI/CD | GitHub Actions, Docker/ECR, SSM 배포 및 배포 자동화 | 김홍기 |
| Monitoring | Prometheus/Grafana, Health Check, 운영 모니터링 | 김홍기 |
| Infra Troubleshooting | 운영 장애 분석, EC2 메모리 병목, Hikari Pool 병목, Auto Scaling 도입 판단 | 김홍기 |
| Frontend | 프론트엔드 전반 및 Backend API 연동 | 배지현 |

## 3. 핵심 공동 작업 경계

아래 흐름은 공동 검토가 필요한 도메인 연결을 나타낸다. 상태 전이, 결제·환불, 좌석 정합성의 상세 정책은 [API 명세](../api/bobfull-api-spec-complete.md), [프로젝트 컨텍스트](../product/project-context.md), [ERD](../data/erd.md)를 따른다.

### 예약 생성

```text
테이블·회차 유효성 확인
→ 모집 마감 확인
→ partySize 검증
→ 좌석 10분 임시 선점
→ Payment READY와 paymentId 생성
→ PortOne 결제
→ 결제 당사자와 Payment.memberId 확인
→ 서버 상태·금액·통화 검증
→ Payment PAID
→ 예약 생성
→ 최초 참여자 등록
→ 현재 참여 인원에 partySize 반영
→ 예약 상태 계산
→ 모집 상태 OPEN
```

필수 공동 검토:

- 김홍기: 테이블·회차 유효성, 중복 회차
- 김현승: PortOne 결제 준비·검증·웹훅·상태와 결제 금액
- 배지현: 예약·참여자·partySize·상태 반영과 중복 생성 방지

### 식당 이미지 업로드

```text
OWNER 인증
→ Presigned PUT URL 발급
→ S3 temp 경로 업로드
→ Java Lambda 검증
→ final 경로 복사와 temp 삭제
→ 식당 등록·수정 시 final 객체 존재 확인
→ restaurant.image_key 저장
→ 조회 응답에서 Presigned GET URL 생성
```

필수 공동 검토:

- 정용태: OWNER 인증·소유자 경로 검증, 식당 등록·수정의 최종 객체 존재 확인
- 김홍기: S3 버킷 CORS, temp lifecycle, Lambda 이벤트 알림·로그·메모리·Timeout
- 프론트엔드 담당: Presigned PUT 요청 헤더, 업로드 후 식당 등록·수정에 `finalImageKey` 전달

식당 이미지 업로드는 예약·좌석·결제 상태 전이를 변경하지 않는다. DB에는 `restaurant.image_key`만 저장하고, 조회 응답의 `imageUrl`은 S3 Presigned GET URL로 생성한다.

### 추가 참여

```text
예약 상태와 모집 상태 확인
→ 동일 사용자 중복 참여 확인
→ 남은 참여 가능 인원 확인
→ 좌석 10분 임시 선점
→ Payment READY와 paymentId 생성
→ PortOne 결제·서버 검증
→ Payment PAID
→ 참여자 등록
→ 현재 참여 인원·임시 선점 인원 재계산
→ 예약 상태와 모집 상태 재계산
```

필수 공동 검토:

- 배지현: 정원·동시성·상태 재계산
- 김현승: PortOne 결제와 실패·만료·웹훅 결과
- 김홍기: 조회 조건과 회차 정보

### 결제 완료 검증과 웹훅

```text
결제 준비와 좌석 10분 임시 선점
→ 프론트 PortOne 결제
→ 완료 검증 API 또는 PortOne 웹훅
→ PortOne 결제 단건 재조회
→ 상태·금액·통화 검증
→ 동일 Payment 내부 PK 비관적 락과 상태·expiresAt 재검증
→ 성공 시 PAID와 예약·참여 반영
→ 검증 실패는 FAILED, 시간 만료는 EXPIRED; 좌석은 expiresAt 계산으로 즉시 반환
```

- 완료 API는 소유권, 웹훅은 원본 Body·`webhook-id`·`webhook-signature`·`webhook-timestamp` 공식 SDK 서명을 검증한다. 웹훅은 `permitAll`과 JWT 필터 우회를 사용하지만 서명 성공 뒤에만 JSON 이벤트를 해석한다.
- 결제 완료 API와 웹훅은 동일 Payment 행 비관적 락과 `ReservationConfirmationService(MANDATORY)`의 한 트랜잭션으로 수렴해 예약·참여·결제 결과를 한 번만 반영한다. 웹훅 영구 업무 실패는 200, 인프라 실패는 5xx다.
- 외부 PAID·내부 EXPIRED 또는 만료 READY는 `PAYMENT_COMPENSATION_REQUIRED` 구조화 로그를 남기며 예약 확정·자동 취소·환불은 수행하지 않는다.
- `READY && expiresAt <= cutoff` 후보는 `(expiresAt, paymentId 내부 PK)` 순서로 최대 100건만 조회하고, 각 건은 별도 트랜잭션의 내부 PK 행 락으로 EXPIRED 정규화한다. 스케줄러는 예약 확정이나 외부 보상을 호출하지 않는다.
- 환불 완료 상태와 결제 취소 상태는 함께 반영한다. 상세 상태 관계는 [프로젝트 컨텍스트](../product/project-context.md)와 [ERD](../data/erd.md)를 따른다.

### 예약 확정과 모집 마감

```text
현재 참여 인원과 테이블별 확정 기준 비교
→ 기준 이상이면 CONFIRMED
→ 모집 상태 OPEN이면 잔여 정원까지 추가 참여
→ 최초 예약자 수동 마감은 CONFIRMED + OPEN에서만 허용
→ 정원 도달 또는 시작 2시간 전
→ 모집 상태 CLOSED
```

필수 공동 검토:

- 배지현: 테이블별 확정 기준과 상태 전이
- 김홍기: 테이블 정원·회차 시작 시각
- 김현승: 모집 실패 환불 대상과 금액

- 모집 마감 자체는 TimeSlot을 재사용 가능 상태로 바꾸지 않으며, Reservation 전체가 취소된 뒤에만 재사용 여부를 판단한다. 상세 상태·시간 조건은 [프로젝트 컨텍스트](../product/project-context.md)와 [API 명세](../api/bobfull-api-spec-complete.md)를 따른다.

### MEMBER 취소·환불

```text
인증 MEMBER와 본인 ReservationParticipant 확인
→ 서버 시간 기준 식사 시작 2시간 전인지 확인
→ 최초 예약자 여부 분기
→ [접수, 짧은 트랜잭션] 최초 예약자: Reservation CANCELLING·모든 유효 참여자 CANCEL_REQUESTED
→ [접수, 짧은 트랜잭션] 추가 참여자: 본인 ReservationParticipant CANCEL_REQUESTED
→ [외부 실행, 트랜잭션 밖] 참여자별 Payment 전액 PortOne 환불 요청
→ [완료 확정, 짧은 트랜잭션] 참여자별 환불 완료 시 CANCEL_REQUESTED → CANCELLED 확정
→ 최초 예약자: 남은 CANCEL_REQUESTED 없어야 Reservation CANCELLED 확정·조건부 TimeSlot 복구
→ 추가 참여자: 확정 시점에 currentParticipantCount와 availableCapacity 재계산
→ 모집 OPEN이면 confirmationThreshold 기준으로 RECRUITING 또는 CONFIRMED 계산
→ 수동 마감 CLOSED면 기준 이상은 CONFIRMED + CLOSED 유지, 기준 미달은 남은 유효 참여자도 같은 접수·실행·확정 절차로 전체 취소
→ CANCELLING 전환 시점부터 ChatRoom 신규 메시지 전송 종료, 지급 예정금은 환불 COMPLETED 반영 시점에 갱신
```

(#44, #45) 취소는 접수·외부 환불 실행·완료 확정 세 단계로 나뉜다. `RefundStatus`의 결과 불명확 표현은 `UNKNOWN`을 새로 도입하지 않고 `REQUESTED` 유지로 표현하는 것으로 2026-08-05 확정됐다(자세한 내용은 [PROJECT_CONTEXT](../product/project-context.md), [ERD](../data/erd.md) 참고).

필수 공동 검토:

- 정용태: 본인 참여 권한, OWNER 식당 소유권, NO_SHOW 이후 취소 차단
- 김현승: Payment 전체 환불, Payment당 Refund 1건, 지급 예정금
- 배지현: 최초·추가 참여자 분기, 참여 인원·예약 상태·모집 상태·채팅 종료 반영
- 김홍기: `CANCELLED` 예약·시작 2시간 전·활성 예약 없음·OWNER 제한 없음 조건의 TimeSlot 복구

- MEMBER 취소는 허용 시점 안에서만 처리하며, 취소 후 재모집은 지원하지 않는다. 상세 시점·참여자 분기·환불 조건은 [프로젝트 컨텍스트](../product/project-context.md)와 [API 명세](../api/bobfull-api-spec-complete.md)를 따른다.

### TimeSlot 활성 예약 정합성

```text
최초 예약 결제 준비 또는 새 Reservation 생성
→ 대상 TimeSlot 행 비관적 락 획득
→ RECRUITING·CONFIRMED 활성 Reservation 존재 여부 조회
→ CREATE면 만료되지 않은 CREATE READY Payment 존재 여부 조회
→ 활성 Reservation과 유효 CREATE READY가 모두 없을 때만 CREATE READY 또는 새 Reservation 생성
→ 트랜잭션 종료까지 TimeSlot 잠금 유지
```

- 활성 Reservation 또는 유효한 CREATE READY는 같은 TimeSlot에서 동시에 하나만 성공해야 한다. 상세 잠금·만료·JOIN 처리 조건은 [프로젝트 컨텍스트](../product/project-context.md)와 [ERD](../data/erd.md)를 따른다.

### 노쇼와 예약 종료

```text
식사 종료
→ 예약 CLOSED
→ 사장님 소유권 확인
→ 예약 참여자 목록 조회
→ RESERVED 참여자를 NO_SHOW 처리
→ 잘못 처리한 경우 NO_SHOW 해제
→ 지급 예정 예약금과 노쇼율 반영
```

- OWNER의 노쇼 처리·해제는 참여자 단위로 수행하고 처리 이력을 남긴다. 상세 허용 상태와 저장 관계는 [프로젝트 컨텍스트](../product/project-context.md), [API 명세](../api/bobfull-api-spec-complete.md), [ERD](../data/erd.md)를 따른다.
- `예약 CLOSED`는 스케줄러가 `CONFIRMED` 예약을 대상으로 `TimeSlot.endAt` 도달 후보를 조회해 전이한다. 채팅 SEND 차단과 노쇼 처리 허용은 스케줄러 처리 시점과 무관하게 `now >= TimeSlot.endAt`을 직접 비교해 같은 기준으로 즉시 판단한다(Issue #175).

### 예약 참여자 채팅

```text
최초 예약 Payment PAID
→ 예약당 ChatRoom 1개 생성
→ 결제 완료·미취소 유효 참여자만 접근
→ DB 저장·커밋 후 Redis Pub/Sub으로 각 인스턴스 Simple Broker에 실시간 전달
→ 단절 중 누락은 cursor 기반 과거 메시지 조회로 복구
→ 예약 CANCELLED 또는 CLOSED 시 새 메시지 전송 종료
→ 기존 ChatMessage는 조회 가능
```

- 결제 완료 후 취소되지 않은 참여자만 채팅에 접근하며, 취소된 참여자는 즉시 접근이 종료된다. 상세 접근·전송 조건은 [프로젝트 컨텍스트](../product/project-context.md)와 [API 명세](../api/bobfull-api-spec-complete.md)를 따른다.
- Redis Pub/Sub은 메시지를 저장하거나 다시 보내주는 경로가 아니며, 발행 실패는 이미 저장된 ChatMessage를 롤백하지 않는다.

### ChatMessage 후속 처리와 Restaurant Feedback Insight

```text
ChatMessage 저장
→ 같은 트랜잭션에 OutboxEvent(CHAT_MESSAGE_CREATED) 저장
→ 커밋 후 ChatMessageOutboxProcessor가 Kafka에 발행
→ Moderation Consumer Group은 ChatModerationService를 호출
→ Restaurant Insight Consumer Group은 messageId로 Restaurant을 역추적해 익명 피드백 Item을 저장
→ 두 Consumer Group은 offset·Retry·DLT 경계가 분리됨
```

- AI Moderation은 관리자 Human Review 참고 신호를 만들 뿐 회원 상태를 자동 변경하지 않는다.
- Restaurant Feedback Insight는 Producer/Event Schema를 바꾸지 않고 같은 ChatMessage Event를 재사용한다. production 기본 설정은 비활성일 수 있으므로, 구현 완료와 운영 enabled 상태를 구분한다.
- Kafka는 채팅 실시간 전달을 담당하지 않는다. Redis Pub/Sub과 Kafka 경로는 ChatMessage 커밋 이후 서로 다른 책임으로 분리된다.

## 4. 핵심 계산 계약

```text
현재 참여 인원
= Σ 결제 완료 참여자.partySize

임시 선점 인원
= Σ 만료되지 않은 READY 결제.partySize

남은 참여 가능 인원
= 테이블 정원 - 현재 참여 인원 - 임시 선점 인원

결제 금액
= partySize × 1인당 예약금

지급 예정 예약금
= PAID 결제 금액 합계 - COMPLETED 환불 금액 합계
```

## 5. 정책 변경 영향표

| 변경 정책 | 필수 영향 도메인 | 함께 확인할 항목 |
|---|---|---|
| 테이블 정원 `2·4·6·8` 변경 | 테이블, 예약, 좌석, 검색 | 정원 검증, 확정 기준, 남은 인원, 동시성 테스트 |
| 테이블별 확정 기준 변경 | 예약, 결제, 취소, 환불, 모집 마감 | `RECRUITING/CONFIRMED`, 모집 실패, 환불 대상 |
| `partySize` 규칙 변경 | 예약, 참여자, 결제, 취소, 노쇼 | 입력 검증, 합산, 결제 금액, 전체 단위 처리 |
| 모집 상태 `OPEN/CLOSED` 변경 | 예약, 참여, 취소, 검색 | 참여 가능 조건, 수동 마감, 자동 마감, 재오픈 금지 |
| 모집 마감 `시작 2시간 전` 변경 | 예약, 결제, 취소, 환불, 알림 | 참여 차단, 재모집, 환불 기준 |
| 결제와 참여 등록 순서 변경 | 결제, 예약, 좌석 | 10분 임시 선점, PortOne 검증, 웹훅 중복, 저장 실패, 보상 처리 |
| 예약 상태 기준 변경 | 예약, 취소, 노쇼, 지급 예정금 | 상태 전이, 종료 조건, 테스트 |
| 참여자 상태 기준 변경 | 예약, 취소, 노쇼, 지급 예정금 | 유효 참여 인원, 노쇼 해제, 금액 계산 |
| 최초 예약자 취소 정책 변경 | 취소, 예약, 환불, 좌석, 회차 | 전체 취소, 나머지 환불, 회차 복구 |
| 환불 기준 변경 | 결제, 취소, 지급 예정금, 관리자 | 환불 상태, 귀책, 미환불 금액 |
| 노쇼 처리·해제 변경 | 인증, 사장님, 예약, 참여자, 지급 예정금 | 소유권, 허용 상태, 처리 이력, 노쇼율 |
| 지급 예정금 계산 변경 | 결제, 환불, 취소, 노쇼, 사장님 | 포함·제외 항목, 조회 시점, 테스트 데이터 |
| 식당 이미지 업로드 정책 변경 | 식당, 인증, 검색, AWS 운영 | 허용 확장자·크기, S3 Key 형식, Lambda 검증, 조회 URL 만료 |

## 6. 문서와 구현 변경 체크리스트

- [ ] [`project-context.md`](../product/project-context.md)의 확정 정책과 충돌하지 않는가
- [ ] 영향을 받는 도메인과 담당자를 Issue에 적었는가
- [ ] API Base URL `/api/**`, Actuator `/actuator/**`, WebSocket `/ws`, 공통 응답, 역할 계약이 바뀌는가
- [ ] `partySize`, 정원, 확정 기준과 모집 상태가 DB 모델에 반영되는가
- [ ] 예약·참여자·결제·환불 상태 전이가 바뀌는가
- [ ] 트랜잭션 실패 후 남는 데이터가 바뀌는가
- [ ] 동시 요청 결과와 중복 방지 방식이 바뀌는가
- [ ] 권한과 소유권 검증이 바뀌는가
- [ ] 식당 이미지 Key 저장값과 조회용 Presigned URL 생성 방식이 바뀌는가
- [ ] S3 버킷 CORS, lifecycle, Lambda 이벤트 알림·로그 설정이 바뀌는가
- [ ] 환불·지급 예정금 계산이 바뀌는가
- [ ] PortOne 완료 검증의 결제 당사자 검증, 웹훅 서명·멱등성, 좌석 임시 선점 해제가 검증되는가
- [ ] 채팅 접근자가 결제 완료·미취소 유효 참여자로 제한되고 CANCELLED/CLOSED 시 전송 규칙이 지켜지는가
- [ ] 전체 플로우차트·API·ERD 중 실제 영향 문서만 수정했는가
- [ ] 완료 조건과 테스트가 변경된 계약을 증명하는가
- [ ] 폐기된 `1인 단위·2명 고정·VISITED` 정책이 남지 않았는가

## 7. v1 검증 우선순위

1. PortOne 결제 검증 실패 또는 10분 만료 시 예약·참여자·현재 참여 인원이 반영되지 않는다. 만료 좌석은 `expiresAt` 계산으로 즉시 제외되고 Payment는 `EXPIRED`로 정규화된다.
2. `partySize`가 남은 참여 가능 인원을 초과하면 실패한다.
3. 동시에 N명 참여를 요청해도 정원을 초과하지 않는다.
4. 테이블별 확정 기준에 맞춰 `RECRUITING/CONFIRMED`가 계산된다.
5. `CONFIRMED + OPEN`이면 잔여 정원까지 참여할 수 있다.
6. 모집 상태가 `CLOSED`이면 빈자리가 있어도 참여할 수 없다.
7. 한 사용자의 취소·노쇼가 해당 사용자의 `partySize` 전체에 적용된다.
