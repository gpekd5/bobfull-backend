package com.bobfull.reservation.application.service;

import com.bobfull.payment.application.port.PaymentHoldReader;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 테이블 정원에서 결제 완료 참여 인원과 만료되지 않은 READY 임시 선점 인원을 차감해
 * 남은 참여 가능 인원을 계산한다(ADR 0001, docs/040-architecture/domain-dependencies.md §4).
 */
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
    private final PaymentHoldReader paymentHoldReader;

    /**
     * {@code CLOSED}(식사 종료로 생명주기가 끝난 예약)가 있으면 참여자 상태와 무관하게 0을
     * 반환한다(PR #178 리뷰 반영, Issue #175). 노쇼 처리로 `RESERVED` 참여자가 `NO_SHOW`로
     * 빠져 점유 합계가 줄어도, 이미 끝난 회차의 좌석이 다시 열려 재예약으로 이어지면 안 된다.
     */
    public int calculate(Long timeSlotId, Integer tableCapacity) {
        if (isClosed(timeSlotId)) {
            return 0;
        }
        int currentParticipantCount = activeParticipantCountFor(timeSlotId);
        return availableCapacity(timeSlotId, tableCapacity, currentParticipantCount);
    }

    /**
     * 호출자가 같은 회차의 활성 예약 참여자 합계를 이미 조회해 알고 있을 때 그 값을 재사용해
     * {@link #calculate}와 동일한 계산식으로 남은 좌석 수를 반환한다(Issue #61 Track B).
     * TimeSlotService.toAvailableDiningSessionResponse가 DTO의 currentParticipantCount를 만들기
     * 위해 이미 실행한 활성 예약 조회·참여자 합계 조회를 이 메서드에서 다시 실행하지 않도록 한다.
     * 계산식 자체는 바꾸지 않으며, 중복 조회만 제거하는 리팩터링이다.
     */
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
