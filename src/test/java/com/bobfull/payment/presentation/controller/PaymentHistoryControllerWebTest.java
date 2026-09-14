package com.bobfull.payment.presentation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.member.domain.entity.MemberRole;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.payment.presentation.dto.PaymentListResponse;
import com.bobfull.payment.presentation.dto.RefundResponse;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.service.PaymentCompletionService;
import com.bobfull.payment.application.service.PaymentQueryService;
import com.bobfull.payment.application.service.RefundQueryService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {PaymentController.class, MemberPaymentHistoryController.class, RefundController.class})
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=payment-history-controller-web-test-secret-key-please-keep-long",
        "jwt.access-token-expiration-seconds=1800"
})
class PaymentHistoryControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private PaymentCompletionService paymentCompletionService;
    @MockitoBean private PaymentQueryService paymentQueryService;
    @MockitoBean private RefundQueryService refundQueryService;

    @Test
    void 인증된_회원이_내결제목록을_페이징형식으로_조회한다() throws Exception {
        // given
        PaymentListResponse item = new PaymentListResponse("payment-id", 1L, 10L, PaymentPurpose.CREATE, 2,
                BigDecimal.valueOf(30000), "KRW", PaymentStatus.PAID, OffsetDateTime.parse("2026-07-25T17:30:00+09:00"));
        given(paymentQueryService.getMyPayments(eq(1L), eq("PAID"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/members/me/payments?paymentStatus=PAID").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].paymentId", is("payment-id")))
                .andExpect(jsonPath("$.data.totalElements", is(1)));
    }

    @Test
    void 인증된_회원이_내환불목록을_페이징형식으로_조회한다() throws Exception {
        // given
        RefundResponse item = new RefundResponse(1L, "payment-id", 10L, BigDecimal.valueOf(30000), RefundStatus.COMPLETED,
                OffsetDateTime.parse("2026-07-25T19:00:00+09:00"), OffsetDateTime.parse("2026-07-25T19:05:00+09:00"));
        given(refundQueryService.getMyRefunds(eq(1L), eq("COMPLETED"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        // when & then
        mockMvc.perform(get("/api/members/me/refunds?refundStatus=COMPLETED").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].refundId", is(1)))
                .andExpect(jsonPath("$.data.totalPages", is(1)));
    }

    @Test
    void 타인결제상세는_404를_반환한다() throws Exception {
        // given
        given(paymentQueryService.getMyPayment(1L, "other-payment"))
                .willThrow(new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/payments/other-payment").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PAYMENT_NOT_FOUND")));
    }

    @Test
    void 타인환불상세는_404를_반환한다() throws Exception {
        // given
        given(refundQueryService.getMyRefund(1L, 2L))
                .willThrow(new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/refunds/2").with(authentication(memberAuthentication(1L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("REFUND_ID_NOT_FOUND")));
    }

    @Test
    void 인증없이_결제이력을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/members/me/payments")).andExpect(status().isUnauthorized());
    }

    private Authentication memberAuthentication(Long memberId) {
        AuthMember authMember = new AuthMember(memberId, MemberRole.MEMBER);
        return new UsernamePasswordAuthenticationToken(authMember, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
}
