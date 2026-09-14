package com.bobfull.admin.presentation.dto;

public record AdminOverviewStatisticsResponse(
        long totalReservationCount,
        double reservationConfirmationRate,
        double noShowRate
) {
}
