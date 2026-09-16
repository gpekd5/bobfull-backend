package com.bobfull.admin.application.result;

/** 회원별 노쇼율 집계 결과 1건이다(Issue #49 §11-11). */
public record AdminMemberNoShowRateResult(
        Long memberId,
        String name,
        long totalReservationCount,
        long noShowCount
) {
}
