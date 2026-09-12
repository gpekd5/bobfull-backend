package com.bobfull.reservation.presentation.dto;

import com.bobfull.common.support.MemberNameMasker;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationParticipant;

/** §9-1 노쇼 처리 대상 참여자 조회 응답이다(Issue #48). */
public record NoShowCandidateResponse(
        Long participationId,
        Long memberId,
        String name,
        Integer partySize,
        ParticipationStatus participationStatus
) {
    public static NoShowCandidateResponse of(ReservationParticipant participant, String memberName) {
        return new NoShowCandidateResponse(
                participant.getId(), participant.getMemberId(), MemberNameMasker.mask(memberName),
                participant.getPartySize(), participant.getParticipationStatus());
    }
}
