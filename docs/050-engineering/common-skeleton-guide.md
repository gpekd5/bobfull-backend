# BobFull 공통 개발 골격

이 문서는 새 기능이나 새 domain을 만들 때 참고할 수 있는 코드 골격을 제공한다.
Naming, package, Annotation과 작성 규칙의 Source of Truth는 [code-convention.md](code-convention.md)다.
Repository 구조와 기능별 소유권 배경은 [project-structure.md](../040-architecture/project-structure.md)를 따른다.

여기 있는 package와 class는 예시다. 기능에 없는 책임의 빈 package를 만들거나, 예시를 이유로 기존 API·DB·transaction·의존 방향을 바꾸지 않는다.

## 1. 새 기능 package 골격

```text
com.bobfull.<feature>
├─ presentation
│  ├─ controller
│  │  └─ ReservationController.java
│  ├─ request
│  │  └─ ReservationPrepareRequest.java
│  └─ response
│     └─ ReservationPrepareResponse.java
├─ application
│  ├─ command
│  │  └─ PrepareReservationCommand.java
│  ├─ result
│  │  └─ ReservationPreparationResult.java
│  ├─ model
│  ├─ port
│  │  └─ ReadyPaymentPort.java
│  └─ service
│     └─ ReservationPreparationService.java
├─ domain
│  ├─ entity
│  │  └─ Reservation.java
│  ├─ exception
│  │  └─ ReservationErrorCode.java
│  └─ policy
└─ infrastructure
   ├─ payment
   │  └─ PortOneReadyPaymentAdapter.java
   └─ repository
      ├─ ReservationRepository.java
      └─ query
         ├─ ReservationSearchRepository.java
         └─ ReservationSearchRepositoryImpl.java
```

Application 경계 interface는 `*Port`, 구현체는 `*Adapter`를 사용한다. 복잡 조회와 QueryDSL은 `infrastructure/repository/query`에 두고 `CustomRepository`처럼 책임이 모호한 이름을 사용하지 않는다.

## 2. HTTP와 Application 전달 타입

### HTTP Request

```java
package com.bobfull.reservation.presentation.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationPrepareRequest(
        @NotNull Long timeSlotId,
        @NotNull @Positive Integer partySize
) {
}
```

### Application Command

```java
package com.bobfull.reservation.application.command;

public record PrepareReservationCommand(
        Long memberId,
        Long timeSlotId,
        Integer partySize
) {
}
```

### Application Result

```java
package com.bobfull.reservation.application.result;

public record ReservationPreparationResult(
        Long reservationId,
        String paymentKey
) {
}
```

### HTTP Response

```java
package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.application.result.ReservationPreparationResult;

public record ReservationPrepareResponse(
        Long reservationId,
        String paymentKey
) {

    public static ReservationPrepareResponse from(ReservationPreparationResult result) {
        return new ReservationPrepareResponse(
                result.reservationId(),
                result.paymentKey()
        );
    }
}
```

특정 use case 입출력에 종속되지 않는 Application 개념만 `application/model`에 둔다. 현재 인증 사용자는 `auth/application/model/AuthMember`가 대표 예시다. 전달 타입에는 `DTO` suffix를 붙이지 않고 `Request`, `Response`, `Command`, `Result` 책임을 이름에 표시한다.

## 3. Controller 골격

```java
package com.bobfull.reservation.presentation.controller;

import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.reservation.application.command.PrepareReservationCommand;
import com.bobfull.reservation.application.result.ReservationPreparationResult;
import com.bobfull.reservation.application.service.ReservationPreparationService;
import com.bobfull.reservation.presentation.request.ReservationPrepareRequest;
import com.bobfull.reservation.presentation.response.ReservationPrepareResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationPreparationService reservationPreparationService;

    @PostMapping
    public ApiResponse<ReservationPrepareResponse> prepare(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ReservationPrepareRequest request
    ) {
        PrepareReservationCommand command = new PrepareReservationCommand(
                authMember.id(),
                request.timeSlotId(),
                request.partySize()
        );
        ReservationPreparationResult result = reservationPreparationService.prepare(command);

        return ApiResponse.success(ReservationPrepareResponse.from(result));
    }
}
```

`@Validated`는 `@Positive @PathVariable`, `@Min @RequestParam` 같은 Controller method parameter validation이 실제로 있을 때만 class에 추가한다. 기존 `ApiResponse<T>`와 인증 계약을 그대로 사용한다.

## 4. Service와 Transaction 골격

쓰기 전용 Service는 class-level 기본 transaction을 사용할 수 있다.

```java
package com.bobfull.reservation.application.service;

import com.bobfull.reservation.application.command.PrepareReservationCommand;
import com.bobfull.reservation.application.port.ReadyPaymentPort;
import com.bobfull.reservation.application.result.ReservationPreparationResult;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReservationPreparationService {

    private final ReservationRepository reservationRepository;
    private final ReadyPaymentPort readyPaymentPort;

    public ReservationPreparationResult prepare(PrepareReservationCommand command) {
        Reservation reservation = Reservation.create(
                command.memberId(),
                command.timeSlotId(),
                command.partySize()
        );
        Reservation savedReservation = reservationRepository.save(reservation);
        ReservationPreparationResult result = readyPaymentPort.createReadyPayment(command);

        log.info(
                "event=RESERVATION_PREPARED reservationId={}",
                savedReservation.getId()
        );
        return result;
    }
}
```

조회 전용 Service는 다음 형태를 사용한다.

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyReservationQueryService {
}
```

읽기와 쓰기가 섞인 Service는 class-level 기본값을 두지 않고 method별로 `@Transactional(readOnly = true)`와 `@Transactional`을 구분한다. `REQUIRES_NEW`, `MANDATORY` 같은 propagation은 기존 동작상 이유가 있을 때만 사용한다.

## 5. Port와 Adapter 골격

```java
package com.bobfull.reservation.application.port;

import com.bobfull.reservation.application.command.PrepareReservationCommand;
import com.bobfull.reservation.application.result.ReservationPreparationResult;

public interface ReadyPaymentPort {

    ReservationPreparationResult createReadyPayment(PrepareReservationCommand command);
}
```

```java
package com.bobfull.reservation.infrastructure.payment;

import com.bobfull.reservation.application.command.PrepareReservationCommand;
import com.bobfull.reservation.application.port.ReadyPaymentPort;
import com.bobfull.reservation.application.result.ReservationPreparationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PortOneReadyPaymentAdapter implements ReadyPaymentPort {

    private final PortOneClient portOneClient;

    @Override
    public ReservationPreparationResult createReadyPayment(
            PrepareReservationCommand command
    ) {
        // 외부 기술 응답을 Application Result로 변환한다.
    }
}
```

Port는 Application이 요구하는 작은 책임 단위 interface다. `Reader`, `Requester`, `Hook` 등을 별도 suffix로 사용하지 않고 필요한 기능은 `ReadyPaymentPort`와 method 이름에 표현한다.

## 6. Entity 골격

```java
package com.bobfull.reservation.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    private Long memberId;

    private Long timeSlotId;

    private Integer partySize;

    private Reservation(Long memberId, Long timeSlotId, Integer partySize) {
        this.memberId = memberId;
        this.timeSlotId = timeSlotId;
        this.partySize = partySize;
    }

    public static Reservation create(Long memberId, Long timeSlotId, Integer partySize) {
        return new Reservation(memberId, timeSlotId, partySize);
    }

    public void cancel() {
        // invariant 확인 후 의미 있는 상태 변경을 수행한다.
    }
}
```

실제 `@Column`, association, enum, index와 unique constraint는 확정된 JPA mapping과 DB schema를 따른다. Entity에는 public Setter를 열지 않고 상태 변경 method를 사용한다.

## 7. Repository와 QueryDSL 골격

기본 Repository는 Spring Data JPA를 사용한다.

```java
package com.bobfull.reservation.infrastructure.repository;

import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.query.ReservationSearchRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, ReservationSearchRepository {
}
```

복잡 조회 계약과 구현은 `repository/query`에 둔다.

```java
package com.bobfull.reservation.infrastructure.repository.query;

import com.bobfull.reservation.application.model.ReservationSearchCondition;
import com.bobfull.reservation.application.result.ReservationSearchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReservationSearchRepository {

    Page<ReservationSearchResult> searchRecruitingReservations(
            ReservationSearchCondition condition,
            Pageable pageable
    );
}
```

```java
package com.bobfull.reservation.infrastructure.repository.query;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

public class ReservationSearchRepositoryImpl
        implements ReservationSearchRepository {

    private final JPAQueryFactory queryFactory;

    public ReservationSearchRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }
}
```

Spring Data가 fragment 구현을 등록하는 형태라면 `@Repository`를 기계적으로 붙이지 않는다. 독립 bean 등록 등 framework상 필요한 경우에만 사용한다. 단순 CRUD Repository까지 interface+Impl로 만들지 않는다.

## 8. ErrorCode와 공통 예외 골격

새 domain은 `BaseErrorCode`를 구현하는 별도 `*ErrorCode` enum을 둔다.

```java
package com.bobfull.reservation.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements BaseErrorCode {

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 예약입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
```

```java
throw new CustomException(ReservationErrorCode.ALREADY_CANCELLED);
```

도메인에 속하지 않는 공통 오류만 `CommonErrorCode`에 둔다. Bean Validation 실패와 처리되지 않은 예외는 기존 `GlobalExceptionHandler`와 `ApiResponse` 계약을 사용한다. 기술 실패는 사용자 비즈니스 오류와 구분된 기술 예외로 유지한다.

## 9. Scheduler, Consumer, Filter와 Interceptor 골격

```java
@Component
@RequiredArgsConstructor
public class PaymentExpirationScheduler {

    private final PaymentExpirationProcessor paymentExpirationProcessor;

    @Scheduled(cron = "...")
    public void expire() {
        paymentExpirationProcessor.process();
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class ChatModerationConsumer {

    private final ChatModerationProcessor chatModerationProcessor;

    @KafkaListener(topics = "...")
    public void consume(ChatMessageEvent event) {
        chatModerationProcessor.process(event);
    }
}
```

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
}

public class ChatStompInterceptor implements ChannelInterceptor {
}
```

`Scheduler`, `Consumer`, `Filter`, `Interceptor`는 위 framework 진입 역할을 실제로 수행할 때만 사용한다. 비즈니스 규칙 선별 객체는 `Policy`, `Validator`, `Gate` 등 실제 책임으로 명명한다.

## 10. 공통 보안과 시간 골격

Controller는 `auth.application.model.AuthMember`를 `@AuthenticationPrincipal`로 받고 클라이언트가 보낸 member id를 인증 정보로 신뢰하지 않는다. 인증 실패와 권한 부족은 기존 공통 security handler와 `ApiResponse` 형식을 사용한다.

DB·Entity의 절대 시점은 UTC `Instant`로 저장한다. API 응답 시각은 계약에 따라 `Asia/Seoul` 기준 `OffsetDateTime`으로 명시적으로 변환한다.

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

현재 시각이 필요한 객체는 system clock을 직접 호출하지 않고 `ClockConfig`가 제공하는 `Clock`을 constructor로 주입한다. 생성·수정 시각이 필요한 Entity는 `BaseTimeEntity`를 상속한다.

## 11. Builder, Setter, Logging과 Formatting 확인

- DTO는 record constructor를 기본으로 사용하고 Lombok을 붙이지 않는다.
- Entity public Setter를 사용하지 않고 의미 있는 domain method를 호출한다.
- Builder는 optional 값이 많거나 test fixture 등 명확한 예외가 있을 때만 사용한다.
- 의존성은 `final` field와 `@RequiredArgsConstructor`로 주입한다.
- 로그가 필요한 class만 `@Slf4j`를 사용한다.
- 로그는 `{}` placeholder와 `event=UPPER_SNAKE_CASE key={}` 형식을 기본으로 한다.
- 비밀번호, token과 민감 개인정보를 로그에 남기지 않는다.
- 들여쓰기 4칸, import 정리, 연속 빈 줄 제거, argument 단위 줄바꿈과 파일 끝 newline은 [code-convention.md](code-convention.md)의 Formatting 규칙을 따른다.

## 12. 적용 전 체크리스트

- Request/Response와 Command/Result가 각각 presentation/application 경계에 있는가?
- 재사용 Application 객체만 `model`에 있고 실제 책임명이 드러나는가?
- Application 외부 경계와 구현체가 `*Port`/`*Adapter`인가?
- 기본 Repository와 QueryDSL Repository가 분리되어 있는가?
- Service의 read/write 책임에 맞는 transaction 위치를 선택했는가?
- Entity, Controller, ErrorCode와 Logger가 제한적 Lombok 기준을 따르는가?
- public Setter와 기본 Builder 사용을 피했는가?
- framework 진입점 suffix가 실제 구현 역할과 일치하는가?
- API, DB, transaction 동작이나 의존 방향을 예시 때문에 바꾸지 않았는가?
