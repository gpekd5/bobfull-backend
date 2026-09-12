package com.bobfull.reservation.presentation.dto;

public record ReservationAvailabilityResponse(
        boolean available,
        Integer availableCapacity,
        String reason
) {
    public static ReservationAvailabilityResponse available(int availableCapacity) {
        return new ReservationAvailabilityResponse(true, availableCapacity, null);
    }
}
