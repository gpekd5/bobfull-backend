package com.bobfull.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class AdminPaymentQueryServiceTest {

    @Mock private PaymentRepository paymentRepository;

    @InjectMocks private AdminPaymentQueryService service;

    @Test
    void EXPIRED_상태필터는_허용하지_않는다() {
        Pageable pageable = PageRequest.of(0, 20);

        Throwable result = catchThrowable(() -> service.getPayments("EXPIRED", pageable));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 필터가_없으면_전체_결제를_조회한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<com.bobfull.payment.domain.entity.Payment> emptyPage = new PageImpl<>(java.util.List.of(), pageable, 0);
        given(paymentRepository.findAll(any(Pageable.class))).willReturn(emptyPage);

        service.getPayments(null, pageable);

        verify(paymentRepository).findAll(any(Pageable.class));
    }

    @Test
    void PAID_필터가_있으면_상태별로_조회한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<com.bobfull.payment.domain.entity.Payment> emptyPage = new PageImpl<>(java.util.List.of(), pageable, 0);
        given(paymentRepository.findAllByStatus(eq(PaymentStatus.PAID), any(Pageable.class))).willReturn(emptyPage);

        service.getPayments("PAID", pageable);

        verify(paymentRepository).findAllByStatus(eq(PaymentStatus.PAID), any(Pageable.class));
    }

    @Test
    void 필터_유무와_관계없이_생성일_역순_id_역순으로_정렬한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<com.bobfull.payment.domain.entity.Payment> emptyPage = new PageImpl<>(java.util.List.of(), pageable, 0);
        given(paymentRepository.findAll(any(Pageable.class))).willReturn(emptyPage);
        given(paymentRepository.findAllByStatus(eq(PaymentStatus.PAID), any(Pageable.class))).willReturn(emptyPage);

        service.getPayments(null, pageable);
        service.getPayments("PAID", pageable);

        ArgumentCaptor<Pageable> noFilterCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findAll(noFilterCaptor.capture());
        assertThat(noFilterCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        ArgumentCaptor<Pageable> statusFilterCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findAllByStatus(eq(PaymentStatus.PAID), statusFilterCaptor.capture());
        assertThat(statusFilterCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
