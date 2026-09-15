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

/**
 * 예약 취소 접수를 짧은 잠금 트랜잭션으로 처리한다(Issue #44 최종 계약). 환불 outbound port 호출은
 * 이 트랜잭션 밖에서 {@link ReservationCancellationService}가 수행하도록, 이 서비스는 권한·기한·상태를
 * 검증하고 CANCELLING/CANCEL_REQUESTED로 전이해 커밋하는 것까지만 책임진다. 실제 CANCELLED 확정은
 * 환불 완료 후 결제 도메인의 공통 완료 경로({@code RefundCompletionService})가 자신이 소유한
 * {@code ReservationCancellationCompletionPort}를 통해 호출하는
 * {@code ReservationCancellationCompletionService#complete}(V2, #45/PR #144)가 담당한다.
 */
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

    // 락 순서: Reservation 단독(ADR 0001 "복수 비관적 락의 획득 순서" 참고). 환불 outbound port 호출은
    // 이 트랜잭션 밖(ReservationCancellationService)에서 수행하므로 결제 도메인 쪽 락 획득과 이 짧은
    // 접수 트랜잭션의 Reservation 락 보유 구간이 겹치지 않는다(Issue #44).
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

    /**
     * 예약에 속한 유효 참여자 전원을 CANCEL_REQUESTED로 전환하고 예약을 CANCELLING으로 전이한다.
     * 최초 예약자 취소, 그리고 추가 참여자 취소로 모집 CLOSED 상태의 확정 기준 미달이 되는 경우 모두
     * 이 경로를 함께 사용해, 취소 대상 전원을 단 하나의 {@code RefundRequestCommand}로 묶는다
     * (참여자별로 나눠 여러 번 환불을 요청하면서 생기는 부분 성공 위험 방지).
     */
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

    /**
     * OWNER가 본인 식당 사유로 예약 전체 취소를 접수한다(Issue #46, #44 공통 접수·실행·확정 절차 재사용).
     * MEMBER 취소와 달리 취소 기한(2시간)을 두지 않고, 상태 충돌은 §6-14 계약에 맞춰
     * {@link ReservationErrorCode#INVALID_STATE}로 통일한다. 이후 트랜잭션 밖 환불 실행과
     * 완료 확정은 MEMBER 취소와 동일한 {@link ReservationCancellationRefundPort}·완료 경로를 그대로 탄다.
     */
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

    /**
     * 예약에 속한 유효(RESERVED) 참여자 전원을 CANCEL_REQUESTED로 전환하고 예약을 CANCELLING으로
     * 전이한 뒤, 하나의 {@code RefundRequestCommand}로 묶을 참여자 ID 목록을 반환한다. 최초 예약자
     * 취소(MEMBER)와 OWNER 강제 취소가 이 로직을 공유한다(Issue #44, #46).
     */
    private List<Long> transitionAllValidParticipantsToCancelRequested(Reservation reservation, String reason) {
        List<ReservationParticipant> validParticipants = reservationParticipantRepository
                .findAllByReservationIdAndParticipationStatus(reservation.getId(), ParticipationStatus.RESERVED);
        validParticipants.forEach(participant -> participant.requestCancel(reason));
        reservation.startCancelling();
        return validParticipants.stream().map(ReservationParticipant::getId).toList();
    }

    /**
     * OWNER 소유권을 검증한다(Issue #48 {@code NoShowService.resolveOwnership}과 동일한 패턴, ADR 0005
     * 원칙 7). Reservation은 이미 락으로 조회돼 있으므로 TimeSlot·SharedTable·Restaurant만 순서대로
     * 조회하며, 그 사이 어떤 대상이 없어도 소유권 불일치와 구분하지 않고 전부 RESERVATION_ID_NOT_FOUND로
     * 취급한다 — 실제 불일치는 마지막 소유권 비교에서만 ACCESS_DENIED로 구분한다.
     */
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

    /**
     * {@code CLOSED}(식사 종료로 생명주기가 끝난 예약)도 여기서 차단한다(Issue #175 PR #178
     * 리뷰 반영). OWNER 취소는 MEMBER 취소와 달리 취소 기한을 두지 않아, 이 검사가 없으면
     * 식사가 끝난 예약도 다시 {@code CANCELLING}으로 전이되어 환불이 시작될 수 있다.
     */
    private void validateReservationCancellableByOwner(Reservation reservation) {
        if (reservation.isCancelled() || reservation.isCancelling() || reservation.isClosed()) {
            throw new CustomException(ReservationErrorCode.INVALID_STATE);
        }
    }

    /**
     * 모집 마감 기한(식사 시작 2시간 전) 도달을 스케줄러 후보 하나에 대해 접수한다(Issue #47,
     * #44 공통 접수·실행·확정 절차 재사용). 후보 조회 이후 다른 경로가 먼저 모집을 마감시켰을 수
     * 있어 {@code recruitmentStatus}가 이미 {@code CLOSED}이거나 예약이 이미 취소 진행 중이면
     * 아무 것도 바꾸지 않고 {@link RecruitmentDeadlineOutcome#ALREADY_PROCESSED}로 멱등 종료한다.
     * 확정 기준 이상이면 모집만 마감하고, 미달이면 유효 참여자 전원을 MEMBER·OWNER 취소와 동일한
     * 방식으로 취소 접수한다.
     *
     * <p>결과 이메일은 상태 변경과 같은 트랜잭션에 Outbox와 수신자별 전송 이력으로 기록한다.
     * {@code ALREADY_PROCESSED}는 같은 예약의 이메일 이벤트도 최초 1회만 생성되게 하는 멱등 가드다.</p>
     */
    public RecruitmentDeadlineAcceptance acceptRecruitmentDeadline(Long reservationId) {
        Reservation reservation = findReservationWithLockOrThrow(reservationId);
        if (reservation.getRecruitmentStatus() != RecruitmentStatus.OPEN || !reservation.isActive()) {
            return new RecruitmentDeadlineAcceptance(reservationId, RecruitmentDeadlineOutcome.ALREADY_PROCESSED, null);
        }
        reservation.closeRecruitment();

        int tableCapacity = reservationCapacityPort.readTableCapacity(reservation.getTimeSlotId());
        int currentCount = reservationParticipantRepository.sumPartySizeByStatuses(reservation.getId(), OCCUPYING_STATUSES);
        if (currentCount >= ReservationCapacityPolicy.confirmationThreshold(tableCapacity)) {
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

    /**
     * 추가 참여자 취소를 접수한다. 취소로 확정 기준 미달이 될지를 먼저 계산해, 모집 CLOSED에서
     * 기준 미달이 되면 {@link #acceptEntireReservationCancellation}로 예약 전체 취소를 대신 접수한다.
     * 그 외에는 본인만 CANCEL_REQUESTED로 전환할 뿐, Reservation의 RECRUITING/CONFIRMED 상태는
     * 여기서 재계산하지 않는다 — CANCEL_REQUESTED 참여자는 환불이 실제로 완료되기 전까지 좌석을
     * 점유한 상태로 집계해야 하므로(Issue #44 최종 계약), 접수 시점에 미리 정원 완화를 반영하면
     * "참여자는 아직 환불 대기 중인데 예약만 먼저 여유가 생김" 불일치가 생긴다. 실제 재계산은 환불
     * 완료 후 {@link #recalculateAfterCompletion}에서 수행한다.
     */
    private CancellationAcceptance acceptParticipantCancellation(
            Reservation reservation, ReservationParticipant actingParticipant, String reason
    ) {
        if (reservation.getRecruitmentStatus() == RecruitmentStatus.CLOSED
                && willFallBelowThresholdAfterCancel(reservation, actingParticipant)) {
            return acceptEntireReservationCancellation(reservation, actingParticipant, reason);
        }

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

    /**
     * 취소 접수(CANCEL_REQUESTED)된 참여자의 환불이 완료된 뒤 남은 유효 인원을 다시 계산해
     * RECRUITING/CONFIRMED를 재계산한다({@code ReservationCancellationCompletionService#complete}이
     * 호출, V2, #45/PR #144). 예약 전체가 CANCELLING인 경로에서는 호출하지 않는다.
     */
    void recalculateAfterCompletion(Reservation reservation) {
        int tableCapacity = reservationCapacityPort.readTableCapacity(reservation.getTimeSlotId());
        // 잠금 없는 SUM 집계 대신 잠금 조회로 합산한다(Issue #264) — 이 read가 속한 트랜잭션은
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

    /**
     * 본인 참여를 조회한다(Issue #131 오류 계약). Reservation은 생성 시 최초 예약자 참여와 함께
     * 만들어지므로, 이미 조회·잠금에 성공한 Reservation에 참여자가 하나도 없는 경우는 데이터 정합성이
     * 깨진 것이다 — 그 경우에만 {@code PARTICIPATION_NOT_FOUND}를 던지고, 참여자는 있지만 요청자
     * 본인의 참여가 아닌 정상적인 경우는 도메인 공통 {@link CommonErrorCode#ACCESS_DENIED}로 구분한다.
     */
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
