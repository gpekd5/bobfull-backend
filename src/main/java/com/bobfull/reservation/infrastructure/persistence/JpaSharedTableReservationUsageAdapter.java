package com.bobfull.reservation.infrastructure.persistence;

import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.sharedtable.application.port.SharedTableReservationUsagePort;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 합석 테이블 삭제를 막아야 하는 예약 상태의 정의를 예약 도메인에 유지한 채 합석 테이블
 * 정책에 제공한다. {@code CLOSED}(식사 종료로 생명주기가 끝난 예약)도 포함한다(PR #178 리뷰
 * 반영, Issue #175) — 연결된 회차에 노쇼 이력이 남아 있는 테이블이 삭제되면 이력·소유권 조회
 * 체인(NoShowService 등)이 깨질 수 있다.
 */
@Component
@RequiredArgsConstructor
public class JpaSharedTableReservationUsageAdapter implements SharedTableReservationUsagePort {

    private static final List<ReservationStatus> DELETION_BLOCKING_STATUSES = List.of(
            ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLING, ReservationStatus.CLOSED);

    private final ReservationRepository reservationRepository;

    @Override
    public boolean hasActiveReservation(Collection<Long> timeSlotIds) {
        return !timeSlotIds.isEmpty()
                && reservationRepository.existsByTimeSlotIdInAndReservationStatusIn(timeSlotIds, DELETION_BLOCKING_STATUSES);
    }
}
