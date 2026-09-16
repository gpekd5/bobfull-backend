package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.transaction.AfterCommitExecutor;
import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.chat.infrastructure.outbox.ChatRoomOutboxProcessor;
import com.bobfull.notification.infrastructure.outbox.EmailOutboxEventService;
import com.bobfull.payment.domain.entity.PaymentPurpose;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.domain.policy.ReservationCapacityPolicy;
import com.bobfull.reservation.application.port.ReservationCapacityPort;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import java.util.List;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 시 실제 Reservation·ReservationParticipant를 생성·갱신하는 예약 도메인의 확정 서비스다.
 * Payment 도메인의 결제 완료 트랜잭션(PaymentCompletionTransactionService) 안에서만 호출되도록
 * 설계되었으므로, 그 전제를 {@code Propagation.MANDATORY}로 명시해 호출자의 트랜잭션 없이 단독
 * 호출되면 즉시 실패하게 한다(부분 성공 방지).
 * 실제 {@code ReservationConfirmationPort} 구현과 웹훅 연결은 #93에서 이 서비스를 호출해 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationConfirmationService {

    private static final List<ParticipationStatus> OCCUPYING_STATUSES =
            List.of(ParticipationStatus.RESERVED, ParticipationStatus.CANCEL_REQUESTED);

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final ReservationCapacityPort reservationCapacityPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ChatRoomOutboxProcessor chatRoomOutboxProcessor;
    private final EmailOutboxEventService emailOutboxEventService;
    private final Clock clock;
    private final BusinessMetricRecorder businessMetricRecorder;

    /**
     * CREATE는 새 Reservation과 최초 ReservationParticipant를, JOIN은 기존 Reservation에
     * ReservationParticipant를 추가로 생성한다. 확정 기준(정원 2면 2명, 그 외에는 정원-1명)
     * 도달 시 CONFIRMED로, 정원에 도달하면 추가로 모집을 CLOSED로 전이한다(§0.8).
     * ChatRoom은 필수 결제·예약 확정 조건이 아니므로 여기서 직접 저장하지 않는다. CREATE에서는
     * ChatRoom 생성 의도만 Outbox PENDING으로 같은 트랜잭션에 기록하고, 커밋 뒤 별도 짧은
     * 트랜잭션의 processor가 실제 생성한다. 따라서 ChatRoom 저장 실패가 이미 완료된 결제·예약을
     * 롤백시키지 않으면서도, 커밋 뒤 signal 유실은 scheduler가 DB 이벤트로 복구한다(#176).
     * 접수·참여 완료 이메일도 같은 트랜잭션에 Outbox와 수신자별 전송 이력을 저장한다(Issue #183).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationConfirmationResult confirm(
            PaymentPurpose purpose, Long timeSlotId, Long reservationId, Long memberId, Integer partySize
    ) {
        Reservation reservation = (purpose == PaymentPurpose.CREATE)
                ? reservationRepository.save(Reservation.create(timeSlotId, memberId))
                : findReservationWithLockOrThrow(reservationId);

        if (purpose == PaymentPurpose.JOIN) {
            validateJoinable(reservation);
        }

        ReservationParticipant participant = reservationParticipantRepository.save(
                ReservationParticipant.create(reservation.getId(), memberId, partySize));

        if (purpose == PaymentPurpose.CREATE) {
            OutboxEvent outboxEvent = outboxEventRepository.save(
                    OutboxEvent.chatRoomCreationRequested(reservation.getId(), clock.instant()));
            AfterCommitExecutor.run(() -> log.info(
                    "event=OUTBOX_EVENT_CREATED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount=0 status=PENDING",
                    outboxEvent.getId(), outboxEvent.getEventType(), reservation.getId()));
            AfterCommitExecutor.run(() -> chatRoomOutboxProcessor.signal(outboxEvent.getId()));
        }
        emailOutboxEventService.enqueue(
                purpose == PaymentPurpose.CREATE ? OutboxEventType.EMAIL_RESERVATION_CREATED : OutboxEventType.EMAIL_PARTICIPATION_COMPLETED,
                reservation.getId(), List.of(participant));

        ReservationStatus beforeStatus = reservation.getReservationStatus();
        updateReservationStatus(reservation, timeSlotId);
        if (beforeStatus != ReservationStatus.CONFIRMED
                && reservation.getReservationStatus() == ReservationStatus.CONFIRMED) {
            log.info("event=RESERVATION_CONFIRMED reservationId={} participantId={} memberId={} beforeStatus={} afterStatus={}",
                    reservation.getId(), participant.getId(), memberId, beforeStatus, reservation.getReservationStatus());
            AfterCommitExecutor.run(() -> businessMetricRecorder.increment(BusinessMetricEvent.RESERVATION_CONFIRMED));
        }
        return new ReservationConfirmationResult(reservation.getId(), participant.getId());
    }

    /**
     * 취소 접수(CANCELLING)로 예약이 비활성화된 뒤 결제 완료 웹훅이 뒤늦게 도착해 새 참여자가
     * 추가되는 경쟁 조건을 막는다(Issue #44). READY Payment 준비 시점에도 같은 검증이 있지만
     * (ReservationPreparationService.validateJoinable), 그 사이 취소가 접수될 수 있으므로 실제
     * 참여자 생성 직전인 여기서 다시 확인해야 한다.
     */
    private void validateJoinable(Reservation reservation) {
        if (!reservation.isActive()) {
            throw new CustomException(ReservationErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
    }

    private void updateReservationStatus(Reservation reservation, Long timeSlotId) {
        int tableCapacity = reservationCapacityPort.readTableCapacity(timeSlotId);
        int currentParticipantCount = reservationParticipantRepository.sumPartySizeByStatuses(
                reservation.getId(), OCCUPYING_STATUSES);
        if (currentParticipantCount >= ReservationCapacityPolicy.confirmationThreshold(tableCapacity)) {
            reservation.confirm();
        }
        if (currentParticipantCount >= tableCapacity) {
            reservation.closeRecruitment();
        }
    }

    private Reservation findReservationWithLockOrThrow(Long reservationId) {
        return reservationRepository.findWithLockById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
    }

    public record ReservationConfirmationResult(Long reservationId, Long reservationParticipantId) {
    }
}
