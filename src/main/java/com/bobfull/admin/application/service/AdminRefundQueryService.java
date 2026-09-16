package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.response.AdminRefundListItemResponse;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.response.PageResponse;
import com.bobfull.payment.domain.entity.Refund;
import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.infrastructure.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자용 전체 환불 현황을 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRefundQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RefundRepository refundRepository;

    public PageResponse<AdminRefundListItemResponse> getRefunds(String refundStatus, Pageable pageable) {
        RefundStatus status = parseStatus(refundStatus);
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
        Page<Refund> refunds = status == null
                ? refundRepository.findAll(sortedPageable)
                : refundRepository.findAllByStatus(status, sortedPageable);
        return PageResponse.from(refunds.map(AdminRefundListItemResponse::from));
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
}
