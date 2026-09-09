# BobFull ERD

## 1. 문서 목적과 기준

이 문서는 확정된 API와 프로젝트 정책을 구현 가능한 관계형 데이터 모델로 표현한다. 현재 정적 스키마의 최종 기준은 실제 `Entity / @Table / @Column / @ElementCollection / Repository / Migration / Index` 정의이며, ERD는 그 구현을 사람이 검토할 수 있는 형태로 기록한다.

- 기준: [`bobfull-api-spec-complete.md`](../20-api/bobfull-api-spec-complete.md), [`project-context.md`](../10-product/project-context.md)
- 범위: V1 예약·결제·환불 조회와 V2 취소·노쇼·채팅·운영 조회, V3 채팅 AI Moderation·Outbox·Restaurant Feedback Insight에 필요한 영속 데이터
- 비범위: Redis, Kafka, 실제 계좌 송금 및 아직 도입하지 않은 별도 영속 모델. Redis와 Kafka는 운영 인프라이며 ERD 엔티티가 아니다.
- 원칙: API Response DTO를 테이블로 만들지 않고, 기준 문서에 없는 정책은 확정하지 않는다. Mermaid의 표현 한계가 있으면 아래 엔티티 상세 표를 기준으로 한다.

### #245 정적 계약 검증 기준선

- 검증 기준 `develop` SHA: `8e17ffc1b61626ff7f4b6fd2186eaf2341f0bbd2` (2026-08-13)
- 대조: Entity/Table/Column 타입·NULL·PK·FK·UNIQUE·값 기반 참조, `@ElementCollection` 테이블, 실제 `@Index`와 `@UniqueConstraint`, API Spec의 식별자·저장값·계산값
- 결과: 코드 전용·문서 전용 스키마 요소와 API Spec ↔ ERD 간 명백한 정적 계약 모순을 확인하지 못했다. BLOCKER / MAJOR / MINOR는 0건이다.

이 결과는 정적 설계 문서 기준선이며, 성능 측정 뒤 Index 채택·제거 또는 #67 최종 주장 검토를 확정하는 근거는 아니다.

## 2. 핵심 데이터 모델 요약

| 엔티티 | 목적 | 주요 관계 | V1·V2·V3 |
|---|---|---|---|
| `Member` | 인증 사용자와 역할 | 식당 소유자, 예약 생성자, 참여자, 결제자, 메시지 발신자 | V1 |
| `Restaurant` | OWNER의 식당 | Member 1:N Restaurant | V1 |
| `SharedTable` | 식당의 합석 테이블·정원 | Restaurant 1:N SharedTable | V1 |
| `TimeSlot` | 테이블별 예약 가능 회차 | SharedTable 1:N TimeSlot | V1 |
| `Reservation` | 회차의 합석 예약과 취소 이력 | TimeSlot 1:N Reservation, 활성 예약은 1건 | V1 |
| `ReservationParticipant` | 사용자별 신청 인원·참여 상태 | Reservation과 Member의 연결 | V1 |
| `Payment` | READY 임시 선점·PortOne 결제 | Member·TimeSlot·Reservation/Participant 연결 | V1 |
| `Refund` | 결제 전체 단위 환불 상태 | Payment 1:0..1 Refund | V1/V2 |
| `NoShowHistory` | OWNER의 노쇼 처리·해제 이력 | ReservationParticipant 1:N NoShowHistory | V2 |
| `ChatRoom` | 예약당 하나의 채팅방 | Reservation 1:0..1 ChatRoom | V2 |
| `ChatMessage` | DB에 저장되는 채팅 메시지 | ChatRoom 1:N ChatMessage | V2 |
| `ChatModeration` | 메시지별 AI 분석 결과와 최종 실패 상태 | ChatMessage 1:0..1 ChatModeration | V3 |
| `RestaurantFeedbackInsight`(테이블 `restaurant_feedback_analysis`) | OWNER에 노출하지 않는 메시지별 식당 피드백 파생 결과 | ChatMessage·Restaurant 값 기반 참조, UNIQUE(chat_message_id, prompt_version) | V3 |
| `RestaurantFeedbackItem` | Analysis에서 추출된 개별 피드백 의견(0..N) | RestaurantFeedbackInsight 1:N RestaurantFeedbackItem | V3 |
| `ChatRoomMemberReport` | 채팅방 상대 회원 신고와 Human Review 이력 | ChatRoom·Member·nullable ChatMessage(anchor) 연결 | V3 |
| `OutboxEvent` | ChatRoom 생성·채팅 메시지 후속 처리·이메일 발송 의도의 공통 영속 이벤트 | `aggregateType`+`aggregateId` 값 기반(Reservation/ChatMessage/ReservationParticipant), 물리 FK 아님 | V2/V3 |
| `EmailOutboxDelivery` | 이메일 발송 대상 수신자별 성공 여부 이력 | `OutboxEvent`·Reservation·ReservationParticipant·Member 값 기반 연결, 물리 FK 아님 | V3 |

관리자 현황·통계와 지급 예정 예약금은 위 데이터의 조회·집계로 제공한다. 별도 `Settlement`, `SeatHold`, `WebhookEvent`, 관리자 전용 엔티티는 현재 계약에 추가하지 않는다.

## 3. Mermaid ERD

대부분의 도메인 참조는 원본 ID `Long` 컬럼 값으로 저장하고 JPA 연관관계를 두지 않는다. 예외적으로 `Refund.payment`, `ChatModeration.categories`의 collection table, `RestaurantFeedbackItem.analysis`는 `@JoinColumn` 또는 JPA 연관 mapping을 가진다. 이 문서는 JPA mapping과 실제 운영 DB physical constraint 확인을 구분한다. 운영 RDS snapshot으로 확인된 physical FK는 #198 기준 `refund.payment_id`, `chat_moderation_category.chat_moderation_id` 2건이며, 이후 추가된 `restaurant_feedback_item.restaurant_feedback_analysis_id`는 최신 Entity의 `@ManyToOne/@JoinColumn` mapping을 반영하되 현재 live DB constraint 존재 여부와 제약명은 별도 DDL 확인이 필요하다. 아래 Mermaid의 `FK` 표기는 JPA mapping 또는 확인된 physical FK가 있는 컬럼에만 사용하고, 나머지 참조 컬럼은 값 기반 참조이므로 컬럼에 `FK`를 표기하지 않는다. 각 컬럼이 실제 DB UNIQUE·INDEX를 갖는지는 섹션 4 상세 표를 기준으로 한다.

```mermaid
erDiagram
    MEMBER {
        bigint member_id PK "내부 식별자"
        varchar(255) email UK "로그인 식별자"
        varchar(255) password_hash "비밀번호 해시"
        varchar(255) name "참여자 목록·채팅 표시 이름"
        varchar(255) phone_number UK "회원 전화번호"
        varchar(255) business_number UK "OWNER 사업자등록번호. MEMBER는 NULL"
        enum role "MEMBER, OWNER, ADMIN"
        datetime(6) deleted_at "회원 소프트 삭제 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    RESTAURANT {
        bigint restaurant_id PK "식당 식별자"
        bigint owner_member_id "OWNER 소유자"
        varchar(100) name "식당명"
        varchar(255) address "식당 주소"
        varchar(50) category "음식 카테고리"
        varchar(1000) description "식당 소개"
        varchar(100) keyword "사장님 입력 식당 키워드"
        integer deposit_per_person "1인당 예약금"
        varchar(500) image_key "S3 최종 Object Key"
        enum status "생성 시 서버 기본값, 현재 ACTIVE만 사용"
        datetime(6) deleted_at "소프트 삭제 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    SHARED_TABLE {
        bigint shared_table_id PK "합석 테이블 식별자"
        bigint restaurant_id "소속 식당"
        integer display_number "식당별 자동 표시 번호"
        integer capacity "허용 정원 2·4·6·8"
        enum status "생성 시 서버 기본값, 현재 ACTIVE만 사용"
        datetime(6) deleted_at "소프트 삭제 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    TIME_SLOT {
        bigint time_slot_id PK "회차 식별자"
        bigint shared_table_id "대상 테이블"
        datetime(6) start_at "회차 시작 시각"
        datetime(6) end_at "회차 종료 시각"
        datetime(6) active_start_at "활성 중복 방지용 생성 컬럼"
        datetime(6) deleted_at "소프트 삭제 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    RESERVATION {
        bigint reservation_id PK "예약 식별자"
        bigint time_slot_id "대상 회차"
        bigint creator_member_id "최초 예약자"
        enum reservation_status "RECRUITING, CONFIRMED, CANCELLING, CANCELLED, CLOSED"
        enum recruitment_status "OPEN, CLOSED"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    RESERVATION_PARTICIPANT {
        bigint reservation_participant_id PK "참여 식별자"
        bigint reservation_id "대상 예약"
        bigint member_id "신청 회원"
        integer party_size "신청 인원"
        enum participation_status "RESERVED, NO_SHOW, CANCEL_REQUESTED, CANCELLED"
        datetime(6) cancelled_at "전체 참여 취소 시각"
        varchar(255) cancel_reason "취소 사유. 노쇼는 저장하지 않음"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    PAYMENT {
        bigint payment_id PK "Payment 내부 식별자"
        varchar(64) portone_payment_id UK "PortOne 외부 결제 식별자"
        bigint member_id "결제 당사자"
        bigint time_slot_id "결제 준비 대상 회차"
        bigint reservation_id "CREATE의 READY 단계는 NULL 가능"
        bigint reservation_participant_id UK "결제 완료 후 연결되는 참여자"
        enum payment_purpose "CREATE, JOIN"
        integer party_size "결제·임시 선점 인원"
        decimal(19,2) amount "party_size 기준 예약금"
        varchar(10) currency "PortOne 검증 대상 통화"
        enum payment_status "READY, PAID, EXPIRED, FAILED, REFUNDED"
        datetime(6) expires_at "READY 임시 선점 만료 시각"
        datetime(6) paid_at "PAID 전환 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    REFUND {
        bigint refund_id PK "환불 식별자"
        bigint payment_id FK "결제 전체 환불 대상"
        decimal(19,2) amount "환불 금액"
        enum refund_status "REQUESTED, PROCESSING, COMPLETED, FAILED"
        datetime(6) requested_at "요청 시각"
        datetime(6) completed_at "완료 시각"
        varchar(64) cancellation_id UK "PortOne 취소 식별자. 접수 시점 NULL"
        varchar(256) idempotency_key UK "외부 요청 멱등 키. 변경 불가"
        varchar(255) request_reason "최초 환불 요청 사유. 변경 불가"
        datetime(6) last_pg_checked_at "마지막 PG 조회 시각"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    NO_SHOW_HISTORY {
        bigint no_show_history_id PK "이력 식별자"
        bigint reservation_participant_id "처리 대상"
        bigint processed_by_member_id "처리 OWNER"
        bit(1) is_marked "TRUE=노쇼 처리, FALSE=노쇼 해제"
        datetime(6) processed_at "처리 시각"
    }
    CHAT_ROOM {
        bigint chat_room_id PK "채팅방 식별자"
        bigint reservation_id UK "예약당 1개"
        datetime(6) created_at "최초 예약 결제 완료 후 생성"
        datetime(6) updated_at "BaseTimeEntity 상속 컬럼. 별도 갱신 로직 없음"
    }
    OUTBOX_EVENT {
        bigint outbox_event_id PK "Outbox 내부 식별자"
        varchar event_id UK "이벤트 UUID"
        enum event_type "CHAT_ROOM_CREATION_REQUESTED 등 6종. Chat/Email 공통 유형"
        varchar(32) aggregate_type "RESERVATION, CHAT_MESSAGE, RESERVATION_PARTICIPANT. Java enum 아닌 String 필드"
        bigint aggregate_id "event_type별 대상 식별자 값. 물리 FK 아님"
        int payload_version "현재 1. Payload 원문·개인정보는 저장하지 않음"
        enum status "PENDING, PROCESSING, COMPLETED, FAILED"
        int attempt_count "처리 실패 횟수. 5회 재시도 후 다음 실패에서 FAILED"
        datetime(6) next_attempt_at "다음 처리 가능 시각"
        datetime(6) processing_started_at "stale PROCESSING 회수 기준. NULL 허용"
        varchar(36) processing_token "claim 소유자 토큰. NULL 허용"
        varchar(128) last_error_code "예외 유형만 기록. NULL 허용"
        datetime(6) processed_at "COMPLETED 처리 시각. NULL 허용"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    EMAIL_OUTBOX_DELIVERY {
        bigint email_outbox_delivery_id PK "내부 식별자"
        bigint outbox_event_id "대상 OutboxEvent. 물리 FK 아님"
        bigint reservation_id "대상 예약. 물리 FK 아님"
        bigint reservation_participant_id "대상 참여자. 물리 FK 아님"
        bigint recipient_member_id "수신 회원. 물리 FK 아님"
        enum status "PENDING, SENT"
        datetime(6) sent_at "SENT 전환 시각. PENDING은 NULL"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }
    CHAT_MESSAGE {
        bigint chat_message_id PK "커서 조회 기준 식별자"
        bigint chat_room_id "대상 채팅방"
        bigint sender_member_id "발신 회원"
        bigint sender_participant_id "유효 참여자 검증"
        varchar(1000) content "메시지 본문"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "BaseTimeEntity 상속 컬럼. 별도 갱신 로직 없음"
    }
    CHAT_MODERATION {
        bigint chat_moderation_id PK "내부 식별자"
        bigint chat_message_id UK "메시지당 분석 결과 1건"
        bigint version "JPA 낙관적 락 version"
        enum status "SAFE, FLAGGED, ANALYSIS_FAILED"
        enum result "SAFE, FLAGGED; 실패면 NULL"
        enum risk_level "LOW, MEDIUM, HIGH; 실패면 NULL"
        varchar provider "분석 Provider"
        varchar model_name "분석 모델"
        varchar prompt_version "적용 Prompt 계약"
        varchar policy_version "적용 Policy 계약"
        bigint latency_millis "분석 시도 지연 시간"
        bigint prompt_tokens "Provider token 관측값"
        bigint completion_tokens "Provider token 관측값"
        bigint total_tokens "Provider token 관측값"
        datetime(6) analyzed_at "결과 또는 최종 실패 기록 시각"
        varchar error_code "최종 실패 예외 유형; 완료면 NULL"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "갱신 시각"
    }
    CHAT_MODERATION_CATEGORY {
        bigint chat_moderation_id FK "ChatModeration 컬렉션 소유자"
        enum category "ModerationCategory Enum"
    }
    RESTAURANT_FEEDBACK_ANALYSIS {
        bigint restaurant_feedback_analysis_id PK "내부 식별자"
        bigint chat_message_id "값 기반 참조. ChatMessage.id. 물리 FK 아님"
        bigint restaurant_id "값 기반 참조. Restaurant.id. 물리 FK 아님"
        varchar(64) prompt_version "activePromptVersion 등 적용 Prompt 계약"
        varchar(32) provider "분석 Provider. Provider 호출 제외는 BOBFULL_RULE"
        varchar(128) model_name "분석 모델. Provider 호출 제외는 normal-exclude"
        datetime(6) analyzed_at "결과 또는 terminal 제외 기록 시각"
        enum status "COMPLETED, EXCLUDED_INPUT_PII, EXCLUDED_CANDIDATE, EXCLUDED_OUTPUT_VALIDATION"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "갱신 시각"
    }
    RESTAURANT_FEEDBACK_ITEM {
        bigint restaurant_feedback_item_id PK "내부 식별자"
        bigint restaurant_feedback_analysis_id FK "소유 Analysis"
        enum category "FOOD, SERVICE, PRICE, CLEANLINESS, ETC"
        enum aspect_type "MENU, SERVICE, PRICE, CLEANLINESS, ETC"
        varchar(40) normalized_aspect "정규화된 짧은 대상 텍스트. PII/재식별 단서 검증 통과분만 저장"
        enum opinion_type "TASTE, TEXTURE, SALTINESS, SPICINESS, SWEETNESS, PORTION, FRESHNESS, TEMPERATURE, FRIENDLINESS, SERVICE_SPEED, PRICE_LEVEL, CLEANLINESS, WAITING, ETC"
        enum sentiment "POSITIVE, NEGATIVE, NEUTRAL"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "갱신 시각"
    }
    CHAT_ROOM_MEMBER_REPORT {
        bigint chat_room_member_report_id PK "신고 식별자"
        bigint chat_room_id "대상 채팅방"
        bigint reporter_member_id "신고자"
        bigint reported_member_id "피신고자"
        bigint anchor_message_id "선택 근거 메시지"
        enum reason "ABUSE, SPAM, PERSONAL_INFORMATION, OTHER"
        varchar(500) detail "신고 상세. NULL 허용"
        enum status "PENDING, REVIEWED"
        enum decision "NO_VIOLATION, VIOLATION_CONFIRMED; PENDING은 NULL"
        bigint reviewed_by_member_id "검토 ADMIN; PENDING은 NULL"
        datetime(6) reviewed_at "검토 시각; PENDING은 NULL"
        bigint version "JPA 낙관적 락"
        datetime(6) created_at "생성 시각"
        datetime(6) updated_at "수정 시각"
    }

    MEMBER ||--o{ RESTAURANT : owns
    RESTAURANT ||--o{ SHARED_TABLE : has
    SHARED_TABLE ||--o{ TIME_SLOT : has
    TIME_SLOT ||--o{ RESERVATION : has
    MEMBER ||--o{ RESERVATION : creates
    RESERVATION ||--o{ RESERVATION_PARTICIPANT : includes
    MEMBER ||--o{ RESERVATION_PARTICIPANT : joins
    MEMBER ||--o{ PAYMENT : pays
    TIME_SLOT ||--o{ PAYMENT : targets
    RESERVATION o|--o{ PAYMENT : links
    RESERVATION_PARTICIPANT o|--o| PAYMENT : confirms
    PAYMENT ||--o| REFUND : has
    RESERVATION_PARTICIPANT ||--o{ NO_SHOW_HISTORY : has
    MEMBER ||--o{ NO_SHOW_HISTORY : processes
    RESERVATION ||--o| CHAT_ROOM : owns
    CHAT_ROOM ||--o{ CHAT_MESSAGE : contains
    MEMBER ||--o{ CHAT_MESSAGE : sends
    RESERVATION_PARTICIPANT ||--o{ CHAT_MESSAGE : sends_as
    CHAT_MESSAGE ||--o| CHAT_MODERATION : is_analyzed_as
    CHAT_MODERATION ||--o{ CHAT_MODERATION_CATEGORY : has_categories
    RESTAURANT_FEEDBACK_ANALYSIS ||--o{ RESTAURANT_FEEDBACK_ITEM : has_items
    CHAT_ROOM ||--o{ CHAT_ROOM_MEMBER_REPORT : has_reports
    CHAT_MESSAGE o|--o{ CHAT_ROOM_MEMBER_REPORT : anchors
    MEMBER ||--o{ CHAT_ROOM_MEMBER_REPORT : reports
    MEMBER ||--o{ CHAT_ROOM_MEMBER_REPORT : is_reported
    MEMBER o|--o{ CHAT_ROOM_MEMBER_REPORT : reviews
```

## 4. 엔티티 상세

모든 Enum 표기는 애플리케이션 Enum 값이다. 이 값은 Java `enum` 타입 필드에 `@Enumerated(EnumType.STRING)`으로 매핑되며, Spring Boot 4.1.0(Hibernate 7 계열) 기본 MySQL Dialect는 별도 `@JdbcTypeCode` 오버라이드가 없는 이 필드들을 VARCHAR가 아닌 네이티브 MySQL `ENUM(...)` 컬럼으로 생성한다(이 저장소에는 그런 오버라이드가 없다). 따라서 아래 표의 Java `enum` 필드는 `ENUM(...)` 타입으로 표기하고, 실제로는 `String` 필드(예: `outbox_event.aggregate_type`)인 값은 `VARCHAR(N)`로 구분해 표기한다.

`created_at`/`updated_at`은 `BaseTimeEntity`(`@MappedSuperclass`)의 `@CreatedDate`/`@LastModifiedDate` 필드로 상속된다. 이 두 필드는 `@Column(updatable = false)`(`createdAt`)와 무옵션(`updatedAt`)만 선언돼 있고 `nullable = false`가 없어, DB 컬럼 자체는 NULL을 허용한다(NULL 컬럼 `Y`). Spring Data JPA Auditing(`AuditingEntityListener`)이 `@PrePersist`/`@PreUpdate` 시점에 항상 값을 채워 실제로는 NULL이 저장되지 않지만, 이는 애플리케이션 레벨 보장이며 DB 제약이 아니다. `NoShowHistory`는 `BaseTimeEntity`를 상속하지 않아 이 두 컬럼이 없다.

### 4.1 `member`

목적: 인증 사용자와 `MEMBER`·`OWNER`·`ADMIN` 역할을 보관한다.

| 컬럼                       | 타입 후보 | NULL | Key·제약 | 설명 |
|----------------------------|---|---:|---|---|
| `member_id`                | BIGINT | N | PK | 내부 식별자 |
| `email`                    | VARCHAR(255) | N | UNIQUE | 로그인 식별자 |
| `password_hash`            | VARCHAR(255) | N |  | 비밀번호 해시 |
| `name`                     | VARCHAR(255) | N |  | 참여자 목록·채팅 표시 이름. `@Column`에 별도 `length` 지정이 없어 Hibernate 기본값 255 적용 |
| `phone_number`             | VARCHAR(255) | N | UNIQUE | 회원 전화번호. `@Column`에 별도 `length` 지정이 없어 Hibernate 기본값 255 적용 |
| `business_number`          | VARCHAR(255) | Y | UNIQUE | OWNER 회원가입 시 저장하는 사업자등록번호. MEMBER는 NULL. NULL은 중복 허용, 값이 있으면 중복 금지. `@Column`에 별도 `length` 지정이 없어 Hibernate 기본값 255 적용 |
| `role`                     | ENUM('MEMBER', 'OWNER', 'ADMIN') | N |  | 역할 |
| `deleted_at`               | DATETIME(6) | Y |  | 회원 소프트 삭제 시각 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

회원 탈퇴 후에도 동일 `email`, `phone_number`, `business_number` 재사용은 허용하지 않는다. 탈퇴 회원의 고유 식별자 값은 변경하지 않고 보존하며, DB UNIQUE 제약도 유지한다.

### 4.2 `restaurant`

목적: OWNER가 소유·관리하는 식당이다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `restaurant_id` | BIGINT | N | PK | 식당 식별자 |
| `owner_member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님) | OWNER 소유자 |
| `name` | VARCHAR(100) | N |  | 식당명 |
| `address` | VARCHAR(255) | N |  | 식당 주소 |
| `category` | VARCHAR(50) | N |  | 음식 카테고리 |
| `description` | VARCHAR(1000) | N |  | 식당 소개 |
| `keyword` | VARCHAR(100) | N |  | 사장님이 직접 입력하는 식당 키워드 |
| `deposit_per_person` | INTEGER | N |  | 1인당 예약금 |
| `image_key` | VARCHAR(500) | Y |  | S3 최종 Object Key. `restaurants/{ownerId}/{uuid}.{extension}` 형식만 저장하며 URL은 저장하지 않음 |
| `status` | ENUM('ACTIVE') | N |  | 생성 시 서버 기본값. 현재 `ACTIVE`만 사용 |
| `deleted_at` | DATETIME(6) | Y |  | API의 소프트 삭제 정책 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`ACTIVE` 외 상태값과 상태 전이는 기준 문서에 없다. 상태 변경 API도 이번 범위에 없다.

### 4.3 `shared_table`

목적: 식당의 합석 정원 단위 테이블이다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `shared_table_id` | BIGINT | N | PK | 합석 테이블 식별자 |
| `restaurant_id` | BIGINT | N | 값 참조 → `restaurant.restaurant_id`(물리 FK 아님), INDEX `idx_shared_table_restaurant_id` | 소속 식당 |
| `display_number` | INTEGER | N | DB UNIQUE 아님(애플리케이션에서 `MAX+1`로 생성) | 식당별 1부터 자동 증가하며 삭제 후에도 재사용하지 않는 표시 번호 |
| `capacity` | INTEGER | N | CHECK 후보: `2,4,6,8` | 허용 정원 |
| `status` | ENUM('ACTIVE') | N |  | 생성 시 서버 기본값. 현재 `ACTIVE`만 사용 |
| `deleted_at` | DATETIME(6) | Y |  | API의 소프트 삭제 정책 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

### 4.4 `time_slot`

목적: 합석 테이블별 예약 가능 회차다. API의 `diningSession`에 대응한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `time_slot_id` | BIGINT | N | PK | 회차 식별자 |
| `shared_table_id` | BIGINT | N | 값 참조 → `shared_table.shared_table_id`(물리 FK 아님), 복합 UNIQUE `uk_time_slot_active_start`의 선행 컬럼 | 대상 테이블 |
| `start_at`, `end_at` | DATETIME(6) | N | `end_at > start_at` | 회차 시작·종료 시각 |
| `active_start_at` | DATETIME(6) | Y | GENERATED, 복합 UNIQUE `uk_time_slot_active_start`의 후행 컬럼(실제 구현됨) | 활성 회차 중복 방지용 생성 컬럼. `deleted_at IS NULL`이면 `start_at`, 삭제된 회차는 NULL. API 입력·응답 값이 아니다 |
| `deleted_at` | DATETIME(6) | Y |  | API의 소프트 삭제 정책 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

동일 테이블의 동일 날짜·시작 시간 중복은 `deleted_at IS NULL`인 활성 회차끼리만 금지한다. 삭제된 회차 이력은 보존하지만, 같은 테이블·같은 시작 시각의 신규 회차를 다시 생성할 수 있다. DB 강제는 `active_start_at = CASE WHEN deleted_at IS NULL THEN start_at ELSE NULL END` 생성 컬럼과 `UNIQUE(shared_table_id, active_start_at)` 조합으로 적용한다.

### 4.5 `reservation`

목적: 회차에 생성되는 합석 예약과 예약·모집 상태를 보관한다. 취소 이력은 보존하며, 같은 회차에는 활성 예약만 한 건 존재할 수 있다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `reservation_id` | BIGINT | N | PK | 예약 식별자 |
| `time_slot_id` | BIGINT | N | 값 참조 → `time_slot.time_slot_id`(물리 FK 아님) | 대상 회차 |
| `creator_member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님) | 최초 예약자 |
| `reservation_status` | ENUM('RECRUITING', 'CONFIRMED', 'CANCELLING', 'CANCELLED', 'CLOSED') | N |  | 예약 상태 |
| `recruitment_status` | ENUM('OPEN', 'CLOSED') | N |  | 모집 상태 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

최초 예약자는 `reservation_participant`에도 존재한다. `creator_member_id`는 최초 예약자만 가능한 모집 마감·취소 권한을 빠르고 명확하게 검증하기 위한 중복 저장이다. 결제 완료 시 최초 참여자와 동일 회원인지 같은 트랜잭션에서 보장해야 한다. `CANCELLED` 예약은 이력을 위해 TimeSlot 연결을 유지하고, 해당 회차의 다음 예약 생성은 활성 Reservation 유무를 트랜잭션에서 확인한다. `CANCELLING`은 취소가 접수돼 외부 환불을 기다리는 중간 상태이며(#44), `isActive()`는 `RECRUITING`·`CONFIRMED`만 참으로 취급해 `CANCELLING`도 신규 JOIN·결제 확정 대상에서 제외한다.

### 4.6 `reservation_participant`

목적: 한 회원이 예약에 신청한 인원과 참여 상태를 보관한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `reservation_participant_id` | BIGINT | N | PK | 참여 식별자 |
| `reservation_id` | BIGINT | N | 값 참조 → `reservation.reservation_id`(물리 FK 아님), 복합 UNIQUE `uk_reservation_participant_member`의 선행 컬럼 | 대상 예약 |
| `member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님), 복합 UNIQUE `uk_reservation_participant_member`의 후행 컬럼 | 신청 회원 |
| `party_size` | INTEGER | N | CHECK 후보: `>= 1` | 신청 인원 |
| `participation_status` | ENUM('RESERVED', 'NO_SHOW', 'CANCEL_REQUESTED', 'CANCELLED') | N |  | 참여자 상태 |
| `cancelled_at` | DATETIME(6) | Y |  | 전체 참여 취소 시각 |
| `cancel_reason` | VARCHAR(255) | Y |  | 취소 사유(MEMBER 본인 취소·식당 귀책 취소 공통). 노쇼는 사유를 저장하지 않는다 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`(reservation_id, member_id)`는 유니크다. 최초 참여자는 `reservation.creator_member_id`와 같은 회원으로 판별한다. 부분 인원 변경·부분 취소·부분 노쇼는 모델 범위에 없다. MEMBER 취소는 서버 시간 기준 식사 시작 2시간 전에 접수되며, `RESERVED → CANCEL_REQUESTED`로 즉시 전이해 취소 접수를 커밋하고, 연결된 Payment 전체 금액의 외부 환불이 완료된 뒤에야 `CANCEL_REQUESTED → CANCELLED`로 확정된다(#44, #45). `cancelled_at`은 이 최종 확정 시각을 기록한다.
예약 전체 취소 사유도 각 유효 `reservation_participant.cancel_reason`에 동일하게 기록한다. `reservation`에는 별도 취소 사유 컬럼을 두지 않는다.

### 4.7 `payment`

목적: PortOne 결제와 `READY` 상태의 10분 임시 좌석 선점을 함께 표현한다. 별도 `seat_hold`는 사용하지 않는다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `payment_id` | BIGINT | N | PK, AUTO_INCREMENT, 복합 INDEX `idx_payment_status_expires_at_id`의 후행 컬럼 | Payment 내부 식별자 |
| `portone_payment_id` | VARCHAR(64) | N | UNIQUE | PortOne 결제 요청·조회·웹훅과 외부 API에 사용하는 식별자 |
| `member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님) | 결제 당사자 |
| `time_slot_id` | BIGINT | N | 값 참조 → `time_slot.time_slot_id`(물리 FK 아님) | 결제 준비 대상 회차 |
| `reservation_id` | BIGINT | Y | 값 참조 → `reservation.reservation_id`(물리 FK 아님) | `CREATE`의 READY 단계에서는 NULL 가능 |
| `reservation_participant_id` | BIGINT | Y | 값 참조 → `reservation_participant.reservation_participant_id`(물리 FK 아님), UNIQUE | 결제 완료 후 연결되는 참여자 |
| `payment_purpose` | ENUM('CREATE', 'JOIN') | N |  | 결제 준비 구분 |
| `party_size` | INTEGER | N | CHECK 후보: `>= 1` | 결제·임시 선점 인원 |
| `amount` | DECIMAL(19,2) | N |  | `party_size` 기준 예약금 |
| `currency` | VARCHAR(10) | N |  | PortOne 검증 대상 통화 |
| `payment_status` | ENUM('READY', 'PAID', 'EXPIRED', 'FAILED', 'REFUNDED') | N | 복합 INDEX `idx_payment_status_expires_at_id`의 선행 컬럼 | 결제 상태 |
| `expires_at` | DATETIME(6) | N | 복합 INDEX `idx_payment_status_expires_at_id`의 중간 컬럼 | READY 임시 선점 만료 시각 |
| `paid_at` | DATETIME(6) | Y |  | PAID 전환 시각 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`payment_id`는 DB 내부 PK이며 Java 필드 `id`에 매핑한다. `portone_payment_id`는 Java 필드 `paymentId`에 매핑하며, 외부 식별자 중복을 막고 결제 완료 API·웹훅 멱등 처리의 기준이 된다. `CREATE`는 READY 생성 시 `reservation_id`, `reservation_participant_id`가 NULL이고, PAID 전환 후 생성된 예약·최초 참여자와 연결한다. 시간 만료는 `READY && expires_at <= now`에서만 `EXPIRED`로 정규화하며, 좌석 계산은 정규화 전에도 `expires_at > now`인 READY만 포함한다. 만료 후보 조회 인덱스는 `(payment_status, expires_at, payment_id)`이고 외부 `portone_payment_id`가 아닌 내부 PK를 반환·잠금 대상으로 사용한다. 동일 `time_slot_id`에는 만료되지 않은 `payment_purpose=CREATE`, `payment_status=READY` Payment를 최대 1건만 허용한다. CREATE READY가 만료되거나 `EXPIRED`가 된 뒤에는 새 CREATE READY를 생성할 수 있다. `JOIN`은 기존 예약을 참조하며 `availableCapacity`를 기준으로 별도 처리한다.

### 4.8 `refund`

목적: 한 결제 전체에 대한 환불 처리 상태를 보관한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `refund_id` | BIGINT | N | PK | 환불 식별자 |
| `payment_id` | BIGINT | N | FK → `payment.payment_id`, UNIQUE | Payment 내부 PK를 참조하는 결제 전체 환불 대상 |
| `amount` | DECIMAL(19,2) | N |  | 환불 금액 |
| `refund_status` | ENUM('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED') | N |  | 환불 상태 |
| `requested_at`, `completed_at` | DATETIME(6) | Y |  | 요청·완료 시각 |
| `cancellation_id` | VARCHAR(64) | Y | UNIQUE | PortOne 취소(cancellation) 식별자. 요청 접수 시점에는 비어 있고, PortOne 응답·웹훅으로 확인되면 저장한다(#45) |
| `idempotency_key` | VARCHAR(256) | N | UNIQUE, 변경 불가 | PortOne 환불 POST 전에 생성하는 외부 요청 식별자. DB에는 따옴표 없는 원본 값을 저장한다(#145) |
| `request_reason` | VARCHAR(255) | N | 변경 불가 | 최초 환불 요청 사유. `@Column`에 별도 `length` 지정이 없어 Hibernate 기본값 255 적용. amount·paymentId·idempotencyKey와 함께 동일 외부 요청 본문으로 고정한다(#145) |
| `last_pg_checked_at` | DATETIME(6) | Y |  | 외부 PG 조회를 실제로 시도한 시각. `updated_at`과 분리해 재확인 후보를 공정하게 순환한다(#141) |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

한 사용자의 `partySize` 결제 전체만 환불하므로 결제당 환불은 0..1건으로 모델링한다. 실패 재시도는 새 환불 행이 아니라 같은 환불의 상태 전이로 처리한다.

**2026-08-05 Human 확정**: Issue #44 완료 조건은 원래 `Refund=UNKNOWN` 상태 추가를 명시했으나, `UNKNOWN`처럼 애매한 상태를 추가로 늘리지 않기로 확정했다. 결과 불명확(timeout·connection reset 등)은 `REQUESTED` 유지로 표현하는 것이 최종 정책이며, 이 표는 그 확정된 정책을 반영한다.

### 4.9 `no_show_history`

목적: OWNER의 노쇼 처리와 해제 이력을 보관한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `no_show_history_id` | BIGINT | N | PK | 이력 식별자 |
| `reservation_participant_id` | BIGINT | N | 값 참조 → `reservation_participant.reservation_participant_id`(물리 FK 아님) | 처리 대상 |
| `processed_by_member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님) | 처리 OWNER |
| `is_marked` | BIT(1) | N |  | `TRUE`=노쇼 처리, `FALSE`=노쇼 해제 |
| `processed_at` | DATETIME(6) | N |  | 처리 시각 |

노쇼는 사유 없이 방문하지 않은 상태를 기록하는 것이므로 처리 사유를 저장하지 않는다.

### 4.10 `chat_room`

목적: 최초 예약 결제 완료 후 생성되는 예약당 하나의 채팅방이다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `chat_room_id` | BIGINT | N | PK | 채팅방 식별자 |
| `reservation_id` | BIGINT | N | 값 참조 → `reservation.reservation_id`(물리 FK 아님), UNIQUE | 예약당 1개 |
| `created_at` | DATETIME(6) | Y |  | 최초 예약 결제 완료 후 생성 |
| `updated_at` | DATETIME(6) | Y |  | `BaseTimeEntity` 상속으로 생성되는 컬럼. 별도 갱신 로직 없이 생성 시각과 함께 초기화 |

### 4.11 `chat_message`

목적: 예약 참여자가 발신하고 DB에 보관하는 채팅 메시지다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `chat_message_id` | BIGINT | N | PK, 복합 INDEX `idx_chat_message_room_id`의 후행 컬럼 | 커서 조회 기준 식별자 |
| `chat_room_id` | BIGINT | N | 값 참조 → `chat_room.chat_room_id`(물리 FK 아님), 복합 INDEX `idx_chat_message_room_id`의 선행 컬럼 | 대상 채팅방 |
| `sender_member_id` | BIGINT | N | 값 참조 → `member.member_id`(물리 FK 아님) | 발신 회원 |
| `sender_participant_id` | BIGINT | N | 값 참조 → `reservation_participant.reservation_participant_id`(물리 FK 아님) | 유효 참여자 검증 |
| `content` | VARCHAR(1000) | N |  | 메시지 본문 |
| `created_at` | DATETIME(6) | Y |  | 생성 시각 |
| `updated_at` | DATETIME(6) | Y |  | `BaseTimeEntity` 상속으로 생성되는 컬럼. 별도 갱신 로직 없이 생성 시각과 함께 초기화 |

읽음 처리, 이미지·파일, 수정·삭제, 차단은 현재 범위에서 제외한다. 사용자 신고는 V3 #218에 포함하며, AI Moderation과 신고 누적은 Human Review 참고 신호일 뿐 자동 제재 점수·자동 BAN 경로로 사용하지 않는다.

### 4.12 `chat_moderation`

목적: ChatMessage 원문은 바꾸지 않고, 메시지별 AI 분석 완료 결과 또는 Kafka Retry/DLT 소진 뒤의 최종 실패 상태를 보관한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `chat_moderation_id` | BIGINT | N | PK | 내부 식별자 |
| `chat_message_id` | BIGINT | N | UNIQUE | 메시지당 분석 결과 1건. 현재 Entity는 물리 FK 대신 원본 메시지 식별자를 보관 |
| `version` | BIGINT | N | JPA `@Version` | stale UPDATE를 거절하는 낙관적 락 version |
| `status` | ENUM('SAFE', 'FLAGGED', 'ANALYSIS_FAILED') | N |  | 분석 처리 상태 |
| `result` | ENUM('SAFE', 'FLAGGED') | Y |  | 완료 상태에서 `SAFE` 또는 `FLAGGED`; 실패면 NULL |
| `risk_level` | ENUM('LOW', 'MEDIUM', 'HIGH') | Y |  | 완료 상태에서 `LOW`, `MEDIUM`, `HIGH`; 실패면 NULL |
| `provider` | VARCHAR(32) | N |  | 분석 Provider 관측값 |
| `model_name` | VARCHAR(128) | N |  | 분석 모델 관측값 |
| `prompt_version` | VARCHAR(64) | N |  | 적용한 Prompt 계약 |
| `policy_version` | VARCHAR(64) | N |  | 적용한 Policy 계약 |
| `latency_millis` | BIGINT | N |  | 해당 분석 시도 관측값 |
| `prompt_tokens`, `completion_tokens`, `total_tokens` | BIGINT | Y |  | Provider가 제공한 token 관측값; 실패면 NULL 가능 |
| `analyzed_at` | DATETIME(6) | N |  | 결과·최종 실패 기록 시각 |
| `error_code` | VARCHAR(128) | Y |  | 최종 실패 예외 유형; 완료면 NULL |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

#### `chat_moderation_category` (`@ElementCollection`)

`ChatModeration.categories`는 별도 Entity가 아닌 `@ElementCollection(fetch = EAGER)`이며, 완료 결과의 복수 `ModerationCategory`를 다음 컬렉션 테이블에 저장한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `chat_moderation_id` | BIGINT | N | FK `fk_chat_moderation_category_moderation` → `chat_moderation.chat_moderation_id` | 컬렉션 소유 `ChatModeration` 식별자 |
| `category` | ENUM('PROFANITY', 'PERSONAL_INFORMATION', 'SPAM') | N |  | `ModerationCategory` 값 |

`version`은 동일 실패 행을 읽은 성공/실패 경로의 늦은 UPDATE가 완료 결과를 덮는 것을 막는다.

### 4.12.1 `chat_room_member_report`

채팅방의 상대 회원에 대한 사용자 신고와 ADMIN Human Review 기록이다. `chat_room_member_report_id`가 PK이며, `chat_room_id`, `reporter_member_id`, `reported_member_id`, nullable `anchor_message_id`, `reason`, nullable `detail`, `status`, nullable `decision`, nullable `reviewed_by_member_id`, nullable `reviewed_at`, `version`, `created_at`, `updated_at`을 저장한다. `chat_room_id`·`reporter_member_id`·`reported_member_id`·`anchor_message_id`·`reviewed_by_member_id`는 물리 FK가 아닌 원본 식별자 값이다. `UNIQUE(reporter_member_id, chat_room_id, reported_member_id)`로 같은 신고자·방·대상 중복을 막는다. `status`는 `PENDING`에서 `REVIEWED`로만 전이하며 `decision`은 `NO_VIOLATION` 또는 `VIOLATION_CONFIRMED`다. 판단은 회원 상태를 자동 변경하지 않는다.

### 4.12.2 `restaurant_feedback_analysis` / `restaurant_feedback_item`

목적: ChatMessage 원문을 OWNER에게 노출하지 않고, 메시지 단위로 식당 피드백을 분류한 파생 결과(0..N개 의견)를 보관한다. #59/#66 Moderation Pipeline과 같은 `ChatMessageCreatedEvent`를 별도 Consumer Group(`bobfull-restaurant-insight-*`)에서 재사용한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `restaurant_feedback_analysis_id` | BIGINT | N | PK | 내부 식별자 |
| `chat_message_id` | BIGINT | N | UNIQUE `uk_feedback_analysis_message_prompt`(`chat_message_id`, `prompt_version`)의 선행 컬럼 | 값 참조 → `chat_message.chat_message_id`(물리 FK 아님). `ChatMessage.chatRoomId → ChatRoom.reservationId → Reservation.timeSlotId → TimeSlot.sharedTableId → SharedTable.restaurantId` 경로로 `restaurant_id`를 역산해 저장 |
| `restaurant_id` | BIGINT | N | INDEX `idx_feedback_analysis_restaurant_prompt`의 선행 컬럼 | 값 참조 → `restaurant.restaurant_id`(물리 FK 아님) |
| `prompt_version` | VARCHAR(64) | N | UNIQUE 후행 컬럼, INDEX 후행 컬럼 | 처리 시점의 `activePromptVersion`. 버전별 결과를 보존하며 OWNER 집계는 현재 `activePromptVersion` 값만 사용 |
| `provider` | VARCHAR(32) | N |  | 분석 Provider. Provider 호출 후보에서 제외된 경우(`EXCLUDED_INPUT_PII`/`EXCLUDED_CANDIDATE`/`EXCLUDED_OUTPUT_VALIDATION`)는 고정값 `BOBFULL_RULE` |
| `model_name` | VARCHAR(128) | N |  | 분석 모델. Provider 호출 후보에서 제외된 경우는 고정값 `normal-exclude` |
| `analyzed_at` | DATETIME(6) | N | INDEX 후행 컬럼 | 완료 또는 terminal 제외 기록 시각. OWNER 최근 7일 집계 기준 컬럼 |
| `status` | ENUM('COMPLETED', 'EXCLUDED_INPUT_PII', 'EXCLUDED_CANDIDATE', 'EXCLUDED_OUTPUT_VALIDATION') | N |  | `COMPLETED`만 `RestaurantFeedbackItem`을 가진다. 나머지 3개는 Item 0개인 terminal 제외 상태 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`UNIQUE(chat_message_id, prompt_version)`는 동일 `(messageId, promptVersion)` 재전달 시 Provider 재호출과 결과 중복 저장을 막는 멱등성 경계다. 서로 다른 `prompt_version`은 같은 `chat_message_id`로 공존할 수 있다(예: `v1`, `v2` 동시 보존).

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---|---:|---|
| `restaurant_feedback_item_id` | BIGINT | N | PK | 내부 식별자 |
| `restaurant_feedback_analysis_id` | BIGINT | N | JPA `@ManyToOne/@JoinColumn` 참조 → `restaurant_feedback_analysis.restaurant_feedback_analysis_id`, UNIQUE `uk_feedback_item_analysis_key`(`restaurant_feedback_analysis_id`, `category`, `aspect_type`, `normalized_aspect`, `opinion_type`, `sentiment`)의 선행 컬럼 | 소유 Analysis. 현재 live DB physical FK constraint 존재 여부와 제약명은 별도 DDL 확인 필요 |
| `category` | ENUM('FOOD', 'SERVICE', 'PRICE', 'CLEANLINESS', 'ETC') | N | UNIQUE 후행 컬럼, INDEX `idx_feedback_item_aggregation`의 선행 컬럼 |  |
| `aspect_type` | ENUM('MENU', 'SERVICE', 'PRICE', 'CLEANLINESS', 'ETC') | N | UNIQUE 후행 컬럼, INDEX 후행 컬럼 |  |
| `normalized_aspect` | VARCHAR(40) | N | UNIQUE 후행 컬럼, INDEX 후행 컬럼 | NFKC 정규화, 공백 축약, Unicode 40 code point 이하, PII/재식별 단서(전화번호·이메일·예약·주문번호·인물 식별 표현 등) 검증을 통과한 값만 저장 |
| `opinion_type` | ENUM('TASTE', 'TEXTURE', 'SALTINESS', 'SPICINESS', 'SWEETNESS', 'PORTION', 'FRESHNESS', 'TEMPERATURE', 'FRIENDLINESS', 'SERVICE_SPEED', 'PRICE_LEVEL', 'CLEANLINESS', 'WAITING', 'ETC') | N | UNIQUE 후행 컬럼, INDEX 후행 컬럼 |  |
| `sentiment` | ENUM('POSITIVE', 'NEGATIVE', 'NEUTRAL') | N | UNIQUE 후행 컬럼, INDEX 후행 컬럼 |  |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`UNIQUE(restaurant_feedback_analysis_id, category, aspect_type, normalized_aspect, opinion_type, sentiment)`는 동일 Analysis 안에서 같은 5-field 조합 Item이 중복 저장되는 것을 막는다. `idx_feedback_item_aggregation(category, aspect_type, normalized_aspect, opinion_type, sentiment)`는 OWNER 5-field 집계(`RestaurantFeedbackInsightRepository.aggregateForOwner`)의 `group by`/`having` 조회를 지원한다.

OWNER 조회(`GET /api/owner/restaurants/{restaurantId}/feedback-insights`)는 `restaurant_id`+`prompt_version`(=`activePromptVersion`)+최근 7일(`analyzed_at`)로 필터링한 뒤, `RestaurantFeedbackItem`을 `category+aspect_type+normalized_aspect+opinion_type+sentiment` 5-field로 묶고 `chat_message_id → chat_message.sender_member_id` 조인으로 `distinct sender_member_id`를 계산해 3 이상인 그룹만 반환한다. `sender_member_id`는 `RestaurantFeedbackItem`/`RestaurantFeedbackAnalysis`에 복제 저장하지 않는다.

### 4.13 `outbox_event`

목적: 핵심 상태 변경과 함께 후속 처리 의도(ChatRoom 생성, 채팅 메시지 후속 처리, 이메일 발송)를 같은 트랜잭션에 영속화해, 커밋 뒤 메모리 signal 유실·재시작 뒤에도 재처리할 근거를 남긴다(#176). V3에서 채팅 메시지 후속 처리(`CHAT_MESSAGE_CREATED`)와 이메일 발송(`EMAIL_*`, #183)이 같은 공통 Outbox를 재사용하도록 확장됐다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `outbox_event_id` | BIGINT | N | PK, 복합 INDEX `idx_outbox_event_status_next_attempt`의 후행 컬럼 | 내부 식별자 |
| `event_id` | VARCHAR(36) | N | UNIQUE | UUID 이벤트 식별자 |
| `event_type` | ENUM('CHAT_ROOM_CREATION_REQUESTED', 'EMAIL_RESERVATION_CREATED', 'EMAIL_PARTICIPATION_COMPLETED', 'EMAIL_RECRUITMENT_CONFIRMED', 'EMAIL_RECRUITMENT_CANCELLED', 'CHAT_MESSAGE_CREATED') | N | UNIQUE(event_type, aggregate_type, aggregate_id) | 이벤트 유형 |
| `aggregate_type` | VARCHAR(32) | N | 위 복합 UNIQUE. Java enum 아닌 String 필드라 VARCHAR 유지 | `RESERVATION`(ChatRoom 생성·`EMAIL_RECRUITMENT_*`), `CHAT_MESSAGE`(`CHAT_MESSAGE_CREATED`), `RESERVATION_PARTICIPANT`(`EMAIL_RESERVATION_CREATED`, `EMAIL_PARTICIPATION_COMPLETED`) 중 하나 |
| `aggregate_id` | BIGINT | N | 위 복합 UNIQUE | `aggregate_type`에 대응하는 `reservation_id`, `chat_message_id`, 또는 `reservation_participant_id` 값. 물리 FK가 아닌 값 기반 참조 |
| `payload_version` | INT | N |  | 현재 1. Payload 원문·개인정보는 저장하지 않음 |
| `status` | ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED') | N | 복합 INDEX `idx_outbox_event_status_next_attempt`의 선행 컬럼 | 처리 상태 |
| `attempt_count` | INT | N |  | 해당 이벤트의 처리 실패 횟수. 최초 처리 뒤 5회 재시도 후 다음 실패에서 FAILED |
| `next_attempt_at` | DATETIME(6) | N | 복합 INDEX `idx_outbox_event_status_next_attempt`의 중간 컬럼 | 다음 처리 가능 시각 |
| `processing_started_at` | DATETIME(6) | Y |  | stale PROCESSING 회수 기준 |
| `processing_token` | VARCHAR(36) | Y |  | claim 소유자 토큰. 오래된 작업자의 상태 덮어쓰기를 방지 |
| `last_error_code` | VARCHAR(128) | Y |  | 예외 유형만 기록하며 민감 payload는 저장하지 않음 |
| `processed_at` | DATETIME(6) | Y |  | COMPLETED 처리 시각 |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

### 4.14 `email_outbox_delivery`

목적: 이메일 주소 원문을 저장하지 않고, `outbox_event` 하나가 대표하는 이메일 발송 의도에 대해 수신자별 발송 성공 여부만 별도로 추적한다(#183). 공통 Outbox의 재시도·`FAILED` 판정은 `outbox_event` 쪽 상태로 관리하며, 이 표는 수신자별 부분 성공만 구분한다.

| 컬럼 | 타입 후보 | NULL | Key·제약 | 설명 |
|---|---|---:|---|---|
| `email_outbox_delivery_id` | BIGINT | N | PK | 내부 식별자 |
| `outbox_event_id` | BIGINT | N | UNIQUE(outbox_event_id, recipient_member_id)의 선행 컬럼, 복합 INDEX `idx_email_outbox_delivery_event_status(outbox_event_id, status)`의 선행 컬럼. 물리 FK 아님 | 대상 `outbox_event.outbox_event_id` 값 |
| `reservation_id` | BIGINT | N |  | 대상 예약. 물리 FK 아님 |
| `reservation_participant_id` | BIGINT | N |  | 대상 참여자. 물리 FK 아님 |
| `recipient_member_id` | BIGINT | N | 위 복합 UNIQUE | 수신 회원. 물리 FK 아님 |
| `status` | ENUM('PENDING', 'SENT') | N | 복합 INDEX `idx_email_outbox_delivery_event_status`의 후행 컬럼(선행 컬럼은 `outbox_event_id`) | 발송 상태 |
| `sent_at` | DATETIME(6) | Y |  | `SENT` 전환 시각. `PENDING`은 NULL |
| `created_at`, `updated_at` | DATETIME(6) | Y |  | 생성·수정 시각 |

`UNIQUE(outbox_event_id, recipient_member_id)`로 같은 발송 의도에 대한 같은 수신자 중복 생성을 막는다. `idx_email_outbox_delivery_event_status(outbox_event_id, status)`는 이벤트별 미발송(`PENDING`) 수신자 조회에 사용한다. `outbox_event_id`, `reservation_id`, `reservation_participant_id`, `recipient_member_id`는 모두 원본 식별자 값이며 JPA 연관관계·물리 FK로 매핑하지 않는다.

## 5. 관계와 Cardinality

- `MEMBER 1:N RESTAURANT`: OWNER가 여러 식당을 소유할 수 있다.
- `RESTAURANT 1:N SHARED_TABLE`: 식당은 여러 합석 테이블을 가진다.
- `SHARED_TABLE 1:N TIME_SLOT`: 테이블은 여러 예약 가능 회차를 가진다.
- `TIME_SLOT 1:N RESERVATION`: 취소 이력을 포함하면 한 회차에 여러 예약이 연결될 수 있다. 단, `RECRUITING` 또는 `CONFIRMED` 활성 Reservation은 회차당 한 건만 허용한다. `CANCELLED` Reservation은 TimeSlot 연결을 유지한다.
- `RESERVATION 1:N RESERVATION_PARTICIPANT`: 예약에는 최초·추가 참여자가 존재한다.
- `MEMBER 1:N RESERVATION_PARTICIPANT`: 회원은 여러 예약에 참여할 수 있다.
- `MEMBER 1:N PAYMENT`, `TIME_SLOT 1:N PAYMENT`: 결제 준비·완료 이력을 회원과 회차별로 보관한다.
- `RESERVATION 1:N PAYMENT`: 하나의 예약에는 최초·추가 참여 결제가 여러 건 연결될 수 있다. `CREATE` READY 결제는 예약 생성 전에는 NULL이다.
- `RESERVATION_PARTICIPANT 1:0..1 PAYMENT`: 참여자 한 건은 본인 결제 한 건과 연결된다. 결제 완료 전 참여자가 없으므로 Payment 쪽 참조 컬럼(`reservation_participant_id`, 물리 FK 아님)을 NULL 허용으로 둔다.
- `PAYMENT 1:0..1 REFUND`: 결제 전체 환불과 재시도 상태를 한 환불 행으로 관리한다.
- `RESERVATION 1:0..1 CHAT_ROOM`, `CHAT_ROOM 1:N CHAT_MESSAGE`: 예약당 하나의 채팅방과 여러 메시지다.
- `MEMBER 1:N CHAT_MESSAGE`: 발신 회원을 추적한다. `sender_participant_id`는 해당 예약의 유효 참여자 여부를 검증한다.
- `CHAT_MESSAGE 1:0..1 CHAT_MODERATION`: 원문 메시지 하나에 분석 결과·최종 실패 기록을 하나만 둔다. 완료 상태를 최종 실패가 덮지 않도록 `chat_moderation.version`으로 낙관적 락을 적용한다.
- `CHAT_MESSAGE`와 `RESTAURANT_FEEDBACK_ANALYSIS`: 물리 FK가 아닌 값 참조이며 1:N이다(같은 `chat_message_id`가 서로 다른 `prompt_version`으로 여러 건 공존 가능). `RESTAURANT_FEEDBACK_ANALYSIS 1:N RESTAURANT_FEEDBACK_ITEM`: `RestaurantFeedbackItem.analysis`의 JPA `@ManyToOne/@JoinColumn`으로 매핑되며, 메시지 하나에서 0..N개의 의견이 나올 수 있다. live DB physical FK constraint 존재 여부는 별도 DDL 확인 항목이다.

## 6. UNIQUE 및 정합성 제약

| 대상 | 제약 | DB·애플리케이션 책임 |
|---|---|---|
| `member.email` | 이메일 중복 금지 | DB UNIQUE |
| `member.phone_number` | 전화번호 중복 금지 | DB UNIQUE |
| `member.business_number` | 사업자등록번호 중복 금지 | DB UNIQUE. MEMBER의 NULL은 중복 허용, 값이 있으면 중복 금지 |
| `restaurant.owner_member_id` | 소유자는 OWNER여야 함 | 값 참조(물리 FK 아님) + 애플리케이션 역할 검증 |
| 활성 `time_slot` | 동일 테이블·동일 시작 시각 활성 회차 중복 금지 | `deleted_at IS NULL`인 회차만 중복 금지. 삭제된 회차와 같은 시작 시각은 재생성 가능. `active_start_at` generated column + `UNIQUE(shared_table_id, active_start_at)`로 DB에서 활성 회차 중복을 강제 |
| 활성 `reservation.time_slot_id` | 회차당 활성 합석 예약 1건 | DB 단순 UNIQUE로 보장하지 않는다. TimeSlot 행 비관적 락과 `RECRUITING`·`CONFIRMED` Reservation 조회를 같은 트랜잭션에서 수행; `CANCELLED` 이력은 유지 |
| 유효 CREATE READY Payment | 회차당 최초 예약 결제 준비 1건 | TimeSlot 행 잠금 뒤 만료되지 않은 `payment_purpose=CREATE`, `payment_status=READY` Payment를 조회; 있으면 `ACTIVE_RESERVATION_ALREADY_EXISTS` |
| `reservation_participant` | 같은 회원의 같은 예약 중복 참여 금지 | `(reservation_id, member_id)` UNIQUE |
| `chat_room.reservation_id` | 예약당 채팅방 1개 | DB UNIQUE |
| `chat_moderation.chat_message_id` | 메시지당 분석 기록 1건 | DB UNIQUE는 INSERT 멱등성, JPA `@Version`은 stale UPDATE 방지 |
| `restaurant_feedback_analysis(chat_message_id, prompt_version)` | 동일 메시지·동일 Prompt 버전 결과 1건, 버전별 결과는 공존 | DB UNIQUE `uk_feedback_analysis_message_prompt`는 동일 Kafka Event 재전달·동시 처리 경쟁 시 중복 INSERT를 막아 최종 저장 결과가 1건으로 수렴하게 한다. Provider 동시 중복 호출 자체는 막지 않는다. |
| `restaurant_feedback_item(restaurant_feedback_analysis_id, category, aspect_type, normalized_aspect, opinion_type, sentiment)` | 동일 Analysis 안에서 5-field 조합 Item 중복 저장 금지 | DB UNIQUE `uk_feedback_item_analysis_key` |
| `payment.payment_id` | Payment 내부 식별자 | PK, AUTO_INCREMENT |
| `payment.portone_payment_id` | PortOne 외부 결제 식별자 중복 금지 | DB UNIQUE + 상태 전이 멱등 처리 |
| `payment.reservation_participant_id` | 참여자와 결제의 1:1 연결 | NULL 허용 UNIQUE |
| `refund.payment_id` | 같은 결제의 중복 환불 요청 방지 | DB UNIQUE; 재시도는 상태 전이 |
| `shared_table.capacity` | 허용 정원은 2·4·6·8 | DB CHECK 후보 + API 검증 |
| `party_size` | 최소 1명 | DB CHECK 후보 + API 검증 |
| CREATE partySize | 테이블 정원 이하 | 동적 규칙이므로 트랜잭션 내 애플리케이션 검증 |
| JOIN partySize | availableCapacity 이하 | 동적 규칙이므로 임시 선점·PAID 합계 조회와 트랜잭션 검증 |

정원 초과 방지는 단순 CHECK로 막을 수 없다. 같은 회차의 `PAID` 참여 인원과 만료 전 `READY` 결제 인원을 트랜잭션 안에서 다시 검증해야 한다. MySQL 부분 UNIQUE 인덱스를 전제하지 않으므로, TimeSlot의 활성 Reservation 최대 1건과 유효 CREATE READY 최대 1건은 DB 단순 UNIQUE가 아니라 TimeSlot 행 비관적 락, 활성 Reservation 조회, 유효 CREATE READY 조회로 막는다.

동일 TimeSlot에 동시에 여러 CREATE 요청을 보내는 구현 테스트에서 활성 Reservation 또는 유효 CREATE READY의 성공은 최대 1건이어야 한다. 나머지 요청은 `ACTIVE_RESERVATION_ALREADY_EXISTS`를 반환한다. JOIN READY 생성과 `availableCapacity` 계산도 같은 TimeSlot 잠금 경계에서 수행한다.

## 7. 저장값과 계산값 구분

| 값 | 구분 | 산출 또는 저장 근거 |
|---|---|---|
| `currentParticipantCount` | 계산값 | `PAID` 결제와 연결된 유효 ReservationParticipant의 `party_size` 합계 |
| `temporaryHeldCount` | 계산값 | `READY`이며 `expires_at`이 현재보다 이후인 Payment의 `party_size` 합계 |
| `availableCapacity` | 계산값 | `shared_table.capacity - currentParticipantCount - temporaryHeldCount` |
| `confirmationThreshold` | 계산값 | 정원 `2→2`, `4→3`, `6→5`, `8→7` |
| `payableAmount` | 계산값 | 식당 또는 예약의 `paid_at`이 존재하는 Payment 금액 합계에서 `COMPLETED` Refund 금액 합계 차감 |
| `expectedSettlementAmount`, `expectedAmount` | 계산값 | `paid_at`이 존재하는 결제 완료 이력 금액 합계에서 환불 완료 금액 합계를 차감 |
| `totalPaidAmount`, `totalRefundedAmount` | 계산값 | 기간·식당·예약 조건에 맞는 Payment·Refund 금액 합계 |
| `imageUrl` | 계산값 | `restaurant.image_key`가 있을 때 S3 Presigned GET URL로 생성 |
| `noShowCount` | 계산값 | 회원의 `participation_status=NO_SHOW` 참여 건수 |
| `noShowRate` | 계산값 | 전체 참여 횟수 대비 노쇼 건수 비율 |
| `reservationConfirmationRate`, `confirmationRate` | 계산값 | 전체 예약 수 대비 확정 예약 수 비율 |
| `totalReservationCount`, `confirmedReservationCount`, `reservationCount`, `refundCount` | 계산값 | 조건에 맞는 예약·환불 건수 집계 |
| `profanityCount`, `personalInformationCount`, `spamCount` | 계산값 | `FLAGGED` 메시지를 발신 회원별·category별로 `COUNT(DISTINCT messageId)` 집계. 하나의 메시지가 복수 category여도 각 category 집계에는 포함한다. |
| `totalFlaggedCount` | 계산값 | 발신 회원의 `LOW`/`MEDIUM`/`HIGH` `FLAGGED` 메시지를 `COUNT(DISTINCT messageId)` 집계. `LOW`도 포함한다. |
| `reviewTargetCount` | 계산값 | 발신 회원의 `MEDIUM`/`HIGH` `FLAGGED` 메시지만 `COUNT(DISTINCT messageId)` 집계한다. `LOW`는 제외한다. |
| `reviewStatus` | 계산값 | `reviewTargetCount >= 3`이면 `REVIEW_REQUIRED`, 아니면 `NORMAL`. `REVIEW_REQUIRED`는 DB 저장 상태·자동 제재·BAN·Review Case가 아닌 조회 시 계산되는 관리자 검토 후보다. |
| `party_size`, `amount`, `expires_at`, `restaurant.image_key`, 상태값 | 저장값 | 결제·참여 이력, 식당 이미지 Object Key, 임시 선점·환불·정산 조회의 원천 데이터 |

회원 moderation 집계에서는 `SAFE`와 `ANALYSIS_FAILED`를 제외한다. 위 집계값은 API 응답에 포함되더라도 중복 컬럼으로 저장하지 않으며, `MemberModerationSummary`, `ReviewCase`, Redis Counter 같은 별도 영속 모델도 현재 범위에 추가하지 않는다. 성능·동시성 문제로 별도 저장이 필요해지면 갱신 책임과 정합성 전략을 별도 결정해야 한다.

## 8. 상태 Enum

| 구분 | 애플리케이션 Enum 값 | 비고 |
|---|---|---|
| 회원 역할 | `MEMBER`, `OWNER`, `ADMIN` | `member.role` |
| 식당 상태 | `ACTIVE` | 생성 시 서버 적용, 상태 변경 API 없음 |
| 테이블 상태 | `ACTIVE` | 생성 시 서버 적용, 상태 변경 API 없음 |
| 예약 상태 | `RECRUITING`, `CONFIRMED`, `CANCELLING`, `CANCELLED`, `CLOSED` | `reservation.reservation_status`; `CANCELLING`은 취소 접수 후 외부 환불 완료를 기다리는 중간 상태(#44) |
| 모집 상태 | `OPEN`, `CLOSED` | `reservation.recruitment_status` |
| 참여자 상태 | `RESERVED`, `NO_SHOW`, `CANCEL_REQUESTED`, `CANCELLED` | `reservation_participant.participation_status`; `CANCEL_REQUESTED`는 취소 접수 후 본인 환불 완료를 기다리는 중간 상태(#44) |
| 결제 상태 | `READY`, `PAID`, `FAILED`, `EXPIRED`, `REFUNDED` | `payment.payment_status`; `REFUNDED`는 Payment 전체 환불 완료 |
| 환불 상태 | `REQUESTED`, `PROCESSING`, `COMPLETED`, `FAILED` | `refund.refund_status`; 결과 불명확은 `REQUESTED` 유지로 표현하며 `UNKNOWN`은 도입하지 않는다(2026-08-05 Human 확정, #44) |
| 식당 피드백 Analysis 상태 | `COMPLETED`, `EXCLUDED_INPUT_PII`, `EXCLUDED_CANDIDATE`, `EXCLUDED_OUTPUT_VALIDATION` | `restaurant_feedback_analysis.status`; `COMPLETED`만 Item을 가지며 나머지 3개는 Item 0개인 terminal 제외 상태(#277) |
| 식당 피드백 category | `FOOD`, `SERVICE`, `PRICE`, `CLEANLINESS`, `ETC` | `restaurant_feedback_item.category` |
| 식당 피드백 aspectType | `MENU`, `SERVICE`, `PRICE`, `CLEANLINESS`, `ETC` | `restaurant_feedback_item.aspect_type` |
| 식당 피드백 opinionType | `TASTE`, `TEXTURE`, `SALTINESS`, `SPICINESS`, `SWEETNESS`, `PORTION`, `FRESHNESS`, `TEMPERATURE`, `FRIENDLINESS`, `SERVICE_SPEED`, `PRICE_LEVEL`, `CLEANLINESS`, `WAITING`, `ETC` | `restaurant_feedback_item.opinion_type` |
| 식당 피드백 sentiment | `POSITIVE`, `NEGATIVE`, `NEUTRAL` | `restaurant_feedback_item.sentiment` |

`no_show_history.is_marked`는 상태 Enum이 아니라 처리·해제 이력을 구분하는 boolean 값이다. `TRUE`는 노쇼 처리, `FALSE`는 노쇼 해제를 뜻한다.

## 9. 주요 트랜잭션과 ERD 연결

### 예약 결제 준비와 활성 예약 확인

1. 대상 `time_slot` 행을 비관적 락으로 조회하고 트랜잭션 종료까지 잠금을 유지한다.
2. `RECRUITING` 또는 `CONFIRMED` Reservation 존재 여부를 확인한다. CREATE의 활성 Reservation이 있으면 `ACTIVE_RESERVATION_ALREADY_EXISTS`로 거절한다.
3. CREATE면 만료되지 않은 `payment_purpose=CREATE`, `payment_status=READY` Payment 존재 여부를 확인한다. 있으면 `ACTIVE_RESERVATION_ALREADY_EXISTS`로 거절한다. JOIN의 READY Payment 생성과 `availableCapacity` 계산은 같은 잠금 경계에서 별도 처리한다.
4. `shared_table.capacity`와 만료 전 READY·PAID 데이터를 사용해 `availableCapacity`를 계산하고 CREATE `partySize`를 검증한다.
5. `payment`에 Member, TimeSlot, `party_size`, 금액, 통화, `payment_purpose=CREATE`, `payment_status=READY`, `expires_at`을 저장한다.
6. 이 시점에는 `reservation_id`, `reservation_participant_id`가 NULL이다. 만료 READY는 좌석 집계에서 `expires_at` 기준으로 즉시 제외하고 스케줄러가 `EXPIRED`로 정규화한다.

### 최초 예약 결제 완료

1. 인증 회원과 `payment.member_id`를 비교하고 PortOne 결제 정보·금액·통화를 검증한다.
2. Payment를 멱등하게 `PAID`로 전환한다.
3. `reservation`, 최초 `reservation_participant`, ChatRoom 생성용 `outbox_event(PENDING)`을 같은 트랜잭션에 저장하고 Payment의 비어 있던 참조 컬럼(`reservation_id`, `reservation_participant_id`, 물리 FK 아님)을 연결한다.
4. 커밋 뒤 즉시 signal 또는 scheduler가 Outbox를 claim해 별도 트랜잭션에서 `chat_room`을 `reservation_id` 기준 멱등 생성한다. 최초 처리 실패 뒤 5·10·20·40·80초 backoff로 5회 재시도하고, 다음 실패에서 `FAILED`로 남긴다. 5분 stale `PROCESSING`은 `PENDING`으로 회수한다.
5. 결제 완료 인원으로 예약·모집 상태를 계산한다.

### 추가 참여 결제 완료

1. 기존 Reservation과 TimeSlot을 기준으로 남은 정원을 다시 검증한다.
2. JOIN Payment를 `PAID`로 전환하고 `reservation_participant`를 생성한 뒤 연결한다.
3. 참여 인원·확정 기준·정원을 기준으로 예약·모집 상태를 재계산한다.

### MEMBER 취소·환불

1. 인증 MEMBER의 `reservation_participant.member_id`를 확인하고, 서버 시간 기준 `time_slot.start_at` 2시간 전까지이며 참여 상태가 `RESERVED`인지 검증한다. 부분 `party_size` 취소는 허용하지 않는다.
2. **접수(짧은 트랜잭션)**: 추가 참여자 취소는 해당 `reservation_participant`를 `CANCEL_REQUESTED`로, 최초 예약자 취소는 Reservation을 `CANCELLING`으로 전환하고 유효 Participant를 모두 `CANCEL_REQUESTED`로 전환해 커밋한다(#44). 이 시점에는 아직 `CANCELLED`가 아니며, 좌석은 계속 점유한 것으로 계산한다.
3. **외부 환불 실행(트랜잭션 밖)**: 접수 커밋·Reservation 락 해제 뒤 참여자별로 `refund.payment_id` UNIQUE 제약으로 중복을 막으며 Refund를 생성하고 PortOne 환불을 요청한다(#45).
4. **완료 확정(짧은 트랜잭션)**: 환불이 완료되면 해당 `reservation_participant`만 `CANCEL_REQUESTED → CANCELLED`로 조건부 전이한다. 남은 `CANCEL_REQUESTED`가 없으면 Reservation을 `CANCELLED`로 확정하고, 추가 참여자 단건 취소면 `currentParticipantCount`·`availableCapacity`를 재계산해 확정 기준 미달 시 `RECRUITING`, 기준 이상이면 `CONFIRMED`를 유지한다. `CONFIRMED + CLOSED` 수동 마감 예약은 기준 이상이면 그대로 유지하고, 기준 미달이면 남은 유효 Participant를 마저 취소 접수한다. CLOSED 모집을 다시 OPEN하지 않는다.
5. 최초 예약자 취소로 Reservation이 `CANCELLED`로 확정되면, 현재 시간이 식사 시작 2시간 전보다 이전이고 다른 활성 Reservation 및 OWNER·시스템 사용 제한이 없을 때만 TimeSlot에 새 Reservation을 생성할 수 있다. Reservation과 Payment·Refund 행은 보존한다.
6. Reservation이 `CANCELLING`·`CANCELLED` 또는 `CLOSED`이면 ChatRoom 신규 메시지 전송은 종료되고 기존 ChatMessage는 유지한다. OWNER·시스템 귀책 및 모집 실패 전체 취소도 동일한 접수·외부 실행·완료 확정과 Payment·Refund·이력 보존 원칙을 따른다.

### 노쇼

OWNER 소유권을 확인한 뒤 참여자 상태를 변경하고 `no_show_history`에 처리·해제 이력을 남긴다. `NO_SHOW` 이후 MEMBER 취소는 허용하지 않는다.

## 10. 인덱스

### 현재 구현된 Index

| 대상 | 실제 Index | 정의 위치·조회 근거 |
|---|---|---|
| `shared_table` | `idx_shared_table_restaurant_id(restaurant_id)` | `SharedTable.@Table`; 식당 조건 테이블 조회와 식당 검색 date·time 3-way join |
| `payment` | `idx_payment_status_expires_at_id(payment_status, expires_at, payment_id)` | `Payment.@Table`; READY 만료 후보의 상태·시각·내부 PK 정렬 |
| `chat_message` | `idx_chat_message_room_id(chat_room_id, chat_message_id)` | `ChatMessage.@Table`; room별 messageId cursor 조회 |
| `outbox_event` | `idx_outbox_event_status_next_attempt(status, next_attempt_at, outbox_event_id)` | `OutboxEvent.@Table`; 처리 가능·stale Outbox 후보 조회 |
| `email_outbox_delivery` | `idx_email_outbox_delivery_event_status(outbox_event_id, status)` | `EmailOutboxDelivery.@Table`; 이벤트별 미발송 수신자 조회 |
| `restaurant_feedback_analysis` | `idx_feedback_analysis_restaurant_prompt(restaurant_id, prompt_version, analyzed_at)` | `RestaurantFeedbackInsight.@Table`; OWNER 최근 7일·`activePromptVersion` 필터 조회 |
| `restaurant_feedback_item` | `idx_feedback_item_aggregation(category, aspect_type, normalized_aspect, opinion_type, sentiment)` | `RestaurantFeedbackItem.@Table`; OWNER 5-field 집계 `group by`/`having` 조회 |

아래 표는 실제 정의가 아닌 **후보**다. UNIQUE 제약으로 생성되는 인덱스는 각 엔티티 상세 표와 섹션 6을 기준으로 한다.

### 인덱스 후보

| 대상 | 후보 | API·조회 근거 |
|---|---|---|
| `member` | `UNIQUE(email)`, `UNIQUE(phone_number)`, `UNIQUE(business_number)` | 로그인·이메일·전화번호·사업자등록번호 중복 검증 |
| `restaurant` | `(owner_member_id)` | 내 식당 목록·소유권 확인 |
| `shared_table` | `(restaurant_id)`, `UNIQUE(restaurant_id, display_number)` | 식당별 테이블 조회·표시 번호 중복 방지 |
| `time_slot` | `(shared_table_id, start_at, deleted_at)`, `UNIQUE(shared_table_id, active_start_at)` | 회차 조회·활성 중복 방지. 삭제 후 같은 시간 재생성을 허용하므로 단순 `UNIQUE(shared_table_id, start_at)`는 사용하지 않음 |
| `reservation` | `(time_slot_id, reservation_status)`, `(reservation_status, recruitment_status)` | 활성 Reservation 조회·모집 예약 검색; `time_slot_id` 단순 UNIQUE 미사용 |
| `reservation_participant` | `UNIQUE(reservation_id, member_id)`, `(member_id)` | 중복 참여 방지·내 예약 조회 |
| `payment` | `UNIQUE(portone_payment_id)`, `(member_id, payment_status)`, `(time_slot_id, payment_status, expires_at)`, `(payment_status, expires_at, payment_id)` | 외부 결제 식별자 조회·임시 선점 계산·만료 후보의 상태/시각/내부 PK 정렬 |
| `refund` | `UNIQUE(payment_id)`, `(refund_status)` | 중복 환불 방지·실패 환불 조회 |
| `chat_message` | `(chat_room_id, chat_message_id)` | messageId cursor 기반 과거 메시지 조회 |
| `chat_moderation` | `UNIQUE(chat_message_id)` | 메시지별 분석 기록 중복 생성 방지 |

`chat_message(chat_room_id, created_at)`는 createdAt cursor 방식을 채택할 때만 추가 검토한다. 검색·통계용 복합 인덱스는 실제 API 조회량과 실행 계획 측정 전에는 확정하지 않는다.

`shared_table (restaurant_id)`는 Issue #61에서 `idx_shared_table_restaurant_id`로 구현했다. `GET /api/restaurants`의 date·time 필터(3-way join)가 이 인덱스 없이 매 요청마다 `shared_table` 전체를 스캔하는 것을 실제 MySQL EXPLAIN ANALYZE로 확인한 뒤(docs/110-records/evidence/v3/61-search-query/README.md) 추가했다.

위 표의 `payment (payment_status, expires_at, payment_id)`와 `chat_message (chat_room_id, chat_message_id)`는 후보가 아니라 각각 `idx_payment_status_expires_at_id`, `idx_chat_message_room_id`로 이미 구현된 실제 Index다(섹션 4.7, 4.11 상세 표 참고). 같은 행의 나머지 항목(`payment`의 `(member_id, payment_status)`, `(time_slot_id, payment_status, expires_at)` 등)은 아직 도입하지 않은 순수 후보다.

## 11. 삭제와 이력 보존 정책

| 대상 | 처리 방향 | 근거·보류 |
|---|---|---|
| 회원 | `deleted_at` 기반 소프트 삭제 | 탈퇴 후에도 `email`, `phone_number`, `business_number` 재사용 불가. 고유 식별자 값 보존 |
| 식당·테이블·회차 | `deleted_at` 기반 소프트 삭제 방향 | 예약·결제·노쇼 이력 보존. 회차는 삭제 후 같은 테이블·시작 시각으로 신규 생성 가능 |
| 예약·참여자 | 물리 삭제하지 않고 상태 보존 | 취소·노쇼·정산·권한 이력 필요 |
| 결제·환불 | 물리 삭제하지 않음 | PortOne 검증·환불·지급 예정 조회·감사 필요 |
| 채팅 메시지 | 물리 삭제 정책 Human 결정 필요 | 보관 기간과 CLOSED 후 조회 기간은 Human 결정 필요 |

모든 테이블에 소프트 삭제 컬럼을 일괄 추가하지 않는다. 채팅·결제·환불은 현재 기준 문서에 삭제 API가 없다.

## 12. 보류 및 Human 결정 필요

| 항목 | 현재 모델 | 대안과 영향 |
|---|---|---|
| 최초 예약자 구분 방식 | `reservation.creator_member_id`로 판별하고 Participant 역할 Enum은 저장하지 않음 | 별도 역할 컬럼을 추가하면 creator와 불일치 방지 책임이 생김 |
| 웹훅 원문 이력 | `WebhookEvent` 미생성 | Payment 외부 식별자 UNIQUE와 상태 검증으로 멱등 처리; 원문 감사가 필요하면 후속 별도 테이블 검토 |
| 환불 재시도 이력 | Refund 1건의 상태 전이 | 시도별 감사가 필요하면 별도 이력 테이블 검토 |
| 채팅 보관 기간 | `chat_message` 삭제 시점 미정 | 기간·CLOSED 이후 조회 기간 결정 필요 |
| 동일 사용자의 동일 시간대 다른 예약 제한 | 제약 미설정 | 시간 겹침 제한 정책이 기준 문서에 없음 |
| 합석 회차 상태 변경 API | TimeSlot 상태 Enum 미추가 | 보류된 `PATCH /api/owner/dining-sessions/{sessionId}/status` 필요성 결정 후 반영 |

## 13. API, PROJECT_CONTEXT, ERD 3자 교차검증 결과

| 검증 항목 | 결과 | ERD 근거 |
|---|---|---|
| API 주요 식별자 | 충족 | Member, Restaurant, SharedTable, TimeSlot, Reservation, Payment, Refund, ChatRoom 식별자 존재 |
| Request 저장 필드 | 충족 | `partySize`, `capacity`, 회차 시간, 결제 금액·통화·만료 시각·상태를 각각 저장 |
| 응답 계산값 | 충족 | PAID/READY Payment와 Participation, SharedTable 정원으로 계산 |
| 역할·소유권 | 충족 | `Restaurant.ownerMemberId`, `Payment.memberId`, `Reservation.creatorMemberId` 값 참조(물리 FK 아님) |
| 예약·모집·참여 상태 | 충족 | Reservation과 ReservationParticipant의 별도 상태 컬럼 |
| READY 임시 선점 | 충족 | Payment의 READY·expires_at·party_size; SeatHold 미사용 |
| 환불·지급 예정 | 충족 | Payment 1:0..1 Refund와 PAID/COMPLETED 집계 |
| 정원 초과 방지 | 충족 | 원천 데이터와 트랜잭션 내 동적 검증 규칙 문서화 |
| TimeSlot 활성 예약 1건 | 충족 | TimeSlot 1:N 이력, 단순 UNIQUE 미사용, 행 잠금과 활성 Reservation 조회 |
| 채팅방·cursor 조회 | 충족 | ChatRoom reservation UNIQUE, ChatMessage PK cursor 인덱스 후보 |
| 기준 문서 외 기술 | 충족 | Settlement, SeatHold, WebhookEvent, Redis, Kafka를 확정 엔티티로 추가하지 않음 |

기준 문서와 ERD의 핵심 데이터 계약 충돌은 없다. 12장의 항목은 기준 문서에서 후속 결정으로 남겨둔 설계 선택이며, 현재 V1·V2·V3 모델 구현을 막는 충돌로 분류하지 않는다.
