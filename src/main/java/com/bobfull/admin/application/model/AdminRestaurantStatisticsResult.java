package com.bobfull.admin.application.model;

/** 식당별 예약 성사율 집계 결과 1건이다(Issue #49 §11-10). */
public record AdminRestaurantStatisticsResult(
        Long restaurantId,
        String restaurantName,
        long totalReservationCount,
        long confirmedReservationCount
) {
}
