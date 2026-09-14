package com.bobfull.admin.presentation.dto;

import com.bobfull.admin.application.model.AdminMemberNoShowRateResult;
import com.bobfull.common.support.MemberNameMasker;

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
