package com.bobfull.admin.presentation.response;

public record AdminOverviewStatisticsResponse(
        long totalReservationCount,
        double reservationConfirmationRate,
        double noShowRate
) {
}
