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

// 결제 완료 결과를 예약과 참여자 상태에 원자적으로 반영한다.
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

    // 결제 완료 트랜잭션 안에서 예약·참여자와 후속 처리 Outbox를 함께 확정한다.
    // MANDATORY는 호출자 트랜잭션 없이 일부 상태만 반영되는 것을 막는다.
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
            // 채팅방 생성은 결제 완료의 필수 조건이 아니므로 같은 트랜잭션에는 생성 의도만 기록한다.
            // 커밋 뒤 처리가 실패해도 scheduler가 PENDING 이벤트를 다시 처리한다.
            OutboxEvent outboxEvent = outboxEventRepository.save(
                    OutboxEvent.chatRoomCreationRequested(reservation.getId(), clock.instant()));
            AfterCommitExecutor.run(() -> log.info(
                    "event=OUTBOX_EVENT_CREATED outboxEventId={} eventType={} aggregateType=RESERVATION aggregateId={} attemptCount=0 status=PENDING",
                    outboxEvent.getId(), outboxEvent.getEventType(), reservation.getId()));
            AfterCommitExecutor.run(() -> chatRoomOutboxProcessor.signal(outboxEvent.getId()));
        }
        // 이메일도 예약 상태와 함께 Outbox에 기록해 커밋된 결과만 발송되게 한다.
        emailOutboxEventService.enqueue(
                purpose == PaymentPurpose.CREATE ? OutboxEventType.EMAIL_RESERVATION_CREATED : OutboxEventType.EMAIL_PARTICIPATION_COMPLETED,
                reservation.getId(), List.of(participant));

        ReservationStatus beforeStatus = reservation.getReservationStatus();
        updateReservationStatus(reservation, timeSlotId);
        if (beforeStatus != ReservationStatus.CONFIRMED
                && reservation.getReservationStatus() == ReservationStatus.CONFIRMED) {
            log.info("event=RESERVATION_CONFIRMED reservationId={} participantId={} memberId={} beforeStatus={} afterStatus={}",
                    reservation.getId(), participant.getId(), memberId, beforeStatus, reservation.getReservationStatus());
            // 롤백된 예약 확정이 운영 지표에 포함되지 않도록 커밋 뒤에 기록한다.
            AfterCommitExecutor.run(() -> businessMetricRecorder.increment(BusinessMetricEvent.RESERVATION_CONFIRMED));
        }
        return new ReservationConfirmationResult(reservation.getId(), participant.getId());
    }

    // READY 결제 준비 후 취소가 접수될 수 있어 참여자 생성 직전에 활성 상태를 다시 확인한다.
    private void validateJoinable(Reservation reservation) {
        if (!reservation.isActive()) {
            throw new CustomException(ReservationErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
    }

    private void updateReservationStatus(Reservation reservation, Long timeSlotId) {
        int tableCapacity = reservationCapacityPort.readTableCapacity(timeSlotId);
        int currentParticipantCount = reservationParticipantRepository.sumPartySizeByStatuses(
                reservation.getId(), OCCUPYING_STATUSES);
        // 성사 기준은 정원 2명이면 2명, 그 외에는 정원보다 한 명 적은 인원이다.
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
