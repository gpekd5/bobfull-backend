package com.bobfull.reservation.presentation.response;

import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationParticipant;

// 식당 운영에서 참여자를 식별할 수 있도록 이름을 마스킹하지 않고 제공한다.
public record OwnerReservationParticipantResponse(
        Long participationId,
        Long memberId,
        String name,
        Integer partySize,
        ParticipationStatus participationStatus
) {
    public static OwnerReservationParticipantResponse of(ReservationParticipant participant, String memberName) {
        return new OwnerReservationParticipantResponse(
                participant.getId(),
                participant.getMemberId(),
                memberName,
                participant.getPartySize(),
                participant.getParticipationStatus()
        );
    }
}
