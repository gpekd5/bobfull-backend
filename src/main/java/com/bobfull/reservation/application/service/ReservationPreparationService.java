package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.payment.application.command.CreateReadyPaymentCommand;
import com.bobfull.payment.application.result.CreateReadyPaymentResult;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.payment.application.port.PaymentHoldPort;
import com.bobfull.payment.application.port.ReadyPaymentPort;
import com.bobfull.reservation.presentation.response.ReservationAvailabilityResponse;
import com.bobfull.reservation.presentation.request.ReservationPrepareRequest;
import com.bobfull.reservation.presentation.response.ReservationPrepareResponse;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.application.port.ReservationTargetPort;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 예약 생성·추가 참여 가능 여부를 검증하고 결제 준비를 시작한다.
@Service
@RequiredArgsConstructor
public class ReservationPreparationService {

    // 식사가 끝난 회차가 다시 결제 준비되지 않도록 CLOSED 예약도 새 예약을 차단한다.
    private static final List<ReservationStatus> CREATE_BLOCKING_STATUSES = List.of(
            ReservationStatus.RECRUITING, ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLING, ReservationStatus.CLOSED);

    private final ReservationTargetPort reservationTargetPort;
    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final PaymentHoldPort paymentHoldReader;
    private final ReadyPaymentPort readyPaymentCreator;
    private final AvailableCapacityCalculator availableCapacityCalculator;

    // 현재 좌석과 결제 선점을 기준으로 예약 생성·참여의 사전 가능 여부를 반환한다.
    @Transactional(readOnly = true)
    public ReservationAvailabilityResponse checkAvailability(
            Long memberId, PaymentPurpose type, Long targetId, Integer partySize
    ) {
        validatePartySizeInput(partySize);
        ValidatedTarget target = (type == PaymentPurpose.CREATE)
                ? resolveCreateTarget(targetId, partySize, false)
                : resolveJoinTarget(memberId, targetId, partySize, false);
        return ReservationAvailabilityResponse.available(target.availableCapacity());
    }

    // 예약 대상을 잠가 최종 가능 여부를 확인한 뒤 READY 결제를 생성한다.
    @Transactional
    public ReservationPrepareResponse prepare(Long memberId, ReservationPrepareRequest request) {
        validatePartySizeInput(request.partySize());
        ValidatedTarget target = (request.type() == PaymentPurpose.CREATE)
                ? resolveCreateTarget(request.targetId(), request.partySize(), true)
                : resolveJoinTarget(memberId, request.targetId(), request.partySize(), true);

        BigDecimal amount = BigDecimal.valueOf(target.depositPerPerson()).multiply(BigDecimal.valueOf(request.partySize()));
        CreateReadyPaymentCommand command = new CreateReadyPaymentCommand(
                memberId, target.timeSlotId(), target.reservationId(), request.type(), request.partySize(), amount);
        CreateReadyPaymentResult result = readyPaymentCreator.createReadyPayment(command);
        return ReservationPrepareResponse.from(result);
    }

    // 락 순서: TimeSlot 단독(ADR 0001 "복수 비관적 락의 획득 순서" 참고).
    private ValidatedTarget resolveCreateTarget(Long timeSlotId, Integer partySize, boolean lock) {
        ReservationTargetPort.ReservationTarget target = reservationTargetPort.read(timeSlotId, lock);
        validatePartySizeAgainstCapacity(partySize, target.tableCapacity());
        validateNoActiveCreate(target.timeSlotId());

        int availableCapacity = availableCapacityCalculator.calculate(target.timeSlotId(), target.tableCapacity());
        return new ValidatedTarget(target.timeSlotId(), null, target.depositPerPerson(), availableCapacity);
    }

    private ValidatedTarget resolveJoinTarget(Long memberId, Long reservationId, Integer partySize, boolean lock) {
        // Reservation을 잠금 조회로 트랜잭션의 첫 쿼리로 만들어야 한다. MySQL REPEATABLE_READ에서는
        // 이후의 일반 SELECT(잔여 인원 합계 등)가 트랜잭션의 첫 조회 시점 스냅샷을 그대로 쓰기 때문에,
        // 잠금 없는 조회를 먼저 하면 TimeSlot 락을 기다렸다 풀려도 그 사이 상대가 커밋한 결과를
        // 못 보고 통과할 수 있다(ADR 0001).
        // 락 순서: Reservation → TimeSlot(ADR 0001 "복수 비관적 락의 획득 순서" 참고, 역순 금지).
        Reservation reservation = lock ? findReservationWithLockOrThrow(reservationId) : findReservationOrThrow(reservationId);
        ReservationTargetPort.ReservationTarget target = reservationTargetPort.read(reservation.getTimeSlotId(), lock);

        validateJoinable(reservation);
        validateNotAlreadyParticipating(reservation.getId(), memberId);
        validateNoActiveJoinReady(reservation.getId(), memberId);

        int availableCapacity = availableCapacityCalculator.calculate(target.timeSlotId(), target.tableCapacity());
        validatePartySizeAgainstRemainingCapacity(partySize, availableCapacity);

        return new ValidatedTarget(target.timeSlotId(), reservation.getId(), target.depositPerPerson(), availableCapacity);
    }

    private void validateNoActiveCreate(Long timeSlotId) {
        boolean blockingReservationExists = reservationRepository.existsByTimeSlotIdAndReservationStatusIn(
                timeSlotId, CREATE_BLOCKING_STATUSES);
        boolean activeCreateReadyExists = paymentHoldReader.existsActiveReadyPayment(timeSlotId, PaymentPurpose.CREATE);
        if (blockingReservationExists || activeCreateReadyExists) {
            throw new CustomException(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        }
    }

    private void validateJoinable(Reservation reservation) {
        if (!reservation.isActive() || reservation.getRecruitmentStatus() != RecruitmentStatus.OPEN) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    private void validateNotAlreadyParticipating(Long reservationId, Long memberId) {
        if (reservationParticipantRepository.existsByReservationIdAndMemberId(reservationId, memberId)) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    // 결제 완료 전에는 참여자가 없으므로 활성 JOIN READY 결제로 중복 결제 준비를 막는다.
    private void validateNoActiveJoinReady(Long reservationId, Long memberId) {
        if (paymentHoldReader.existsActiveJoinReadyPayment(reservationId, memberId)) {
            throw new CustomException(ReservationErrorCode.ACTIVE_RESERVATION_ALREADY_EXISTS);
        }
    }

    private void validatePartySizeInput(Integer partySize) {
        if (partySize == null || partySize < 1) {
            throw new CustomException(ReservationErrorCode.INVALID_PARTY_SIZE);
        }
    }

    private void validatePartySizeAgainstCapacity(Integer partySize, Integer tableCapacity) {
        if (partySize > tableCapacity) {
            throw new CustomException(ReservationErrorCode.INVALID_PARTY_SIZE);
        }
    }

    private void validatePartySizeAgainstRemainingCapacity(Integer partySize, int availableCapacity) {
        if (partySize > availableCapacity) {
            throw new CustomException(ReservationErrorCode.INSUFFICIENT_REMAINING_CAPACITY);
        }
    }

    private Reservation findReservationOrThrow(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
    }

    private Reservation findReservationWithLockOrThrow(Long reservationId) {
        return reservationRepository.findWithLockById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
    }

    private record ValidatedTarget(Long timeSlotId, Long reservationId, Integer depositPerPerson, int availableCapacity) {
    }
}
