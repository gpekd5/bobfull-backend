package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.presentation.dto.PaymentDetailResponse;
import com.bobfull.payment.presentation.dto.PaymentListResponse;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    @Mock private PaymentRepository paymentRepository;

    @Test
    void 본인_결제만_최신생성순과_내부PK내림차순으로_조회한다() {
        // given
        Payment payment = payment(10L, "payment-id", 1L, PaymentStatus.PAID);
        Pageable requested = PageRequest.of(0, 20);
        given(paymentRepository.findAllByMemberId(eq(1L), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(payment), requested, 1));

        // when
        PageResponse<PaymentListResponse> response = service().getMyPayments(1L, null, requested);

        // then
        assertThat(response.content()).extracting(PaymentListResponse::paymentId).containsExactly("payment-id");
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findAllByMemberId(eq(1L), captor.capture());
        assertThat(captor.getValue().getSort().toString()).isEqualTo("createdAt: DESC,id: DESC");
    }

    @Test
    void 허용된_결제상태로_본인목록을_필터링한다() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(paymentRepository.findAllByMemberIdAndStatus(eq(1L), eq(PaymentStatus.PAID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        service().getMyPayments(1L, "PAID", pageable);

        // then
        verify(paymentRepository).findAllByMemberIdAndStatus(eq(1L), eq(PaymentStatus.PAID), any(Pageable.class));
    }

    @Test
    void EXPIRED_결제상태필터는_허용하지_않는다() {
        // when
        Throwable result = catchThrowable(() -> service().getMyPayments(1L, "EXPIRED", PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void 알수없는_결제상태필터는_허용하지_않는다() {
        // when
        Throwable result = catchThrowable(() -> service().getMyPayments(1L, "UNKNOWN", PageRequest.of(0, 20)));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 결제식별자와_본인조건으로_상세를_조회한다() {
        // given
        Payment payment = payment(10L, "payment-id", 1L, PaymentStatus.PAID);
        given(paymentRepository.findByPaymentIdAndMemberId("payment-id", 1L)).willReturn(java.util.Optional.of(payment));

        // when
        PaymentDetailResponse response = service().getMyPayment(1L, "payment-id");

        // then
        assertThat(response.paymentId()).isEqualTo("payment-id");
        verify(paymentRepository).findByPaymentIdAndMemberId("payment-id", 1L);
    }

    @Test
    void 타인결제는_소유조건조회결과가없어_404를_반환한다() {
        // given
        given(paymentRepository.findByPaymentIdAndMemberId("other-payment", 1L)).willReturn(java.util.Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service().getMyPayment(1L, "other-payment"));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    private PaymentQueryService service() {
        return new PaymentQueryService(paymentRepository);
    }

    private Payment payment(Long id, String paymentId, Long memberId, PaymentStatus status) {
        Payment payment = Payment.createReady(paymentId, memberId, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.TEN, Instant.parse("2026-07-30T00:10:00Z"));
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "paidAt", Instant.parse("2026-07-30T00:00:00Z"));
        return payment;
    }
}
