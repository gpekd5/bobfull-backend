package com.bobfull.auth.application.service;

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
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.auth.infrastructure.jwt.JwtTokenProvider;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회원가입·로그인과 Refresh Token 재발급·로그아웃 흐름을 담당한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final BusinessMetricRecorder businessMetricRecorder;
    private final AccessTokenBlacklistStore accessTokenBlacklistStore;
    private final Clock clock;

    // 이메일과 휴대전화번호 중복을 검증하고 일반 회원 계정을 생성한다.
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

    // 이메일·휴대전화번호·사업자번호 중복을 검증하고 식당 소유자 계정을 생성한다.
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

    // 자격 증명을 검증하고 기존 Refresh Token을 교체해 새 인증 토큰을 발급한다.
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

    // Refresh Token을 검증·회전하고 새 Access Token을 발급한다.
    @Transactional(readOnly = true)
    public ReissueResponse reissue(String refreshToken) {
        RefreshTokenStore.RotatedToken rotated = rotateOrRejectOnFailure(refreshToken);
        Member member = memberRepository.findById(rotated.memberId())
                .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));

        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getRole());
        return new ReissueResponse(accessToken, rotated.refreshToken());
    }

    private RefreshTokenStore.RotatedToken rotateOrRejectOnFailure(String refreshToken) {
        // 재발급은 신원 확인 경계이므로 Redis 장애도 무효 토큰과 같이 거부한다.
        try {
            return refreshTokenStore.rotate(refreshToken)
                    .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
        } catch (DataAccessException e) {
            log.error("event=AUTH_REISSUE_FAILED reason=REFRESH_TOKEN_STORE_UNAVAILABLE", e);
            businessMetricRecorder.increment(BusinessMetricEvent.AUTH_REISSUE_FAILED);
            throw new CustomException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    // 현재 Access Token을 즉시 무효화하고 회원의 Refresh Token을 삭제한다.
    public LogoutResponse logout(Long memberId, String accessToken) {
        // 인증 필터를 통과한 현재 요청의 토큰이므로 동일한 검증 기준으로 Claim을 읽는다.
        JwtTokenProvider.AccessTokenClaims claims = jwtTokenProvider.parseAccessTokenClaims(accessToken);
        // 저장소 실패를 성공으로 감추지 않기 위해 Blacklist 등록과 토큰 삭제 오류를 그대로 전파한다.
        // jti가 없는 호환 토큰은 Blacklist 등록만 건너뛰고 Refresh Token 삭제는 계속한다.
        if (claims.jti() != null) {
            Duration remaining = Duration.between(clock.instant(), claims.expiresAt());
            accessTokenBlacklistStore.blacklist(claims.jti(), remaining);
        }
        refreshTokenStore.deleteByMember(memberId);
        return LogoutResponse.success();
    }

    private Member saveOrThrowDuplicate(Member member, String email, String phoneNumber, String businessNumber) {
        // 사전 검사를 통과한 동시 가입 경쟁은 DB UNIQUE 제약으로 최종 차단한다.
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
