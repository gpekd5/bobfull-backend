package com.bobfull.reservation.presentation.response;

import com.bobfull.common.privacy.MemberNameMasker;
import com.bobfull.reservation.domain.entity.ParticipationStatus;
import com.bobfull.reservation.domain.entity.ReservationParticipant;

// 노쇼 처리 대상 참여자 정보를 제공한다.
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
