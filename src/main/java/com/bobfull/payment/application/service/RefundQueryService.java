package com.bobfull.payment.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.PaymentErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.presentation.dto.RefundResponse;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundQueryService {

    private final RefundRepository refundRepository;

    public RefundQueryService(RefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<RefundResponse> getMyRefunds(Long memberId, String refundStatus, Pageable pageable) {
        Pageable orderedPageable = ordered(pageable);
        RefundStatus status = parseStatus(refundStatus);
        Page<Refund> refunds = status == null
                ? refundRepository.findAllByPayment_MemberId(memberId, orderedPageable)
                : refundRepository.findAllByPayment_MemberIdAndStatus(memberId, status, orderedPageable);
        return PageResponse.from(refunds.map(RefundResponse::from));
    }

    @Transactional(readOnly = true)
    public RefundResponse getMyRefund(Long memberId, Long refundId) {
        Refund refund = refundRepository.findByIdAndPayment_MemberId(refundId, memberId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.REFUND_ID_NOT_FOUND));
        return RefundResponse.from(refund);
    }

    private RefundStatus parseStatus(String refundStatus) {
        if (refundStatus == null || refundStatus.isBlank()) {
            return null;
        }
        try {
            return RefundStatus.valueOf(refundStatus);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private Pageable ordered(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
