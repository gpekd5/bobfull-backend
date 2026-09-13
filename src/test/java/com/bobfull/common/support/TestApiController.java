package com.bobfull.common.support;

import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.auth.application.model.AuthMember;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공통 골격(응답·예외·보안)이 실제 요청 흐름에서 동작하는지 검증하기 위한 테스트 전용 Controller다.
 * 운영 API가 아니며 src/test 범위 밖으로 노출되지 않는다.
 */
@RestController
@Profile("test-api")
public class TestApiController {

    @GetMapping("/api/public/hello")
    public ApiResponse<String> publicHello() {
        return ApiResponse.success("public-ok");
    }

    @GetMapping("/api/protected/hello")
    public ApiResponse<TestAuthResponse> protectedHello(@AuthenticationPrincipal AuthMember authMember) {
        return ApiResponse.success(new TestAuthResponse(authMember.id(), authMember.role()));
    }

    @GetMapping("/api/owner/hello")
    public ApiResponse<String> ownerHello() {
        return ApiResponse.success("owner-ok");
    }

    @GetMapping("/api/admin/hello")
    public ApiResponse<String> adminHello() {
        return ApiResponse.success("admin-ok");
    }

    @PostMapping("/api/auth/sample")
    public ApiResponse<String> authSample() {
        return ApiResponse.success("auth-ok");
    }

    @GetMapping("/api/restaurants")
    public ApiResponse<String> restaurantsHello() {
        return ApiResponse.success("restaurants-ok");
    }

    @GetMapping("/api/restaurants/{restaurantId}/dining-sessions")
    public ApiResponse<String> diningSessionsHello(@PathVariable Long restaurantId) {
        return ApiResponse.success("dining-sessions-ok");
    }

    @GetMapping("/api/reservations/search")
    public ApiResponse<String> reservationsSearchHello() {
        return ApiResponse.success("reservations-search-ok");
    }

    @GetMapping("/api/errors/custom")
    public ApiResponse<Void> customError() {
        throw new CustomException(MemberErrorCode.MEMBER_NOT_FOUND);
    }

    @GetMapping("/api/errors/domain")
    public ApiResponse<Void> domainError() {
        throw new CustomException(SampleDomainErrorCode.SAMPLE_DOMAIN_CONFLICT);
    }

    @GetMapping("/api/errors/internal")
    public ApiResponse<Void> internalError() {
        throw new IllegalStateException("의도적으로 발생시킨 예외");
    }

    @PostMapping("/api/errors/validate")
    public ApiResponse<String> validate(@Valid @RequestBody TestRequest request) {
        return ApiResponse.success(request.name());
    }

    @GetMapping("/api/time/hello")
    public ApiResponse<TestTimeResponse> timeHello() {
        Instant fixedInstant = Instant.parse("2026-07-23T00:00:00Z");
        return ApiResponse.success(
                new TestTimeResponse(fixedInstant.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime())
        );
    }
}
