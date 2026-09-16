package com.bobfull.admin.application.result;

// 회원별 노쇼율 집계 결과다.
public record AdminMemberNoShowRateResult(
        Long memberId,
        String name,
        long totalReservationCount,
        long noShowCount
) {
}
