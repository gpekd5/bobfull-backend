package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.notification.infrastructure.outbox.EmailOutboxEventService;
import com.bobfull.reservation.domain.CancellationScope;
import com.bobfull.reservation.presentation.request.ReservationCancellationRequest;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.RecruitmentStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import com.bobfull.reservation.application.port.ReservationCancellationRefundPort;
import com.bobfull.reservation.application.port.ReservationCapacityPort;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 예약 취소 조건을 검증하고 환불 전 상태 전이를 짧은 잠금 트랜잭션에서 확정한다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReservationCancellationTransactionService {

    private static final Duration CANCELLATION_DEADLINE = Duration.ofHours(2);
    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);
    private static final String RECRUITMENT_FAILURE_REASON = "모집 마감 기준 인원 미달로 자동 취소되었습니다";

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final ReservationCapacityPort reservationCapacityPort;
    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final Clock clock;
    private final EmailOutboxEventService emailOutboxEventService;

    // Reservation만 잠가 취소를 접수한다. 외부 환불은 커밋 뒤 호출해 Payment → Reservation 완료
    // 흐름과 이 트랜잭션의 락 보유 구간이 겹치지 않게 한다(ADR 0001).
    public CancellationAcceptance accept(Long memberId, Long reservationId, ReservationCancellationRequest request) {
        Reservation reservation = findReservationWithLockOrThrow(reservationId);
        validateReservationCancellable(reservation);

        ReservationParticipant actingParticipant = findParticipantOrThrow(reservationId, memberId);
        validateParticipantCancellable(actingParticipant);

        validateCancellationDeadline(reservation.getTimeSlotId());

        String reason = request.reason();
        CancellationAcceptance acceptance = reservation.isCreatedBy(memberId)
                ? acceptEntireReservationCancellation(reservation, actingParticipant, reason)
                : acceptParticipantCancellation(reservation, actingParticipant, reason);
        log.info("event=RESERVATION_CANCELLATION_REQUESTED reservationId={} participantId={} memberId={} "
                        + "scope={} afterReservationStatus={} afterParticipantStatus={}",
                acceptance.reservationId(), acceptance.actingParticipantId(), memberId, acceptance.scope(),
                reservation.getReservationStatus(), actingParticipant.getParticipationStatus());
        return acceptance;
    }

    // 전체 참여자를 한 명령으로 묶어 참여자별 환불 요청에서 생길 수 있는 부분 성공을 피한다.
    private CancellationAcceptance acceptEntireReservationCancellation(
            Reservation reservation, ReservationParticipant actingParticipant, String reason
    ) {
        List<Long> participantIds = transitionAllValidParticipantsToCancelRequested(reservation, reason);
        ReservationCancellationRefundPort.RefundRequestCommand command =
                new ReservationCancellationRefundPort.RefundRequestCommand(
                        reservation.getId(), participantIds, actingParticipant.getMemberId(), reason);

        return new CancellationAcceptance(
                reservation.getId(), actingParticipant.getId(), CancellationScope.RESERVATION, command);
    }

    // 식당 사유의 예약 전체 취소를 접수한다. 회원 취소와 달리 2시간 기한은 적용하지 않는다.
    public OwnerCancellationAcceptance acceptByOwner(Long ownerMemberId, Long reservationId, String reason) {
        Reservation reservation = findReservationWithLockOrThrow(reservationId);
        validateOwnership(reservation, ownerMemberId);
        validateReservationCancellableByOwner(reservation);

        List<Long> participantIds = transitionAllValidParticipantsToCancelRequested(reservation, reason);
        ReservationCancellationRefundPort.RefundRequestCommand command =
                new ReservationCancellationRefundPort.RefundRequestCommand(
                        reservation.getId(), participantIds, ownerMemberId, reason);

        log.info("event=RESERVATION_CANCELLATION_REQUESTED reservationId={} actorId={} scope=RESERVATION "
                        + "trigger=OWNER_CANCEL participantCount={} afterReservationStatus={}",
                reservation.getId(), ownerMemberId, participantIds.size(), reservation.getReservationStatus());
        return new OwnerCancellationAcceptance(reservation.getId(), command);
    }

    // 유효 참여자 전원을 환불 대기 상태로 바꾸고 예약 전체 취소를 시작한다.
    private List<Long> transitionAllValidParticipantsToCancelRequested(Reservation reservation, String reason) {
        List<ReservationParticipant> validParticipants = reservationParticipantRepository
                .findAllByReservationIdAndParticipationStatus(reservation.getId(), ParticipationStatus.RESERVED);
        validParticipants.forEach(participant -> participant.requestCancel(reason));
        reservation.startCancelling();
        return validParticipants.stream().map(ReservationParticipant::getId).toList();
    }

    // 연결 대상 누락으로 예약 존재 여부가 노출되지 않도록 모두 RESERVATION_ID_NOT_FOUND로 처리하고,
    // 연결 체인이 온전한 경우에만 실제 소유권 불일치를 ACCESS_DENIED로 구분한다(ADR 0005).
    private void validateOwnership(Reservation reservation, Long ownerMemberId) {
        TimeSlot timeSlot = timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        SharedTable sharedTable = sharedTableRepository.findByIdAndDeletedAtIsNull(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(sharedTable.getRestaurantId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    // 식당 취소에는 시간 제한이 없으므로 CLOSED를 명시적으로 막아 종료된 예약의 환불을 방지한다.
    private void validateReservationCancellableByOwner(Reservation reservation) {
        if (reservation.isCancelled() || reservation.isCancelling() || reservation.isClosed()) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    // 모집 마감 시점의 인원을 다시 확인해 모집 확정 또는 전체 취소를 한 번만 접수한다.
    public RecruitmentDeadlineAcceptance acceptRecruitmentDeadline(Long reservationId) {
        Reservation reservation = findReservationWithLockOrThrow(reservationId);
        // 후보 조회 뒤 다른 실행이 먼저 처리했을 수 있어 잠금 상태에서 다시 검사한다.
        // 이 가드는 같은 결과 이메일 Outbox가 중복 생성되는 것도 막는다.
        if (reservation.getRecruitmentStatus() != RecruitmentStatus.OPEN || !reservation.isActive()) {
            return new RecruitmentDeadlineAcceptance(reservationId, RecruitmentDeadlineOutcome.ALREADY_PROCESSED, null);
        }
        reservation.closeRecruitment();

        int tableCapacity = reservationCapacityPort.readTableCapacity(reservation.getTimeSlotId());
        int currentCount = reservationParticipantRepository.sumPartySizeByStatuses(reservation.getId(), OCCUPYING_STATUSES);
        if (currentCount >= ReservationCapacityPolicy.confirmationThreshold(tableCapacity)) {
            // 상태 변경과 이메일 Outbox를 함께 커밋해 확정된 결과만 후속 발송한다.
            emailOutboxEventService.enqueue(OutboxEventType.EMAIL_RECRUITMENT_CONFIRMED, reservationId,
                    reservationParticipantRepository.findAllByReservationIdAndParticipationStatus(reservationId, ParticipationStatus.RESERVED));
            return new RecruitmentDeadlineAcceptance(reservationId, RecruitmentDeadlineOutcome.CLOSED_ONLY, null);
        }

        List<ReservationParticipant> participants = reservationParticipantRepository
                .findAllByReservationIdAndParticipationStatus(reservationId, ParticipationStatus.RESERVED);
        List<Long> participantIds = transitionAllValidParticipantsToCancelRequested(reservation, RECRUITMENT_FAILURE_REASON);
        ReservationCancellationRefundPort.RefundRequestCommand command =
                new ReservationCancellationRefundPort.RefundRequestCommand(
                        reservation.getId(), participantIds, reservation.getCreatorMemberId(), RECRUITMENT_FAILURE_REASON);
        emailOutboxEventService.enqueue(OutboxEventType.EMAIL_RECRUITMENT_CANCELLED, reservation.getId(), participants);
        log.info("event=RESERVATION_CANCELLATION_REQUESTED reservationId={} actorId=SYSTEM scope=RESERVATION "
                        + "trigger=RECRUITMENT_DEADLINE participantCount={} afterReservationStatus={}",
                reservation.getId(), participantIds.size(), reservation.getReservationStatus());
        return new RecruitmentDeadlineAcceptance(reservationId, RecruitmentDeadlineOutcome.CANCELLED, command);
    }

    // 추가 참여 취소가 마감된 예약의 성사 기준을 깨면 예약 전체 취소로 전환한다.
    private CancellationAcceptance acceptParticipantCancellation(
            Reservation reservation, ReservationParticipant actingParticipant, String reason
    ) {
        if (reservation.getRecruitmentStatus() == RecruitmentStatus.CLOSED
                && willFallBelowThresholdAfterCancel(reservation, actingParticipant)) {
            return acceptEntireReservationCancellation(reservation, actingParticipant, reason);
        }

        // 환불 대기 참여자는 계속 좌석을 점유하므로 예약 상태 재계산은 환불 완료 뒤에 수행한다.
        actingParticipant.requestCancel(reason);

        ReservationCancellationRefundPort.RefundRequestCommand command =
                new ReservationCancellationRefundPort.RefundRequestCommand(
                        reservation.getId(), List.of(actingParticipant.getId()), actingParticipant.getMemberId(), reason);

        return new CancellationAcceptance(
                reservation.getId(), actingParticipant.getId(), CancellationScope.PARTICIPATION, command);
    }

    private boolean willFallBelowThresholdAfterCancel(Reservation reservation, ReservationParticipant actingParticipant) {
        int tableCapacity = reservationCapacityPort.readTableCapacity(reservation.getTimeSlotId());
        int countAfterCancel = reservationParticipantRepository
                .sumPartySizeByStatuses(reservation.getId(), OCCUPYING_STATUSES) - actingParticipant.getPartySize();
        return countAfterCancel < ReservationCapacityPolicy.confirmationThreshold(tableCapacity);
    }

    // 개별 참여자의 환불 완료 뒤 남은 점유 인원으로 예약 성사 상태를 다시 계산한다.
    void recalculateAfterCompletion(Reservation reservation) {
        int tableCapacity = reservationCapacityPort.readTableCapacity(reservation.getTimeSlotId());
        // 잠금 없는 SUM 집계 대신 잠금 조회로 합산한다. 이 read가 속한 트랜잭션은
        // 그보다 앞서 RefundTransactionService의 잠금 없는 LAZY 로딩으로 REPEATABLE READ 스냅샷이
        // 이미 고정돼 있어, 뒤늦게 Reservation 행 락을 잡아도 SUM 집계는 그 옛 스냅샷을 읽는다.
        int currentCount = reservationParticipantRepository
                .findAllWithLockByReservationIdAndParticipationStatusIn(reservation.getId(), OCCUPYING_STATUSES)
                .stream()
                .mapToInt(ReservationParticipant::getPartySize)
                .sum();
        if (currentCount >= ReservationCapacityPolicy.confirmationThreshold(tableCapacity)) {
            reservation.confirm();
        } else {
            reservation.revertToRecruiting();
        }
    }

    private void validateReservationCancellable(Reservation reservation) {
        if (reservation.isCancelled() || reservation.isCancelling()) {
            throw new CustomException(ReservationErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
    }

    private void validateCancellationDeadline(Long timeSlotId) {
        Instant startAt = reservationCapacityPort.readTimeSlotStartAt(timeSlotId);
        Instant deadline = startAt.minus(CANCELLATION_DEADLINE);
        if (Instant.now(clock).isAfter(deadline)) {
            throw new CustomException(ReservationErrorCode.CANCELLATION_DEADLINE_PASSED);
        }
    }

    private void validateParticipantCancellable(ReservationParticipant participant) {
        if (participant.getParticipationStatus() == ParticipationStatus.CANCELLED) {
            throw new CustomException(ReservationErrorCode.PARTICIPATION_ALREADY_CANCELLED);
        }
        if (!participant.isCancellable()) {
            throw new CustomException(ReservationErrorCode.CANCELLATION_NOT_ALLOWED);
        }
    }

    // 잠긴 예약에 참여자가 전혀 없으면 정합성 오류로, 다른 회원의 참여만 있으면 권한 오류로 구분한다.
    private ReservationParticipant findParticipantOrThrow(Long reservationId, Long memberId) {
        return reservationParticipantRepository.findByReservationIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> {
                    if (reservationParticipantRepository.existsByReservationId(reservationId)) {
                        return new CustomException(CommonErrorCode.ACCESS_DENIED);
                    }
                    return new CustomException(ReservationErrorCode.PARTICIPATION_NOT_FOUND);
                });
    }

    private Reservation findReservationWithLockOrThrow(Long reservationId) {
        return reservationRepository.findWithLockById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
    }

    public record CancellationAcceptance(
            Long reservationId,
            Long actingParticipantId,
            CancellationScope scope,
            ReservationCancellationRefundPort.RefundRequestCommand refundCommand
    ) {
    }

    public record OwnerCancellationAcceptance(
            Long reservationId,
            ReservationCancellationRefundPort.RefundRequestCommand refundCommand
    ) {
    }

    public enum RecruitmentDeadlineOutcome {
        ALREADY_PROCESSED, CLOSED_ONLY, CANCELLED
    }

    public record RecruitmentDeadlineAcceptance(
            Long reservationId,
            RecruitmentDeadlineOutcome outcome,
            ReservationCancellationRefundPort.RefundRequestCommand refundCommand
    ) {
    }
}
