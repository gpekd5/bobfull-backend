package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.application.port.ReservationNotificationPort;
import com.bobfull.reservation.application.port.ReservationNotificationPort.Recipient;
import com.bobfull.reservation.application.port.ReservationNotificationPort.ReservationResultNotification;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.notification.infrastructure.outbox.EmailOutboxDelivery;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * #47 모집 마감 처리 결과(확정·인원 미달 취소)와 결제 완료(접수·참여) 결과를 이메일로 안내한다.
 * 호출자({@code EmailOutboxProcessor})는 공통 Outbox가 claim한 PENDING 수신자 1건을 커밋된
 * 트랜잭션 밖에서 처리하며, 발송 실패는 예외로 전파해 Outbox의 재시도·FAILED 전이만으로
 * 처리한다(Issue #183).
 */
@Service
@RequiredArgsConstructor
public class ReservationNotificationService {

    private final ReservationRepository reservationRepository;
    private final ReservationParticipantRepository reservationParticipantRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final MemberRepository memberRepository;
    private final ReservationNotificationPort notificationPort;

    /** Outbox processor가 단일 수신자를 발송하고 성공 시에만 전송 이력을 확정할 수 있게 한다. */
    public void sendOutboxEmail(String eventTypeName, EmailOutboxDelivery delivery) {
        OutboxEventType type = OutboxEventType.valueOf(eventTypeName);
        List<ReservationParticipant> participants = reservationParticipantRepository.findAllById(List.of(delivery.getReservationParticipantId()));
        if (participants.size() != 1 || !participants.get(0).getMemberId().equals(delivery.getRecipientMemberId())) {
            throw new IllegalStateException("EMAIL_RECIPIENT_NOT_FOUND");
        }
        ReservationResultNotification notification = buildNotification(delivery.getReservationId(), participants);
        switch (type) {
            case EMAIL_RESERVATION_CREATED -> notificationPort.notifyReservationCreated(notification);
            case EMAIL_PARTICIPATION_COMPLETED -> notificationPort.notifyParticipationCompleted(notification);
            case EMAIL_RECRUITMENT_CONFIRMED -> notificationPort.notifyConfirmed(notification);
            case EMAIL_RECRUITMENT_CANCELLED -> notificationPort.notifyCancelledDueToInsufficientParticipants(notification);
            default -> throw new IllegalArgumentException("이메일 이벤트 유형이 아닙니다.");
        }
    }

    private ReservationResultNotification buildNotification(Long reservationId, List<ReservationParticipant> participants) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        TimeSlot timeSlot = timeSlotRepository.findByIdAndDeletedAtIsNull(reservation.getTimeSlotId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
        SharedTable sharedTable = sharedTableRepository.findByIdAndDeletedAtIsNull(timeSlot.getSharedTableId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(sharedTable.getRestaurantId())
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));

        List<Long> memberIds = participants.stream().map(ReservationParticipant::getMemberId).distinct().toList();
        Map<Long, Member> membersById = memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getId, member -> member));
        List<Recipient> recipients = memberIds.stream()
                .map(membersById::get)
                .filter(Objects::nonNull)
                .map(member -> new Recipient(member.getId(), member.getEmail(), member.getName()))
                .toList();

        return new ReservationResultNotification(
                reservationId, restaurant.getName(), restaurant.getAddress(), timeSlot.getStartAt(), recipients);
    }
}
