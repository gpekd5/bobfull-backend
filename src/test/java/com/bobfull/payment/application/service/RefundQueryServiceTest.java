package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.presentation.response.RefundResponse;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
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
class RefundQueryServiceTest {

    @Mock private RefundRepository refundRepository;

    @Test
    void 본인결제에_연결된_환불만_결정적정렬로_조회한다() {
        // given
        Pageable requested = PageRequest.of(0, 20);
        Refund refund = refund(10L, "payment-id", 1L);
        given(refundRepository.findAllByPayment_MemberId(eq(1L), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(refund), requested, 1));

        // when
        PageResponse<RefundResponse> response = service().getMyRefunds(1L, null, requested);

        // then
        assertThat(response.content()).extracting(RefundResponse::paymentId).containsExactly("payment-id");
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).findAllByPayment_MemberId(eq(1L), captor.capture());
        assertThat(captor.getValue().getSort().toString()).isEqualTo("createdAt: DESC,id: DESC");
    }

    @Test
    void 환불상태로_본인환불목록을_필터링한다() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(refundRepository.findAllByPayment_MemberIdAndStatus(eq(1L), eq(RefundStatus.COMPLETED), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        service().getMyRefunds(1L, "COMPLETED", pageable);

        // then
        verify(refundRepository).findAllByPayment_MemberIdAndStatus(eq(1L), eq(RefundStatus.COMPLETED), any(Pageable.class));
    }

    @Test
    void 타인환불은_소유조건조회결과가없어_404를_반환한다() {
        // given
        given(refundRepository.findByIdAndPayment_MemberId(10L, 1L)).willReturn(java.util.Optional.empty());

        // when
        Throwable result = catchThrowable(() -> service().getMyRefund(1L, 10L));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(PaymentErrorCode.REFUND_ID_NOT_FOUND);
    }

    private RefundQueryService service() { return new RefundQueryService(refundRepository); }

    private Refund refund(Long id, String paymentId, Long memberId) {
        Payment payment = Payment.createReady(paymentId, memberId, 2L, null, PaymentPurpose.CREATE, 1,
                BigDecimal.TEN, Instant.parse("2026-07-30T00:10:00Z"));
        Refund refund = Refund.create(payment, BigDecimal.TEN, RefundStatus.COMPLETED,
                Instant.parse("2026-07-30T00:00:00Z"), Instant.parse("2026-07-30T00:01:00Z"),
                "test-key-my-refund", "test reason");
        ReflectionTestUtils.setField(refund, "id", id);
        return refund;
    }
}
