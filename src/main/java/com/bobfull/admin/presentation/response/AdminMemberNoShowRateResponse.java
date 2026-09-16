package com.bobfull.admin.presentation.response;

import com.bobfull.admin.application.result.AdminMemberNoShowRateResult;
import com.bobfull.common.privacy.MemberNameMasker;

public record AdminMemberNoShowRateResponse(
        Long memberId,
        String name,
        long totalReservationCount,
        long noShowCount,
        double noShowRate
) {
    public static AdminMemberNoShowRateResponse of(AdminMemberNoShowRateResult result, double noShowRate) {
        return new AdminMemberNoShowRateResponse(
                result.memberId(), MemberNameMasker.mask(result.name()),
                result.totalReservationCount(), result.noShowCount(), noShowRate);
    }
}
