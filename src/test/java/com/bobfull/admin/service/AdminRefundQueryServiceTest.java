package com.bobfull.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import java.util.List;
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
class AdminRefundQueryServiceTest {

    @Mock private RefundRepository refundRepository;

    @InjectMocks private AdminRefundQueryService service;

    @Test
    void 유효하지_않은_상태필터는_400_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20);

        Throwable result = catchThrowable(() -> service.getRefunds("INVALID", pageable));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void COMPLETED_필터가_있으면_상태별로_조회한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Refund> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        given(refundRepository.findAllByStatus(eq(RefundStatus.COMPLETED), any(Pageable.class))).willReturn(emptyPage);

        service.getRefunds("COMPLETED", pageable);

        verify(refundRepository).findAllByStatus(eq(RefundStatus.COMPLETED), any(Pageable.class));
    }

    @Test
    void 필터가_없으면_전체_환불을_조회한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Refund> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        given(refundRepository.findAll(any(Pageable.class))).willReturn(emptyPage);

        service.getRefunds(null, pageable);

        verify(refundRepository).findAll(any(Pageable.class));
    }

    @Test
    void 필터_유무와_관계없이_생성일_역순_id_역순으로_정렬한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Refund> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        given(refundRepository.findAll(any(Pageable.class))).willReturn(emptyPage);
        given(refundRepository.findAllByStatus(eq(RefundStatus.COMPLETED), any(Pageable.class))).willReturn(emptyPage);

        service.getRefunds(null, pageable);
        service.getRefunds("COMPLETED", pageable);

        ArgumentCaptor<Pageable> noFilterCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).findAll(noFilterCaptor.capture());
        assertThat(noFilterCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        ArgumentCaptor<Pageable> statusFilterCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(refundRepository).findAllByStatus(eq(RefundStatus.COMPLETED), statusFilterCaptor.capture());
        assertThat(statusFilterCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
