package com.bobfull.auth.application.service;

import com.bobfull.auth.presentation.dto.LoginRequest;
import com.bobfull.auth.presentation.dto.LoginResponse;
import com.bobfull.auth.presentation.dto.LogoutResponse;
import com.bobfull.auth.presentation.dto.ReissueResponse;
import com.bobfull.auth.presentation.dto.SignupOwnerRequest;
import com.bobfull.auth.presentation.dto.SignupResponse;
import com.bobfull.auth.presentation.dto.SignupUserRequest;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.bobfull.auth.infrastructure.redis.RefreshTokenStore;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.member.domain.exception.MemberErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입·로그인과 Refresh Token 재발급·로그아웃을 담당한다(Issue #125, #186).
 * 이메일·전화번호·사업자등록번호 중복은 저장 전 사전 검사로 우선 차단하고,
 * 동시 가입 경쟁으로 사전 검사를 통과한 뒤 DB UNIQUE 제약에 걸리면
 * DataIntegrityViolationException을 같은 중복 ErrorCode로 변환한다.
 * Refresh Token은 Redis에만 저장하며(회원당 1건, 재발급마다 회전), 재발급 중 Redis 조회 실패는
 * 무효 토큰과 동일하게 401로 거부한다(fail-closed). 로그아웃의 Redis 실패(Blacklist 등록·Refresh Token
 * 삭제 모두)는 감추지 않고 전파한다 — 인증 필터의 매 요청 Blacklist *조회*만 Fail-open이고, 로그아웃
 * 자체의 등록 실패를 성공으로 위장하지는 않는다(Issue #186 Q5).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final BusinessMetricRecorder businessMetricRecorder;
    private final AccessTokenBlacklistStore accessTokenBlacklistStore;
    private final Clock clock;

    public AuthService(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore,
            BusinessMetricRecorder businessMetricRecorder,
            AccessTokenBlacklistStore accessTokenBlacklistStore,
            Clock clock
    ) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.businessMetricRecorder = businessMetricRecorder;
        this.accessTokenBlacklistStore = accessTokenBlacklistStore;
        this.clock = clock;
    }

    @Transactional
    public SignupResponse signupMember(SignupUserRequest request) {
        validateEmailNotDuplicated(request.email());
        validatePhoneNumberNotDuplicated(request.phoneNumber());

        Member member = Member.createMember(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phoneNumber()
        );

        Member savedMember = saveOrThrowDuplicate(member, request.email(), request.phoneNumber(), null);
        return SignupResponse.from(savedMember);
    }

    @Transactional
    public SignupResponse signupOwner(SignupOwnerRequest request) {
        validateEmailNotDuplicated(request.email());
        validatePhoneNumberNotDuplicated(request.phoneNumber());
        validateBusinessNumberNotDuplicated(request.businessNumber());

        Member member = Member.createOwner(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phoneNumber(),
                request.businessNumber()
        );

        Member savedMember = saveOrThrowDuplicate(member, request.email(), request.phoneNumber(), request.businessNumber());
        return SignupResponse.from(savedMember);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    log.warn("event=LOGIN_FAILED reason=INVALID_CREDENTIALS");
                    businessMetricRecorder.increment(BusinessMetricEvent.LOGIN_FAILED);
                    return new CustomException(MemberErrorCode.INVALID_CREDENTIALS);
                });

        if (!passwordEncoder.matches(request.password(), member.getPasswordHash())) {
            log.warn("event=LOGIN_FAILED reason=INVALID_CREDENTIALS");
            businessMetricRecorder.increment(BusinessMetricEvent.LOGIN_FAILED);
            throw new CustomException(MemberErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        String refreshToken = refreshTokenStore.issue(member.getId());
        return LoginResponse.of(accessToken, refreshToken);
    }

    /**
     * Refresh Token을 검증하고 회전한 뒤 새 Access·Refresh Token을 발급한다.
     * Redis 조회 실패(연결 장애 등)도 무효 토큰과 동일하게 401로 응답한다(Human 결정 Q3, fail-closed).
     * 로그아웃과 달리 재발급은 신원 확인 자체가 목적이라 장애를 감추지 않고 거부로 처리한다.
     */
    @Transactional(readOnly = true)
    public ReissueResponse reissue(String refreshToken) {
        RefreshTokenStore.RotatedToken rotated = rotateOrRejectOnFailure(refreshToken);
        Member member = memberRepository.findById(rotated.memberId())
                .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));

        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        return new ReissueResponse(accessToken, rotated.refreshToken());
    }

    private RefreshTokenStore.RotatedToken rotateOrRejectOnFailure(String refreshToken) {
        try {
            return refreshTokenStore.rotate(refreshToken)
                    .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
        } catch (DataAccessException e) {
            log.error("event=AUTH_REISSUE_FAILED reason=REFRESH_TOKEN_STORE_UNAVAILABLE", e);
            businessMetricRecorder.increment(BusinessMetricEvent.AUTH_REISSUE_FAILED);
            throw new CustomException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 현재 Access Token의 jti를 남은 유효시간만큼 Blacklist에 등록해 즉시 무효화하고,
     * 해당 회원의 Refresh Token을 삭제한다(Issue #186). accessToken은 이 요청이 인증 필터를
     * 통과한 그 토큰이므로 서명·만료 재검증은 항상 성공한다. jti가 없는 토큰(이 기능 배포 이전에
     * 발급된 토큰, PR #187 리뷰)은 Blacklist에 등록할 방법이 없어 그 등록만 건너뛰고 Refresh Token
     * 삭제는 그대로 수행한다 — 로그아웃 자체가 실패하지는 않는다.
     */
    public LogoutResponse logout(Long memberId, String accessToken) {
        JwtTokenProvider.AccessTokenClaims claims = jwtTokenProvider.parseAccessTokenClaims(accessToken);
        if (claims.jti() != null) {
            Duration remaining = Duration.between(clock.instant(), claims.expiresAt());
            accessTokenBlacklistStore.blacklist(claims.jti(), remaining);
        }
        refreshTokenStore.deleteByMember(memberId);
        return LogoutResponse.success();
    }

    private Member saveOrThrowDuplicate(Member member, String email, String phoneNumber, String businessNumber) {
        try {
            return memberRepository.save(member);
        } catch (DataIntegrityViolationException e) {
            throw resolveDuplicateException(email, phoneNumber, businessNumber, e);
        }
    }

    private CustomException resolveDuplicateException(
            String email,
            String phoneNumber,
            String businessNumber,
            DataIntegrityViolationException cause
    ) {
        if (memberRepository.existsByEmail(email)) {
            return new CustomException(MemberErrorCode.DUPLICATE_EMAIL);
        }
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            return new CustomException(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
        }
        if (businessNumber != null && memberRepository.existsByBusinessNumber(businessNumber)) {
            return new CustomException(MemberErrorCode.DUPLICATE_BUSINESS_NUMBER);
        }
        throw cause;
    }

    private void validateEmailNotDuplicated(String email) {
        if (memberRepository.existsByEmail(email)) {
            throw new CustomException(MemberErrorCode.DUPLICATE_EMAIL);
        }
    }

    private void validatePhoneNumberNotDuplicated(String phoneNumber) {
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new CustomException(MemberErrorCode.DUPLICATE_PHONE_NUMBER);
        }
    }

    private void validateBusinessNumberNotDuplicated(String businessNumber) {
        if (memberRepository.existsByBusinessNumber(businessNumber)) {
            throw new CustomException(MemberErrorCode.DUPLICATE_BUSINESS_NUMBER);
        }
    }
}
