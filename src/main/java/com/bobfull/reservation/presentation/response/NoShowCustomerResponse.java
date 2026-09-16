package com.bobfull.reservation.presentation.response;

import com.bobfull.common.privacy.MemberNameMasker;
import com.bobfull.reservation.application.result.NoShowCustomerResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;

// 식당별 노쇼 고객 집계 정보를 제공한다.
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
