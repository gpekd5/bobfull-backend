package com.bobfull.reservation.presentation.dto;

import com.bobfull.common.privacy.MemberNameMasker;
import com.bobfull.reservation.application.dto.NoShowCustomerResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** §9-5 식당 노쇼 고객 조회 응답이다(Issue #48). */
public record NoShowCustomerResponse(
        Long memberId,
        String name,
        long noShowCount,
        OffsetDateTime latestNoShowAt,
        Long reservationId,
        Long participationId,
        Integer partySize
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static NoShowCustomerResponse of(NoShowCustomerResult result) {
        return new NoShowCustomerResponse(
                result.memberId(),
                MemberNameMasker.mask(result.memberName()),
                result.noShowCount(),
                OffsetDateTime.ofInstant(result.latestNoShowAt(), SEOUL),
                result.reservationId(),
                result.participationId(),
                result.partySize());
    }
}
