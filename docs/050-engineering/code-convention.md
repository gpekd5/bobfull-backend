# BobFull Code Convention

이 문서는 BobFull production Java 코드의 package, naming, Annotation과 작성 패턴을 정하는 Source of Truth다.
새 코드를 작성하거나 기존 코드의 표현을 정리할 때 먼저 이 문서를 따른다.

- Repository와 Java package의 구조적 배경·소유권은 [project-structure.md](../040-architecture/project-structure.md)를 따른다.
- API, DB, 정책, 권한과 트랜잭션의 동작 계약은 각 기준 문서를 따른다.
- Java 일반 스타일은 [NAVER CAMPUS HACKDAY Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)을 기본으로 하며, 충돌하면 이 문서의 BobFull 규칙을 우선한다.
- [common-package-guide.md](common-package-guide.md)는 `com.bobfull.common`이 제공하는 공통 기반과 사용 방법을 설명하며 이 규칙을 재정의하지 않는다.

모든 타입을 같은 형태로 만들지 않는다. 먼저 소유 domain, layer와 실제 책임을 확인하고 **동일한 책임에 동일한 이름과 작성 방식**을 적용한다. 책임 분리나 의존 방향 변경이 필요한 코드는 이름만 바꾸지 않고 Issue #20 범위로 남긴다.

## Quick Reference

| Layer | Package | 역할 | Naming | 기본 형태 / Annotation |
|---|---|---|---|---|
| Presentation | `presentation/controller` | HTTP 진입과 요청·응답 변환 | `*Controller` | `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor` |
| Presentation | `presentation/request` | 외부 HTTP 입력 계약 | `*Request` | `record`, Bean Validation |
| Presentation | `presentation/response` | 외부 HTTP 출력 계약 | `*Response` | `record` |
| Application | `application/service` | Use case 진입과 orchestration | `*Service`, `*QueryService`, `*CommandService`, `*TransactionService` | `@Service`, `@RequiredArgsConstructor`, 책임에 맞는 `@Transactional` |
| Application | `application/command` | 특정 Use case 입력 계약 | `*Command` | `record` |
| Application | `application/result` | 특정 Use case 출력 계약 | `*Result` | `record` |
| Application | `application/model` | Use case 입출력에 종속되지 않는 재사용 개념 | 실제 책임명 | 목적에 맞는 불변 타입 우선 |
| Application | `application/port` | Application이 요구하는 외부 경계 | `*Port` | 작은 책임의 interface |
| Domain | `domain/entity` | 상태와 invariant를 가진 Domain Entity | 실제 domain 이름 | `@Entity`, `@Table`, `@Getter`, protected no-arg constructor |
| Domain | `domain/policy` | 비즈니스 규칙과 판단 기준 | `*Policy` | 필요 시 `@Component` |
| Domain | `domain/exception` | Domain/API 비즈니스 오류 계약 | `*ErrorCode` | `BaseErrorCode`, `@Getter`, `@RequiredArgsConstructor` |
| Infrastructure | `infrastructure/repository` | 기본 persistence와 Spring Data 파생 query | `*Repository` | Spring Data JPA interface |
| Infrastructure | `infrastructure/repository/query` | 복잡 조회와 QueryDSL | `*QueryRepository`, `*SearchRepository` 등 | 책임 interface + 동일 이름 `Impl` |
| Infrastructure | `infrastructure/<technology>` | Application Port의 기술·외부 시스템 구현 | `*Adapter` | 필요 시 `@Component`, `@RequiredArgsConstructor` |

### 역할별 예외

다음 역할은 고정 package를 만들지 않고 실제 책임을 소유하는 layer와 기능별 기술 package에 둔다.

| 역할 | 위치 기준 | Naming | 기본 형태 / Annotation |
|---|---|---|---|
| 반복 처리·상태 전이·delivery 단계 | 해당 책임을 소유하는 Application 또는 Infrastructure package | `*Processor` | 특정 처리 단계 담당 |
| 계산 결과 생성 | 계산 책임을 소유하는 layer | `*Calculator` | 계산 결과 생성 |
| 입력·상태·조건 검증 | 검증 책임을 소유하는 Application 또는 Domain package | `*Validator` | 입력·상태·조건 검증 |
| 주기 실행 진입점 | 기능별 Infrastructure package | `*Scheduler` | `@Component`, `@Scheduled` |
| 메시지 소비 진입점 | Kafka 등 기능별 Infrastructure package | `*Consumer` | `@Component`, `@KafkaListener` 등 |
| Servlet Filter chain | Security/Web Infrastructure package | `*Filter` | 실제 Servlet Filter 구현 |
| Framework interceptor | Web/WebSocket Infrastructure package | `*Interceptor` | 실제 Handler/Channel interceptor 구현 |

기능에 해당 책임이 없으면 빈 package를 만들지 않는다. Issue #38에서 확정한 feature-first 구조와 기능별 package 예외는 유지한다.

## 1. Package와 타입 책임

새 기능의 기본 역할 package는 다음과 같다.

```text
<feature>
├─ presentation
│  ├─ controller
│  ├─ request
│  └─ response
├─ application
│  ├─ command
│  ├─ result
│  ├─ model
│  ├─ port
│  └─ service
├─ domain
│  ├─ entity
│  ├─ exception
│  └─ policy
└─ infrastructure
   └─ repository
      └─ query
```

- `Request`: 외부 HTTP 입력 계약이다.
- `Response`: 외부 HTTP 출력 계약이다.
- `Command`: 특정 Application use case의 입력 계약이다.
- `Result`: 특정 Application use case의 출력 계약이다.
- `Model`: 특정 use case 입출력에 종속되지 않고 재사용되는 Application 개념이다.
- 클래스명에 `DTO` suffix를 사용하지 않는다.
- Entity를 DTO로 분류하지 않는다.
- `Model`을 기타 객체의 수용소로 사용하지 않는다. `AuthMember`, `SearchCondition`, `Context`처럼 실제 책임을 이름에 표현한다.

```java
public record ReservationPrepareRequest(
        Long timeSlotId,
        Integer partySize
) {
}

public record PrepareReservationCommand(
        Long memberId,
        Long timeSlotId,
        Integer partySize
) {
}

public record ReservationPreparationResult(
        Long reservationId,
        String paymentKey
) {
}
```

Application이 presentation 타입을 직접 참조하는 기존 의존 제거, mapping 도입과 계층 방향 변경은 Issue #20 범위다.

## 2. Naming과 suffix

### Service 계열

- `Service`: 하나의 use case를 수행하거나 여러 협력 객체를 조합하는 진입 역할이다.
- `QueryService`: 조회 책임이 명확한 Service다.
- `CommandService`: 쓰기·상태 변경 책임이 명확한 Service다.
- `TransactionService`: 명시적인 transaction 단위를 담당할 때만 사용한다.
- `Processor`: 반복 처리, 상태 전이, delivery 등 특정 처리 단계다.
- `Calculator`: 계산 결과를 만든다.
- `Policy`: 비즈니스 규칙과 판단 기준을 표현한다.
- `Validator`: 입력, 상태와 조건을 검증한다.

Cross-domain 조합이라는 이유만으로 `Coordinator`, `Orchestrator` suffix를 추가하지 않는다. 실제 행위를 앞 이름에 표현한다. 책임이 섞여 있어 이름만으로 해결할 수 없다면 Issue #20에서 분리한다.

### Port와 Adapter

- Application이 외부 기술이나 다른 책임에 요구하는 경계 interface만 `*Port`로 명명한다.
- 모든 interface를 Port로 부르지 않는다.
- `Reader`, `Creator`, `Requester`, `Verifier`, `Generator`, `Hook`을 별도 suffix 체계로 사용하지 않는다. 필요한 기능은 Port 앞 이름과 method 이름에 표현한다.
- 구현체는 `*Adapter`를 기본으로 사용하고 기술명을 앞에 둔다.
- Port는 책임 단위로 작게 유지하며 하나의 거대한 interface로 합치지 않는다.

```java
public interface ReadyPaymentPort {

    CreateReadyPaymentResult createReadyPayment(CreateReadyPaymentCommand command);
}

@Component
@RequiredArgsConstructor
public class PortOneReadyPaymentAdapter implements ReadyPaymentPort {

    private final PortOneClient portOneClient;
}
```

Port 신설, Repository wrapping과 의존 방향 재설계는 Issue #20 범위다.

### 기술 진입점

- `Scheduler`: `@Scheduled`로 시작되는 주기 실행 진입점에만 사용한다.
- `Consumer`: `@KafkaListener` 등 메시지 소비 진입점에 사용한다.
- `Filter`: 실제 Servlet Filter chain 참여 타입에만 사용한다.
- `Interceptor`: 실제 `HandlerInterceptor`, `ChannelInterceptor` 등 framework interceptor에만 사용한다.
- 비즈니스 규칙을 선별하거나 판단하는 객체에는 `Filter`, `Interceptor`를 붙이지 않고 실제 책임에 따라 `Policy`, `Validator`, `Gate` 등으로 명명한다.

### 클래스와 파일

- public type과 파일명은 일치시킨다.
- 클래스는 `PascalCase`, method와 변수는 `camelCase`, 상수와 enum 값은 `UPPER_SNAKE_CASE`를 사용한다.
- 역할이 있는 기술 객체는 이 문서의 책임 suffix를 사용한다.
- Entity와 domain object는 역할명이 충분하면 `Entity`, `VO` 같은 suffix를 인위적으로 붙이지 않는다.
- package 차이는 모양이 아니라 소유권과 책임을 기준으로 판단하고 Issue #38의 의도적인 예외를 유지한다.

## 3. DTO, Command, Result와 Model

`Request`, `Response`, `Command`, `Result`는 `record`가 기본이다.

```java
public record ReservationResponse(
        Long reservationId,
        ReservationStatus status
) {
}
```

- DTO record에 `@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Builder`를 기본 사용하지 않는다.
- framework 요구, 상속, 변경 가능한 상태 또는 복잡한 lifecycle처럼 명확한 이유가 있을 때만 일반 class를 허용한다.
- 인증된 member id처럼 서버가 결정하는 값은 HTTP Request에 받지 않고 인증 객체에서 Command로 전달한다.
- Entity를 HTTP Response로 직접 반환하지 않는다.

## 4. Lombok, 생성자, Builder와 Setter

Lombok은 객체 책임과 상태 변경을 숨기지 않는 범위에서 반복 코드 제거에만 사용한다.

| 대상 | 기본 허용 |
|---|---|
| Controller, Service와 의존성 bean | `@RequiredArgsConstructor` |
| Entity | `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)` |
| ErrorCode | `@Getter`, `@RequiredArgsConstructor` |
| 로그가 필요한 class | `@Slf4j` |
| DTO record | Lombok 미사용 |

다음은 기본적으로 사용하지 않는다.

- `@Data`
- class-level 또는 field-level public `@Setter`
- `@AllArgsConstructor`
- 무분별한 `@Builder`

### Constructor injection

- Spring bean의 의존성은 `final` field와 `@RequiredArgsConstructor`를 사용한 constructor injection이 기본이다.
- 단일 constructor에 `@Autowired`를 붙이지 않는다.
- 복수 constructor 선택이나 optional dependency처럼 명확한 이유가 있는 경우에는 예외를 허용하고 기존 의도를 보존한다.

### Builder와 Setter

- Entity public Setter를 사용하지 않는다.
- Entity 상태는 `cancel()`, `confirm()`처럼 의미 있는 domain method로 변경한다.
- Request, Response, Command와 Result는 record constructor를 기본으로 사용한다.
- Builder는 기본 생성 방식이 아니다. optional 값이 많아 생성자 가독성이 실제로 낮아지는 immutable 객체나 test fixture처럼 명확한 이유가 있을 때만 허용한다.

## 5. Controller와 Validation

Controller의 기본 형태는 다음과 같다.

```java
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

- class에 `@RestController`, base path의 `@RequestMapping`, constructor injection의 `@RequiredArgsConstructor`를 사용한다.
- Request Body는 `@Valid`로 검증한다.
- `@Positive @PathVariable`, `@Min @RequestParam` 등 method parameter validation이 실제로 있을 때만 class-level `@Validated`를 추가한다.
- 기존 `ApiResponse<T>` 계약을 유지한다.
- Controller는 HTTP 변환과 Service 호출만 담당하고 Repository나 Entity를 직접 다루지 않는다.

검증은 책임별로 둔다.

| 위치 | 검증 책임 |
|---|---|
| Request | 외부 입력 형식 |
| Controller | Request Body의 `@Valid`, 필요한 method parameter validation |
| Application Validator | DB 조회와 여러 조건을 조합하는 use case 사전조건 |
| Entity, Policy | 비즈니스 규칙과 domain invariant |
| DB | nullable, unique, FK 등 데이터 무결성의 최종 방어선 |

같은 검증을 모든 계층에 기계적으로 복제하지 않는다.

## 6. Service와 Transaction

Service는 use case를 수행하고 transaction 경계를 관리한다. 책임이 명확한 class는 class-level 기본값을 사용할 수 있고, 읽기와 쓰기가 섞인 class는 method-level로 구분한다.

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyReservationQueryService {
}
```

```java
@Service
@RequiredArgsConstructor
@Transactional
public class ReservationCommandService {
}
```

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    @Transactional(readOnly = true)
    public ReservationResult get(Long reservationId) {
        // 조회
    }

    @Transactional
    public void cancel(Long reservationId) {
        // 상태 변경
    }
}
```

- 조회는 `@Transactional(readOnly = true)`를 사용한다.
- 쓰기와 상태 변경은 기본 `@Transactional`을 사용한다.
- `REQUIRES_NEW`, `MANDATORY` 등 propagation은 동작상 이유가 확인된 경우에만 사용한다.
- Issue #15에서 기존 propagation, 원자성, 실행 순서를 기계적으로 변경하지 않는다.
- 복잡 조회 구현을 포함한 Repository가 transaction 경계를 결정하지 않고 Service가 관리한다.

## 7. Entity

Domain Entity의 기본 형태는 다음과 같다.

```java
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    public void cancel() {
        // invariant 확인 후 상태 변경
    }
}
```

- 기본 조합은 `@Entity`, `@Table(name = "...")`, `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`다.
- `@Id`, `@GeneratedValue`, `@Column`, `@Enumerated`와 association Annotation은 실제 mapping에 맞춘다.
- `@Table`의 index와 unique constraint 등 DB schema 의미가 있는 설정을 삭제하거나 임의 변경하지 않는다.
- public Setter를 열지 않고 의미 있는 domain method로 상태를 변경한다.
- 생성 인자와 invariant가 있으면 정적 factory를 사용할 수 있다.
- 기술 영속 모델이 승인된 infrastructure 소유라면 모양을 맞추기 위해 domain으로 이동하지 않는다.

## 8. Repository와 QueryDSL

기본 persistence와 복잡 조회를 분리한다.

```text
infrastructure/repository
├─ ReservationRepository.java
└─ query
   ├─ ReservationSearchRepository.java
   └─ ReservationSearchRepositoryImpl.java
```

- 단순 CRUD와 Spring Data 파생 query는 `XxxRepository`에 둔다.
- 복잡 조회와 QueryDSL은 `infrastructure/repository/query` 아래 책임별 Repository에 둔다.
- `Custom`처럼 책임이 약한 이름을 사용하지 않는다. `Search`, `Query`, `Statistics`, `Settlement`처럼 실제 조회 책임을 이름에 표현한다.
- 구현 class는 interface와 같은 이름에 `Impl`을 붙인다.
- 단순 Repository까지 기계적으로 interface+Impl로 만들지 않는다.
- `JpaRepository` interface에는 `@Repository`를 기계적으로 붙이지 않는다.
- QueryDSL fragment도 framework 등록상 필요하지 않다면 `@Repository`를 강제하지 않는다.
- `@Query`, `@Lock`, `@Param`은 실제 필요한 method에만 사용한다.
- transaction 경계는 기본적으로 Service가 관리한다.

```java
public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, ReservationSearchRepository {
}
```

```java
public interface ReservationSearchRepository {

    Page<ReservationSearchResult> searchRecruitingReservations(
            ReservationSearchCondition condition,
            Pageable pageable
    );
}

public class ReservationSearchRepositoryImpl
        implements ReservationSearchRepository {

    private final JPAQueryFactory queryFactory;

    public ReservationSearchRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }
}
```

Admin fragment의 역방향 연결, Repository Port 전환과 의존성 재설계는 Issue #20 범위다.

## 9. Exception과 ErrorCode

- 사용자에게 전달하는 domain/API 비즈니스 실패는 `BaseErrorCode` 구현 enum과 `CustomException`을 사용한다.
- 추가 상태를 보존해야 할 때만 specialized `CustomException`을 허용한다.
- 외부 시스템, 직렬화, retry/DLT 등 기술 실패는 기술 예외로 구분한다.
- `IllegalArgumentException`, `IllegalStateException`은 programmer error, config 오류와 내부 invariant 위반에만 사용한다.
- 공통이 아닌 domain 오류는 각 기능의 `domain/exception`에 둔다.

```java
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {

    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
```

- ErrorCode field는 `httpStatus`, `message`로 통일한다.
- enum 상수는 `UPPER_SNAKE_CASE`를 사용한다.
- Controller에서 예외를 반복 catch하지 않고 공통 `GlobalExceptionHandler` 계약을 사용한다.

```java
if (reservation.isCancelled()) {
    throw new CustomException(ReservationErrorCode.ALREADY_CANCELLED);
}
```

## 10. Logging

SLF4J를 사용하되 Logger field를 직접 선언하지 않고 로그가 필요한 class에만 Lombok `@Slf4j`를 사용한다.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    public void cancel(Long reservationId) {
        log.info(
                "event=RESERVATION_CANCELLED reservationId={}",
                reservationId
        );
    }
}
```

- `{}` parameterized logging을 사용한다.
- 주요 event는 `event=UPPER_SNAKE_CASE key={}` 형식을 기본으로 한다.
- 같은 성격의 event와 실패에는 `info`, `warn`, `error`, `debug` level을 일관되게 사용한다.
- 비밀번호, token과 민감 개인정보를 로그에 남기지 않는다.
- level이나 식별자 변경이 운영 alert·보안 정책을 바꾸면 별도 Human 결정을 받는다.

## 11. Formatting과 주석

- 들여쓰기는 공백 4칸이다.
- 의미 없는 연속 빈 줄을 제거하고 처리 단계가 바뀔 때 빈 줄 1줄을 사용한다.
- import group 뒤에 빈 줄을 둔다.
- 사용하지 않는 import와 wildcard import를 사용하지 않는다.
- 한 줄에 field, constructor 또는 method 여러 개를 압축하지 않는다.
- 같은 역할의 Annotation과 method formatting을 통일한다.
- 파일 끝 newline을 유지한다.
- 짧은 호출은 한 줄로 두고 길어지면 argument 단위로 일관되게 줄바꿈한다.

```java
private final HttpStatus httpStatus;
private final String message;

@Override
public HttpStatus getHttpStatus() {
    return httpStatus;
}
```

주석은 코드만 반복하지 않는다. domain 규칙, 보안 의도, framework 예외와 확장 지점처럼 처음 읽는 사람이 코드만으로 알기 어려운 이유를 설명한다. 공개 계약이나 복잡한 책임은 JavaDoc, 짧은 구현 맥락은 `//`를 사용할 수 있다.

## 12. 예외 적용과 Issue 경계

규칙을 적용하기 전에 다음 순서로 확인한다.

1. 소유 domain
2. layer
3. 실제 책임
4. 책임 범위
5. 현재 이름과 package가 책임을 설명하는지

```text
책임 명확 + 이름·작성 방식 적절
→ 유지

책임 명확 + 이름·작성 방식 부적절
→ Issue #15에서 package, naming, Annotation과 표현 정리

책임 혼합 또는 의존 방향 재설계 필요
→ 이름만 바꾸지 않고 Issue #20 대상으로 기록
```

Issue #15에서 변경하지 않는 항목은 다음과 같다.

- API 계약과 JSON 의미
- DB schema와 JPA mapping 의미
- 비즈니스 정책과 상태 전이
- transaction propagation, 원자성과 실행 순서
- Service 책임 분리
- 새 Port 도입과 Repository wrapping
- Application → Presentation 의존 제거
- 계층 의존 방향 재설계
