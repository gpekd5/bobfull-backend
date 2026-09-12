package com.bobfull.reservation.presentation.dto;

import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationParticipant;

/**
 * §6-13 사장님용 예약 참여자 목록 조회 응답이다(Issue #147).
 * 실제 운영(체크인 확인 등)에 예약자 식별이 필요해 §9-1 노쇼 후보 조회와 달리 이름을 마스킹하지 않는다
 * (API 명세 §6-13 예시 기준).
 */
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
