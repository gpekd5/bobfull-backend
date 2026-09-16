package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.presentation.response.PaymentDetailResponse;
import com.bobfull.payment.presentation.response.PaymentListResponse;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 인증 사용자가 조회할 수 있는 결제 목록과 상세 정보를 제공한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private static final Set<PaymentStatus> EXPOSED_FILTER_STATUSES =
            Set.of(PaymentStatus.READY, PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.REFUNDED);

    private final PaymentRepository paymentRepository;

    public PageResponse<PaymentListResponse> getMyPayments(Long memberId, String paymentStatus, Pageable pageable) {
        Pageable orderedPageable = ordered(pageable);
        PaymentStatus status = parseExposedStatus(paymentStatus);
        Page<Payment> payments = status == null
                ? paymentRepository.findAllByMemberId(memberId, orderedPageable)
                : paymentRepository.findAllByMemberIdAndStatus(memberId, status, orderedPageable);
        return PageResponse.from(payments.map(PaymentListResponse::from));
    }

    public PaymentDetailResponse getMyPayment(Long memberId, String paymentId) {
        Payment payment = paymentRepository.findByPaymentIdAndMemberId(paymentId, memberId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return PaymentDetailResponse.from(payment);
    }

    private PaymentStatus parseExposedStatus(String paymentStatus) {
        if (paymentStatus == null || paymentStatus.isBlank()) {
            return null;
        }
        try {
            PaymentStatus status = PaymentStatus.valueOf(paymentStatus);
            if (!EXPOSED_FILTER_STATUSES.contains(status)) {
                throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
            }
            return status;
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private Pageable ordered(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
