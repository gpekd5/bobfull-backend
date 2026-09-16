package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.response.AdminPaymentListItemResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
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

// 관리자용 전체 결제 현황을 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentQueryService {

    private static final Set<PaymentStatus> EXPOSED_FILTER_STATUSES =
            Set.of(PaymentStatus.READY, PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.REFUNDED);
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PaymentRepository paymentRepository;

    public PageResponse<AdminPaymentListItemResponse> getPayments(String paymentStatus, Pageable pageable) {
        PaymentStatus status = parseStatus(paymentStatus);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
        Page<Payment> payments = status == null
                ? paymentRepository.findAll(sortedPageable)
                : paymentRepository.findAllByStatus(status, sortedPageable);
        return PageResponse.from(payments.map(AdminPaymentListItemResponse::from));
    }

    private PaymentStatus parseStatus(String paymentStatus) {
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
}
