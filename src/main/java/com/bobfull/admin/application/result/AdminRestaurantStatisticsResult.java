package com.bobfull.admin.application.result;

// 식당별 예약 성사율 집계 결과다.
public record AdminRestaurantStatisticsResult(
        Long restaurantId,
        String restaurantName,
        long totalReservationCount,
        long confirmedReservationCount
) {
}
