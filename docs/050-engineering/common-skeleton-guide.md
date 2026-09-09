# 공통 개발 골격 사용 가이드

이 문서는 `common` 패키지에서 제공하는 공통 응답·예외·시간·보안 골격을 다른 도메인에서 사용하는 방법을 설명한다.
[docs/050-engineering/code-convention.md](code-convention.md)의 §10~§12 규칙을 실제 클래스와 연결한 실행 가이드다.

## 1. 공통 응답 — `common.response.ApiResponse`

모든 Controller는 `ApiResponse<T>`로 응답을 감싼다.

```java
@GetMapping("/api/reservations/{id}")
public ApiResponse<ReservationResponse> getReservation(@PathVariable Long id) {
    return ApiResponse.success(reservationService.getReservation(id));
}
```

- 성공: `ApiResponse.success(data)`
- 실패는 직접 만들지 않는다. `CustomException`을 던지면 `GlobalExceptionHandler`가 자동으로 `ApiResponse.fail(errorCode)` 응답을 만든다.

## 2. 공통 예외 — `common.exception.BaseErrorCode`, `CommonErrorCode`, `CustomException`

에러 코드는 하나의 거대한 Enum에 누적하지 않는다. 새 도메인은 `BaseErrorCode`를 구현하는
**별도 Enum**을 추가한다(`MemberErrorCode`가 예시).

```java
public enum RestaurantErrorCode implements BaseErrorCode {

    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "식당을 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    RestaurantErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() { return httpStatus; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
```

Service·Domain에서는 아래처럼 던진다. `GlobalExceptionHandler`는 `BaseErrorCode` 인터페이스만
참조하므로 새 Enum을 추가해도 수정할 필요가 없다.

```java
throw new CustomException(RestaurantErrorCode.RESTAURANT_NOT_FOUND);
```

도메인에 속하지 않는 공통 에러(`INVALID_INPUT_VALUE`, `UNAUTHORIZED`, `ACCESS_DENIED`,
`INTERNAL_SERVER_ERROR`)만 `CommonErrorCode`에 있다. 도메인 전용 에러는 여기에 추가하지 않는다.

Bean Validation 실패(`@Valid`)와 처리되지 않은 예외는 `GlobalExceptionHandler`가 각각
`CommonErrorCode.INVALID_INPUT_VALUE`, `CommonErrorCode.INTERNAL_SERVER_ERROR`로 자동 처리한다.

## 3. 인증 회원 — `common.security.AuthMember`, `MemberRole`

Controller는 인증된 회원 정보를 `@AuthenticationPrincipal`로 받는다. 클라이언트가 보낸 회원 ID는 사용하지 않는다.

```java
@PostMapping("/api/reservations")
public ApiResponse<ReservationResponse> create(
        @AuthenticationPrincipal AuthMember authMember,
        @Valid @RequestBody ReservationCreateRequest request
) {
    return ApiResponse.success(reservationService.create(authMember.id(), request));
}
```

- 인증 필요 API를 추가하려면 `common.security.SecurityConfig`의 `authorizeHttpRequests`에 경로 규칙을 추가한다.
- 인증 실패(401)·권한 부족(403) 응답은 `CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler`가 공통 포맷으로 처리하므로 별도 처리가 필요 없다.
- 실제 JWT 인증 필터는 이 Issue의 범위가 아니며 이후 Issue에서 추가된다.

## 4. 시간 정책 — `common.config.ClockConfig`, `JpaAuditingConfig`, `common.entity.BaseTimeEntity`

- DB·Entity에는 절대 시점을 UTC(`Instant`)로 저장한다.
- **`Instant`를 Response DTO에 그대로 담아 반환하지 않는다.** `spring.jackson.time-zone` 설정만으로는
  `Instant`가 자동으로 `Asia/Seoul`로 바뀌지 않고 항상 UTC(`Z`)로 직렬화된다(직접 확인됨).
  API로 시각을 응답해야 하면 Response DTO에서 `Asia/Seoul` 기준 `OffsetDateTime`으로 명시적으로
  변환한다.

```java
public record ReservationResponse(Long id, OffsetDateTime createdAt) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime()
        );
    }
}
```

- 현재 시각이 필요하면 시스템 시각을 직접 호출하지 않고 주입된 `Clock`을 사용한다.

```java
public ReservationService(Clock clock) {
    this.clock = clock;
}
```

- 생성·수정 시각이 필요한 Entity는 `BaseTimeEntity`를 상속한다.

```java
public class Reservation extends BaseTimeEntity {
}
```

## 5. 이 가이드에 포함되지 않는 것

회원가입·로그인, JWT 발급·검증, 각 도메인 권한 세부 정책은 이 골격의 범위가 아니며 해당 Issue에서 별도로 다룬다.
