# Code Convention

# 코딩 컨벤션

> Spring Boot 기반 합석 예약 서비스 밥풀(BobFull)의 백엔드 코드 작성 규칙이다.
>
> 엄격한 DDD 구조보다 **기능 단위 패키지 + 역할별 하위 패키지**를 따른다.
>
> 목표는 빠른 구현이 아니라, 팀원이 서로의 코드를 쉽게 읽고 수정할 수 있는 구조를 만드는 것이다.

---

## 1. 기본 원칙

- Java 코드 스타일은 [NAVER CAMPUS HACKDAY Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)을 기본 기준으로 사용하며, 충돌 시 이 문서의 프로젝트별 규칙을 우선한다.
- 기능 단위로 패키지를 나눈다.
- 각 기능 내부는 역할에 따라 `controller`, `service`, `repository`, `dto`, `entity`로 구분한다.
- Controller는 요청과 응답만 담당한다.
- Service는 비즈니스 로직과 트랜잭션을 담당한다.
- Repository는 DB 접근만 담당한다.
- DTO는 요청/응답 데이터를 전달하는 용도로만 사용한다.
- Entity는 DB 테이블과 매핑되는 객체이며, 핵심 상태 변경 메서드를 포함할 수 있다.
- Entity를 API 응답으로 직접 반환하지 않는다.
- 인증 사용자의 `memberId`는 클라이언트 요청값이 아니라 인증 객체에서 꺼낸다.
- Redis는 DB 테이블이 아니므로 ERD에 포함하지 않고, 아키텍처 문서에 표현한다.

---

## 1.1 주석 작성 원칙

팀원 간 코드 리뷰와 학습 효율을 위해 설명이 필요한 클래스와 메서드에는 주석을 작성한다.

- 클래스 설명은 클래스 선언 바로 위에 JavaDoc 형식으로 작성한다.
- 메서드 내부의 짧은 보조 설명은 `//` 한 줄 주석을 사용한다.
- 단순히 코드 내용을 반복하는 주석은 작성하지 않는다.
- 도메인 규칙, 보안 의도, 확장 포인트처럼 코드를 처음 보는 팀원이 이해해야 하는 맥락을 우선 설명한다.

```java
/**
 * 회원 정보를 저장하는 JPA 엔티티다.
 * 이메일과 닉네임은 서비스 내에서 유일해야 한다.
 */
public class Member {

    public static Member create(String email, String encodedPassword, String nickname) {
        // 신규 가입 회원은 기본 권한을 MEMBER로 생성한다.
        return new Member(email, encodedPassword, nickname, MemberRole.MEMBER);
    }
}
```

---

## 2. 패키지 구조

기능 단위 패키지 아래에 역할별 패키지를 둔다.

```text
com.bobfull
├── common
│   ├── config
│   ├── entity
│   ├── exception
│   ├── response
│   └── security
│
├── auth
│   ├── controller
│   ├── service
│   └── dto
│
└── 각 도메인
    ├── controller
    ├── service
    ├── repository
    ├── dto
    └── entity
```

### 현재 프로젝트 기준

- `presentation`, `application`, `domain`, `infrastructure` 같은 계층형 DDD 패키지는 사용하지 않는다.
- 기능 단위 패키지 아래에 역할별 하위 패키지를 둔다.
- 단, 각 계층의 책임 분리는 반드시 지킨다.

---

## 3. 역할별 책임

| 패키지 | 책임 |
|---|---|
| `controller` | HTTP 요청/응답 처리, Request DTO 검증, Service 호출 |
| `service` | 비즈니스 로직 처리, 트랜잭션 관리, Entity 상태 변경 |
| `repository` | DB 또는 도메인 전용 외부 저장소 조회/저장/수정/삭제 |
| `dto` | Request, Response, 내부 전달용 DTO |
| `entity` | JPA Entity, DB 테이블 매핑, 상태 변경 메서드 |
| `common` | 공통 설정, 응답, 예외, 보안 |

---

## 4. 의존 방향

기본 흐름은 아래 방향을 따른다.

```text
Controller → Service → Repository → Entity
```

각 역할은 아래 원칙을 지킨다.

- Controller는 Service만 호출한다.
- Controller에서 Repository를 직접 호출하지 않는다.
- Controller에서 비즈니스 로직을 작성하지 않는다.
- Service는 Repository를 통해 Entity를 조회하거나 저장한다.
- Repository는 DB 접근 또는 도메인 전용 외부 저장소 접근만 담당한다.
- Entity를 API 응답으로 직접 반환하지 않는다.
- 다른 도메인의 Repository를 직접 많이 끌어오는 구조는 피하고, 필요하면 해당 도메인 Service 메서드로 위임한다.

---

## 5. DTO 규칙

DTO는 각 도메인의 `dto` 패키지에 둔다.

```text
도메인
└── dto
    ├── 기능명Request
    ├── 기능명Response
    └── 기능명DetailResponse
```

### Request DTO

- 클라이언트 요청 데이터를 받는 용도이다.
- Controller에서 사용한다.
- 검증 어노테이션을 사용한다.
- 인증된 회원 ID는 Request DTO에 넣지 않는다.

```java
public record ReservationCreateRequest(
        @NotNull Long restaurantId,
        @NotNull Long timeSlotId
) {
}
```

### Response DTO

- 클라이언트에게 반환할 데이터를 담는다.
- Entity를 직접 반환하지 않고 Response DTO로 변환한다.
- 단순 조회는 `from(entity)` 정적 팩토리를 사용한다.
- 여러 Entity 조합 응답은 Service에서 필요한 데이터를 조립한다.

```java
public record ReservationResponse(
        Long reservationId,
        Long restaurantId,
        Long timeSlotId,
        String status
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getRestaurant().getId(),
                reservation.getTimeSlot().getId(),
                reservation.getStatus().name()
        );
    }
}
```

---

## 6. Controller 규칙

- 요청을 받고 응답을 반환하는 역할만 한다.
- Request DTO를 검증한다.
- Service를 호출한다.
- Entity를 직접 반환하지 않는다.
- Repository를 직접 호출하지 않는다.
- 인증 사용자는 `@AuthenticationPrincipal` 또는 공통 인증 객체로 받는다.
- HTTP Status와 내부 ErrorCode를 혼동하지 않는다.

좋은 예시:

```java
@PostMapping("/api/reservations")
public ResponseEntity<ApiResponse<ReservationResponse>> createReservation(
        @AuthenticationPrincipal AuthMember authMember,
        @Valid @RequestBody ReservationCreateRequest request
) {
    ReservationResponse response =
            reservationService.createReservation(authMember.id(), request);

    return ResponseEntity.ok(ApiResponse.success(response));
}
```

피해야 할 예시:

```java
@PostMapping("/api/reservations")
public Reservation createReservation(
        @RequestBody ReservationCreateRequest request
) {
    Member member = memberRepository.findById(request.memberId())
            .orElseThrow();

    Reservation reservation = Reservation.create(
            member,
            request.restaurantId(),
            request.timeSlotId()
    );

    return reservationRepository.save(reservation);
}
```

---

## 7. Service 규칙

- 비즈니스 로직은 Service에서 처리한다.
- 트랜잭션은 Service 메서드에 적용한다.
- Entity 조회, 검증, 상태 변경, 저장 흐름을 담당한다.
- Controller로부터 받은 Request DTO를 사용할 수 있다.
- Service에서 Response DTO로 변환하여 Controller에 반환한다.

```java
@Transactional
public ReservationResponse createReservation(
        Long memberId,
        ReservationCreateRequest request
) {
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

    Restaurant restaurant = restaurantRepository.findById(request.restaurantId())
            .orElseThrow(() -> new CustomException(ErrorCode.RESTAURANT_NOT_FOUND));

    TimeSlot timeSlot = timeSlotRepository.findById(request.timeSlotId())
            .orElseThrow(() -> new CustomException(ErrorCode.TIME_SLOT_NOT_FOUND));

    Reservation reservation = Reservation.create(
            member,
            restaurant,
            timeSlot
    );

    Reservation savedReservation = reservationRepository.save(reservation);

    return ReservationResponse.from(savedReservation);
}
```

### 트랜잭션 기준

- 조회 전용 메서드는 `@Transactional(readOnly = true)`를 사용한다.
- 생성, 수정, 삭제, 상태 변경은 `@Transactional`을 사용한다.
- 트랜잭션 안에서 불필요한 외부 API 호출, 긴 대기, Thread sleep을 하지 않는다.

---

## 8. Repository 규칙

- Repository는 DB 접근만 담당한다.
- Repository에서 비즈니스 로직을 처리하지 않는다.
- 단순 CRUD는 Spring Data JPA Repository를 사용한다.
- 복잡한 조건 검색은 `@Query` 또는 QueryDSL을 사용할 수 있다.
- 검색 API는 페이징을 기본으로 한다.
- 대량 메시지 조회는 offset보다 cursor 기반 조회를 우선한다.

```java
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Page<Reservation> findAllByRestaurantIdAndStatus(
            Long restaurantId,
            ReservationStatus status,
            Pageable pageable
    );
}
```

---

## 9. Entity 규칙

- Entity는 `entity` 패키지에 둔다.
- Entity는 JPA 매핑 정보를 가진다.
- setter를 무분별하게 열지 않는다.
- 상태 변경은 의미 있는 메서드로 처리한다.
- 생성자는 protected로 제한하고, 생성은 정적 팩토리 메서드를 우선 사용한다.

좋은 예시:

```java
public void confirm() {
    if (this.status != ReservationStatus.RECRUITING) {
        throw new CustomException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    this.status = ReservationStatus.CONFIRMED;
}

public void cancel() {
    if (this.status == ReservationStatus.CLOSED
            || this.status == ReservationStatus.CANCELLED) {
        throw new CustomException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    this.status = ReservationStatus.CANCELLED;
}
```

피해야 할 예시:

```java
reservation.setStatus(ReservationStatus.CONFIRMED);
reservation.setMember(member);
reservation.setRestaurant(restaurant);
reservation.setTimeSlot(timeSlot);
```

---

## 10. 공통 응답 규칙

모든 HTTP 응답은 `ApiResponse<T>`를 사용한다.

### 성공 응답

```json
{
  "success": true,
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

### 실패 응답

```json
{
  "success": false,
  "code": "ERROR_CODE",
  "message": "에러 메시지"
}
```

### 규칙

- 성공 응답은 `ApiResponse.success(data)`를 사용한다.
- 실패 응답은 `GlobalExceptionHandler`에서 일괄 처리한다.
- Controller에서 try-catch로 예외를 반복 처리하지 않는다.
- HTTP Status와 내부 ErrorCode는 분리한다.

```text
HTTP Status: 401 Unauthorized
Error Code: BLACKLIST_TOKEN
```

---

## 11. 예외 처리 규칙

- 비즈니스 예외는 `CustomException`을 사용한다.
- 에러 코드는 `ErrorCode` enum으로 관리한다.
- 공통 예외 처리는 `GlobalExceptionHandler`에서 담당한다.
- 인증 실패는 Security Filter 또는 `AuthenticationEntryPoint`에서 처리한다.
- 권한 부족은 `AccessDeniedHandler`에서 처리한다.

```java
throw new CustomException(ErrorCode.INVALID_REQUEST);
```

### ErrorCode 네이밍 예시

```java
MEMBER_NOT_FOUND
DUPLICATED_EMAIL
INVALID_PASSWORD
UNAUTHORIZED
FORBIDDEN
INVALID_REQUEST
INTERNAL_SERVER_ERROR
```

---

## 12. 인증 / 인가 규칙

- JWT 인증은 Security Filter에서 처리한다.
- 인증된 사용자 정보는 `SecurityContextHolder`에 저장한다.
- Controller에서는 `@AuthenticationPrincipal AuthMember`로 사용자 정보를 받는다.
- 관리자 권한은 Controller 내부 if문보다 Security 설정에서 우선 처리한다.
- 클라이언트가 보낸 사용자 식별자를 인증 정보로 신뢰하지 않는다.

```java
.requestMatchers("/api/auth/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/restaurants", "/api/restaurants/{restaurantId}").permitAll()
.requestMatchers("/api/owner/**").hasRole("OWNER")
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

공개 경로는 넓은 패턴 대신 API 명세에 실제로 존재하는 인증 불필요 Method·Path만 명시적으로 나열한다.

---

## 13. 네이밍 규칙

- 클래스명은 `PascalCase`를 사용한다.
- 메서드명과 변수명은 `camelCase`를 사용한다.
- 상수와 Enum 값은 `UPPER_SNAKE_CASE`를 사용한다.
- Controller는 `도메인명Controller`로 작성한다.
- Service는 `도메인명Service`로 작성한다.
- Repository는 `도메인명Repository`로 작성한다.
- 요청 DTO는 `기능명Request`로 작성한다.
- 응답 DTO는 `기능명Response`로 작성한다.

---

## 14. 테스트 규칙

- Service 핵심 비즈니스 로직은 테스트를 작성한다.
- 테스트 메서드명은 한글 설명형으로 작성하고 단어는 `_`로 구분한다.
- 테스트 이름에 조건과 기대 결과가 드러나게 작성한다.
- 테스트 본문은 `given`, `when`, `then` 순서로 구분해서 작성한다.
- `given`에는 테스트 데이터와 사전 조건, `when`에는 실행 대상, `then`에는 검증 코드를 둔다.
- 예약 생성, 예약 참여, 결제, 환불, 노쇼 상태 변경은 우선 테스트 대상으로 한다.
- 동일 예약에 여러 사용자가 동시에 참여하는 상황은 동시성 테스트를 작성한다.
- 동시성 해결 전 실패 테스트와 해결 후 성공 테스트를 기록한다.
- 상세 테스트 작성·실행·증거 규칙은 `docs/050-engineering/test-convention.md`를 따른다.

테스트 구조 예시:

```java
@Test
void 결제_실패시_예약과_참여자가_생성되지_않는다() {
    // given
    ...

    // when
    ...

    // then
    ...
}
```

동시성 테스트 키워드:

```text
ExecutorService
CountDownLatch
CyclicBarrier
```

---

## 15. 배포 / CI/CD 규칙

최소 배포 구성은 아래를 목표로 한다.

```text
Dockerfile
docker-compose.yml
GitHub Actions CI
EC2 배포
```

### 로컬 개발 환경

- `docker-compose.yml`로 프로젝트에 필요한 로컬 인프라를 실행한다.
- `.env` 파일은 Git에 올리지 않는다.

### CI

- PR 또는 push 시 build/test를 수행한다.
- 테스트 실패 시 배포하지 않는다.
- main 직접 push를 금지한다.

### 민감 정보

- 로컬: `.env`
- GitHub Actions: GitHub Secrets
- 운영: 환경변수 또는 AWS Parameter Store

---

## 16. AI 활용 규칙

- AI의 작업 가능 범위는 `AGENTS.md`, `docs/060-ai/ai-workflow.md`, `docs/060-ai/ai-implementation-guide.md`를 따른다.
- Human이 `READY`로 승인한 Issue에서는 AI가 구현 계획, 코드, 테스트, 검증, 문서와 Draft PR을 작성할 수 있다.
- AI가 생성한 코드와 테스트는 담당자가 실제 Diff와 실행 결과를 확인하고 설명할 수 있어야 한다.
- AI가 사용된 작업과 Human이 직접 확인한 범위는 PR 템플릿에 간단히 기록한다.
- AI 결과를 프로젝트 컨벤션에 맞게 검토하고, 이해하지 못한 코드는 Merge하지 않는다.
- AI는 미확정 정책, 리뷰 반영 범위와 Merge를 결정하지 않는다.

---

## 17. 금지 사항

- Controller에서 Repository 직접 호출 금지
- Controller에서 비즈니스 로직 작성 금지
- Entity를 API 응답으로 직접 반환 금지
- DTO를 Entity처럼 사용 금지
- Repository에서 비즈니스 로직 처리 금지
- setter 무분별한 사용 금지
- 인증/인가 로직을 여러 Controller에 반복 작성 금지
- 예외 처리를 Controller마다 try-catch로 반복 작성 금지
- 클라이언트가 전달한 사용자 식별자를 인증 정보로 신뢰 금지
- 과도한 아키텍처 추가 금지

---

## 18. 현재 프로젝트 기준 정리

현재 밥조팀 프로젝트에서는 아래 구조를 기준으로 한다.

```text
기능 패키지
├── controller
├── service
├── repository
├── dto
└── entity
```

핵심은 아래다.

- Controller는 얇게 유지한다.
- Service에 비즈니스 흐름을 둔다.
- Repository는 DB 접근만 담당한다.
- Entity는 외부로 직접 노출하지 않는다.
- Response DTO로 변환해서 응답한다.
