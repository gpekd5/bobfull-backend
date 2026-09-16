package com.bobfull.reservation.application.service;

import com.bobfull.payment.application.port.PaymentHoldPort;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 결제 완료 인원과 READY 좌석 선점을 반영해 남은 참여 가능 인원을 계산한다.
@Service
@RequiredArgsConstructor
public class AvailableCapacityCalculator {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLING);
    private static final List<ReservationStatus> CLOSED_STATUS = List.of(ReservationStatus.CLOSED);
    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final PaymentHoldPort paymentHoldReader;

    // 노쇼 처리로 점유 인원이 줄어도 종료된 회차가 다시 예약 가능해지지 않도록 CLOSED는 0을 반환한다.
    public int calculate(Long timeSlotId, Integer tableCapacity) {
        if (isClosed(timeSlotId)) {
            return 0;
        }
        int currentParticipantCount = activeParticipantCountFor(timeSlotId);
        return availableCapacity(timeSlotId, tableCapacity, currentParticipantCount);
    }

    // 이미 조회한 참여자 합계를 재사용해 같은 회차의 중복 조회 없이 동일한 계산식을 적용한다.
    public int calculateWithKnownParticipantCount(Long timeSlotId, Integer tableCapacity, int currentParticipantCount) {
        if (isClosed(timeSlotId)) {
            return 0;
        }
        return availableCapacity(timeSlotId, tableCapacity, currentParticipantCount);
    }

    private boolean isClosed(Long timeSlotId) {
        return reservationRepository.existsByTimeSlotIdAndReservationStatusIn(timeSlotId, CLOSED_STATUS);
    }

    private int activeParticipantCountFor(Long timeSlotId) {
        return reservationRepository
                .findByTimeSlotIdAndReservationStatusIn(timeSlotId, ACTIVE_STATUSES)
                .map(this::activeParticipantCount)
                .orElse(0);
    }

    private int availableCapacity(Long timeSlotId, Integer tableCapacity, int currentParticipantCount) {
        int pendingHoldCount = paymentHoldReader.sumActiveReadyPartySize(timeSlotId);
        return ReservationCapacityPolicy.availableCapacity(tableCapacity, currentParticipantCount, pendingHoldCount);
    }

    private int activeParticipantCount(Reservation reservation) {
        return reservationParticipantRepository.sumPartySizeByStatuses(reservation.getId(), OCCUPYING_STATUSES);
    }
}
