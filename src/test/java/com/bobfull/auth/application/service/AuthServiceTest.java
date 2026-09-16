package com.bobfull.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.auth.presentation.request.LoginRequest;
import com.bobfull.auth.presentation.response.LoginResponse;
import com.bobfull.auth.presentation.response.LogoutResponse;
import com.bobfull.auth.presentation.response.ReissueResponse;
import com.bobfull.auth.presentation.request.SignupOwnerRequest;
import com.bobfull.auth.presentation.response.SignupResponse;
import com.bobfull.auth.presentation.request.SignupUserRequest;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.bobfull.auth.infrastructure.redis.RefreshTokenStore;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 회원가입 중복 검증·비밀번호 해시, 로그인·재발급·로그아웃 흐름을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private BusinessMetricRecorder businessMetricRecorder;

    @Mock
    private AccessTokenBlacklistStore accessTokenBlacklistStore;

    @Mock
    private Clock clock;

    @InjectMocks
    private AuthService authService;

    @Test
    void 이메일이_중복되면_회원가입에_실패한다() {
        // given
        SignupUserRequest request = new SignupUserRequest("dup@example.com", "Password123!", "홍길동", "01011112222");
        given(memberRepository.existsByEmail(request.email())).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> authService.signupMember(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 전화번호가_중복되면_회원가입에_실패한다() {
        // given
        SignupUserRequest request = new SignupUserRequest("new@example.com", "Password123!", "홍길동", "01011112222");
        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(memberRepository.existsByPhoneNumber(request.phoneNumber())).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> authService.signupMember(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
    }

    @Test
    void 사업자등록번호가_중복되면_사장님_회원가입에_실패한다() {
        // given
        SignupOwnerRequest request = new SignupOwnerRequest(
                "owner@example.com", "Password123!", "김사장", "01033334444", "1234567890");
        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(memberRepository.existsByPhoneNumber(request.phoneNumber())).willReturn(false);
        given(memberRepository.existsByBusinessNumber(request.businessNumber())).willReturn(true);

        // when
        Throwable result = catchThrowable(() -> authService.signupOwner(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_BUSINESS_NUMBER);
    }

    @Test
    void 회원가입_성공시_비밀번호를_해시로_저장한다() {
        // given
        SignupUserRequest request = new SignupUserRequest("new@example.com", "Password123!", "홍길동", "01011112222");
        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(memberRepository.existsByPhoneNumber(request.phoneNumber())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(memberRepository.save(any(Member.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        SignupResponse response = authService.signupMember(request);

        // then
        assertThat(response.email()).isEqualTo(request.email());
        assertThat(response.role()).isEqualTo(MemberRole.MEMBER);
        verify(passwordEncoder).encode(request.password());
    }

    @Test
    void 사전_검사_통과후_저장시점에_DB_제약을_위반하면_중복_이메일_예외로_변환한다() {
        // given
        SignupUserRequest request = new SignupUserRequest("race@example.com", "Password123!", "홍길동", "01011112222");
        given(memberRepository.existsByEmail(request.email()))
                .willReturn(false)
                .willReturn(true);
        given(memberRepository.existsByPhoneNumber(anyString())).willReturn(false);
        given(passwordEncoder.encode(anyString())).willReturn("encoded-password");
        given(memberRepository.save(any(Member.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key"));

        // when
        Throwable result = catchThrowable(() -> authService.signupMember(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 존재하지_않는_이메일로_로그인하면_INVALID_CREDENTIALS_예외가_발생한다() {
        // given
        LoginRequest request = new LoginRequest("unknown@example.com", "Password123!");
        given(memberRepository.findByEmail(request.email())).willReturn(java.util.Optional.empty());

        // when
        Throwable result = catchThrowable(() -> authService.login(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 로그인_실패_로그는_계정_존재여부와_민감정보를_남기지_않는다() {
        // given
        LoginRequest request = new LoginRequest("unknown@example.com", "Password123!");
        given(memberRepository.findByEmail(request.email())).willReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(AuthService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            catchThrowable(() -> authService.login(request));
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=LOGIN_FAILED");
            assertThat(event.getFormattedMessage()).contains("reason=INVALID_CREDENTIALS");
            assertThat(event.getFormattedMessage()).doesNotContain("unknown@example.com");
            assertThat(event.getFormattedMessage()).doesNotContain("Password123!");
        });
    }

    @Test
    void 비밀번호가_일치하지_않으면_INVALID_CREDENTIALS_예외가_발생한다() {
        // given
        LoginRequest request = new LoginRequest("user@example.com", "WrongPassword!");
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        given(memberRepository.findByEmail(request.email())).willReturn(java.util.Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPasswordHash())).willReturn(false);

        // when
        Throwable result = catchThrowable(() -> authService.login(request));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(MemberErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 로그인_성공시_AccessToken과_RefreshToken을_함께_발급한다() {
        // given
        LoginRequest request = new LoginRequest("user@example.com", "Password123!");
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPasswordHash())).willReturn(true);
        given(jwtTokenProvider.createAccessToken(member.getId(), member.getRole())).willReturn("access-token");
        given(refreshTokenStore.issue(member.getId())).willReturn("refresh-token");

        // when
        LoginResponse response = authService.login(request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void 유효한_RefreshToken으로_재발급하면_회전된_토큰과_새_AccessToken을_반환한다() {
        // given
        Member member = Member.createMember("user@example.com", "encoded-password", "홍길동", "01011112222");
        ReflectionTestUtils.setField(member, "id", 1L);
        given(refreshTokenStore.rotate("old-refresh-token"))
                .willReturn(Optional.of(new RefreshTokenStore.RotatedToken(1L, "new-refresh-token")));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.createAccessToken(1L, member.getRole())).willReturn("new-access-token");

        // when
        ReissueResponse response = authService.reissue("old-refresh-token");

        // then
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
    }

    @Test
    void 존재하지_않거나_만료된_RefreshToken으로_재발급하면_401_예외가_발생한다() {
        // given
        given(refreshTokenStore.rotate("invalid-refresh-token")).willReturn(Optional.empty());

        // when
        Throwable result = catchThrowable(() -> authService.reissue("invalid-refresh-token"));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }

    @Test
    void Redis_장애로_RefreshToken을_확인할_수_없으면_401로_재발급을_거부한다() {
        // given
        given(refreshTokenStore.rotate(anyString()))
                .willThrow(new org.springframework.data.redis.RedisConnectionFailureException("연결 실패"));
        Logger logger = (Logger) LoggerFactory.getLogger(AuthService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when
        Throwable result;
        try {
            result = catchThrowable(() -> authService.reissue("any-refresh-token"));
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("event=AUTH_REISSUE_FAILED");
            assertThat(event.getFormattedMessage()).contains("reason=REFRESH_TOKEN_STORE_UNAVAILABLE");
            assertThat(event.getFormattedMessage()).doesNotContain("any-refresh-token");
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(org.springframework.data.redis.RedisConnectionFailureException.class.getName());
        });
    }

    @Test
    void 로그아웃하면_Access_Token을_Blacklist에_등록하고_해당_회원의_RefreshToken을_삭제한다() {
        // given
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        Instant expiresAt = now.plusSeconds(600);
        given(clock.instant()).willReturn(now);
        given(jwtTokenProvider.parseAccessTokenClaims("access-token")).willReturn(
                new JwtTokenProvider.AccessTokenClaims(new AuthMember(1L, MemberRole.MEMBER), "jti-1", expiresAt));

        // when
        LogoutResponse response = authService.logout(1L, "access-token");

        // then
        assertThat(response.result()).isTrue();
        verify(accessTokenBlacklistStore).blacklist("jti-1", Duration.ofSeconds(600));
        verify(refreshTokenStore).deleteByMember(1L);
    }

    @Test
    void jti가_없는_토큰으로_로그아웃하면_Blacklist_등록은_건너뛰고_RefreshToken만_삭제한다() {
        // given: Issue #186 배포 이전에 발급된 Access Token(PR #187 리뷰) — jti가 없어 Blacklist에
        // 등록할 방법이 없지만, 로그아웃 자체(Refresh Token 삭제)는 그대로 성공해야 한다.
        given(jwtTokenProvider.parseAccessTokenClaims("legacy-access-token")).willReturn(
                new JwtTokenProvider.AccessTokenClaims(new AuthMember(1L, MemberRole.MEMBER), null, Instant.now()));

        // when
        LogoutResponse response = authService.logout(1L, "legacy-access-token");

        // then
        assertThat(response.result()).isTrue();
        verify(accessTokenBlacklistStore, org.mockito.Mockito.never()).blacklist(any(), any());
        verify(refreshTokenStore).deleteByMember(1L);
    }
}
