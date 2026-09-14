package com.bobfull.admin.presentation.dto;

import com.bobfull.admin.application.model.AdminRestaurantStatisticsResult;
public record AdminRestaurantStatisticsResponse(
        Long restaurantId,
        String restaurantName,
        long totalReservationCount,
        long confirmedReservationCount,
        double confirmationRate
) {
    public static AdminRestaurantStatisticsResponse of(AdminRestaurantStatisticsResult result, double confirmationRate) {
        return new AdminRestaurantStatisticsResponse(
                result.restaurantId(), result.restaurantName(),
                result.totalReservationCount(), result.confirmedReservationCount(), confirmationRate);
    }
}
