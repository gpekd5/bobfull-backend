package com.bobfull.restaurant.sharedtable.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.sharedtable.domain.exception.SharedTableErrorCode;
import com.bobfull.restaurant.sharedtable.application.port.SharedTableUsagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회차·예약 도메인이 연결될 때 합석 테이블 변경 가능 여부를 검증하는 경계다.
 */
@Service
@RequiredArgsConstructor
public class SharedTableUsageValidator {

    private final SharedTableUsagePort sharedTableUsagePort;

    public void validateCapacityChangeAllowed(Long tableId) {
        if (sharedTableUsagePort.hasActiveReservation(tableId)) {
            throw new CustomException(SharedTableErrorCode.TABLE_HAS_RESERVATION);
        }
    }

    public void validateDeletionAllowed(Long tableId) {
        if (sharedTableUsagePort.hasDiningSession(tableId)) {
            throw new CustomException(SharedTableErrorCode.TABLE_HAS_DINING_SESSION);
        }
    }
}
