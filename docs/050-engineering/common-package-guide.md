# BobFull common package 가이드

이 문서는 `com.bobfull.common` package가 제공하는 공통 기반과 각 domain에서 사용하는 방법을 설명한다.
프로젝트 전체 package 구조는 [project-structure.md](../040-architecture/project-structure.md), naming·Annotation·Formatting 규칙은 [code-convention.md](code-convention.md)를 따른다.

common은 여러 기능이 실제로 공유하는 최소 기반만 소유한다. 특정 feature의 Controller, Service, DTO, Policy, Processor, Scheduler나 Adapter 작성 규칙을 이 문서에서 다시 정의하지 않는다.

## 1. 현재 common 구성

현재 common은 다음 공통 기반으로 구성된다.

| Package | 제공 기반 | 현재 class | 사용하는 쪽 |
|---|---|---|---|
| `common.response` | 공통 API·paging 응답 | `ApiResponse`, `PageResponse` | Controller, security 응답 handler |
| `common.exception` | 오류 계약과 전역 HTTP 예외 변환 | `BaseErrorCode`, `CommonErrorCode`, `CustomException`, `GlobalExceptionHandler` | Domain ErrorCode, Service, Controller 경계 |
| `common.config` | UTC Clock과 JPA Auditing 설정 | `ClockConfig`, `JpaAuditingConfig` | 시간 의존 Service·Scheduler, JPA Auditing |
| `common.entity` | 생성·수정 시각 기반 | `BaseTimeEntity` | auditing이 필요한 domain·기술 Entity |
| `common.privacy` | 여러 기능이 공유하는 이름 masking | `MemberNameMasker` | 노쇼·회원 이름 노출 Response |
| `common.monitoring` | 제한된 business metric event와 recorder | `BusinessMetricEvent`, `BusinessMetricRecorder` | Application·Infrastructure 장애/상태 관측 지점 |
| `common.transaction` | transaction commit 이후 실행 | `AfterCommitExecutor` | 후속 signal·비동기 실행을 예약하는 Service |
| `common.outbox.entity` | 공유 Outbox 영속 상태 | `OutboxEvent`, `OutboxEventStatus`, `OutboxEventType` | ChatRoom·ChatMessage·Email 후속 처리 |
| `common.outbox.repository` | 공유 Outbox 조회·상태 갱신 | `OutboxEventRepository` | 공통 Outbox transaction service |
| `common.outbox.service` | 짧은 독립 transaction의 claim·완료·실패·복구 | `OutboxEventTransactionService` | 기능별 Outbox Processor |

현재 common에는 `security` package가 없다. 인증 principal인 `AuthMember`는 `auth.application.model`, Security 설정·filter·handler는 `auth.infrastructure.security` 소유다.

## 2. 공통 응답

### `ApiResponse<T>`

Controller의 성공 응답은 `ApiResponse.success(data)`로 감싼다.

```java
@GetMapping("/{reservationId}")
public ApiResponse<ReservationResponse> getReservation(
        @PathVariable Long reservationId
) {
    ReservationResult result = reservationQueryService.getReservation(reservationId);

    return ApiResponse.success(ReservationResponse.from(result));
}
```

실패 응답은 직접 만들지 않는다. `CustomException`이 가진 `BaseErrorCode`를 `GlobalExceptionHandler`가 받아 HTTP status와 `ApiResponse.fail(errorCode)`로 변환한다. 인증·인가 filter 단계의 실패는 auth infrastructure의 security handler가 같은 응답 형식을 사용한다.

### `PageResponse<T>`

Spring Data `Page<T>`를 API paging 계약으로 변환할 때 `PageResponse.from(page)`를 사용한다.

```java
Page<ReservationResult> results = reservationQueryService.getReservations(pageable);
Page<ReservationResponse> responses = results.map(ReservationResponse::from);

return ApiResponse.success(PageResponse.from(responses));
```

`PageResponse`는 `content`, `page`, `size`, `totalElements`, `totalPages`를 제공한다. paging 필드 의미는 API 명세를 따른다.

## 3. 공통 예외 기반

### `BaseErrorCode`와 domain ErrorCode

`BaseErrorCode`는 모든 domain ErrorCode가 제공할 HTTP status, code와 message의 최소 계약이다. Domain 전용 오류는 해당 기능의 `domain/exception`에 별도 `*ErrorCode` enum으로 둔다.

```java
@Getter
@RequiredArgsConstructor
public enum RestaurantErrorCode implements BaseErrorCode {

    RESTAURANT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "식당을 찾을 수 없습니다."
    );

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
```

Issue #15 기준에 따라 ErrorCode는 `@Getter`, `@RequiredArgsConstructor`, `httpStatus`와 `message` field를 사용하며 `getCode()`만 명시 구현한다.

### `CommonErrorCode`

특정 domain에 속하지 않는 전역 오류만 `CommonErrorCode`에 둔다.

- `INVALID_INPUT_VALUE`
- `UNAUTHORIZED`
- `ACCESS_DENIED`
- `INTERNAL_SERVER_ERROR`

Domain 전용 오류를 편의상 `CommonErrorCode`에 추가하지 않는다.

### `CustomException`과 `GlobalExceptionHandler`

사용자에게 전달할 business 실패는 해당 ErrorCode를 `CustomException`에 담아 던진다.

```java
throw new CustomException(RestaurantErrorCode.RESTAURANT_NOT_FOUND);
```

`GlobalExceptionHandler`는 다음 HTTP 경계 실패를 공통 응답으로 변환한다.

- `CustomException`
- Request Body Bean Validation 실패
- 필수 Request parameter 누락과 type mismatch
- 처리되지 않은 예외

외부 시스템, 직렬화, retry/DLT 등 내부 제어에 필요한 기술 예외는 무조건 `CustomException`으로 바꾸지 않는다. 상세 기준은 `code-convention.md`의 Exception 규칙을 따른다.

## 4. UTC Clock과 JPA Auditing

### `ClockConfig`

`ClockConfig`는 UTC `Clock` bean을 제공한다. 현재 시각에 의존하는 Service, Scheduler와 Provider는 system clock을 직접 호출하지 않고 `Clock`을 주입받는다.

```java
@Service
@RequiredArgsConstructor
public class ReservationClosingService {

    private final Clock clock;

    public void closeDueReservations() {
        Instant now = clock.instant();
    }
}
```

### `JpaAuditingConfig`와 `BaseTimeEntity`

`JpaAuditingConfig`는 주입된 UTC `Clock`을 JPA `DateTimeProvider`로 연결한다. 생성·수정 시각이 필요한 Entity는 `BaseTimeEntity`를 상속해 `createdAt`, `updatedAt`을 `Instant`로 기록한다.

```java
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {
}
```

`Instant`는 API 표시 timezone으로 자동 변환되지 않는다. API 계약이 `Asia/Seoul` 시각을 요구하면 Response에서 명시적으로 변환한다.

```java
public record ReservationResponse(Long id, OffsetDateTime createdAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getCreatedAt()
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toOffsetDateTime()
        );
    }
}
```

시간 저장·변환의 결정 근거는 [ADR-0003](../040-architecture/adr/0003-utc-instant-and-clock.md)을 따른다.

## 5. 개인정보 masking

`MemberNameMasker`는 여러 기능의 노쇼 통계·이력 Response에서 회원 이름을 같은 방식으로 가릴 때 사용한다.

```java
String maskedName = MemberNameMasker.mask(member.getName());
```

`null`과 한 글자 이름은 그대로 반환하고, 두 글자 이상은 가운데 글자를 `○`로 바꾼다. 특정 기능에만 필요한 개인정보 전처리나 AI 입력 정책까지 common으로 이동하지 않는다.

## 6. Business metric

`BusinessMetricEvent`는 Prometheus의 `bobfull_business_events` counter에 사용하는 제한된 event 이름이다. 요청 ID나 사용자 ID처럼 계속 늘어나는 값을 metric label로 추가하지 않는다.

```java
businessMetricRecorder.increment(BusinessMetricEvent.PAYMENT_COMPLETED);
```

`BusinessMetricRecorder`는 event별 counter를 미리 준비하고 metric 기록 실패가 핵심 transaction 흐름에 영향을 주지 않도록 내부에서 처리한다. 기능 코드는 동일한 실패 처리 로직을 반복하지 않고 recorder를 호출한다.

새 event 추가는 실제 운영 지표와 alert 사용 여부를 확인하고 `BusinessMetricEvent`의 제한된 값으로 추가한다. dashboard와 alert 계약을 바꾸는 판단은 이 가이드가 대신하지 않는다.

## 7. Transaction commit 이후 실행

`AfterCommitExecutor.run(task)`는 활성 transaction synchronization이 있으면 commit 성공 뒤 task를 실행하고, 활성 synchronization이 없으면 즉시 실행한다.

```java
AfterCommitExecutor.run(() -> outboxSignalDispatcher.signal());
```

이 유틸리티는 transaction 경계나 비동기 실행 정책을 새로 만들지 않는다. task 실행 시점과 executor·장애 처리 방식은 호출하는 기능의 확정된 계약을 유지한다.

## 8. 공통 Outbox 기반

`common.outbox`는 여러 기능이 공유하는 Outbox 영속 상태와 짧은 상태 전이 transaction만 소유한다.

- `OutboxEvent`: 후속 처리 의도와 claim·retry 상태를 저장한다.
- `OutboxEventStatus`: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` 상태를 정의한다.
- `OutboxEventType`: ChatRoom 생성, ChatMessage 생성과 Email 알림 등 현재 공유 event 유형을 정의한다.
- `OutboxEventRepository`: due event 조회, claim, 완료, 실패와 stale processing 복구 query를 제공한다.
- `OutboxEventTransactionService`: `REQUIRES_NEW` transaction에서 claim·complete·fail·recover·manual retry를 수행한다.

기능별 Processor는 자신이 처리할 event type만 넘긴다.

```java
Optional<ClaimedOutboxEvent> claimedEvent = outboxEventTransactionService.claim(
        eventId,
        supportedEventTypes,
        clock.instant()
);
```

Chat/Email 전용 Processor, Scheduler, dispatcher와 payload 해석 책임은 각 기능 package에 남긴다. 공통 table을 사용한다는 이유로 기능별 delivery·retry 정책 전체를 common으로 이동하지 않는다.

## 9. common에 두지 않는 책임

다음은 common 구성요소가 아니다.

- `auth.application.model.AuthMember`와 auth security 설정·filter·handler
- 특정 feature의 Request, Response, Command, Result와 Model
- 특정 feature의 Controller, Service, Policy, Validator와 Processor
- 특정 외부 기술의 Port와 Adapter
- 기능별 Repository, QueryDSL query와 Scheduler·Consumer
- Domain 전용 ErrorCode와 business 상태 규칙
- Chat/Email 등 기능별 Outbox 처리와 payload 해석

여러 곳에서 사용된다는 이유만으로 common으로 이동하지 않는다. 독립적인 공통 책임과 안정된 계약이 있을 때만 common이 소유한다.
