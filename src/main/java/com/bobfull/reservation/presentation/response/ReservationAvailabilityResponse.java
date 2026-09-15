package com.bobfull.reservation.presentation.response;

public record ReservationAvailabilityResponse(
        boolean available,
        Integer availableCapacity,
        String reason
) {
    public static ReservationAvailabilityResponse available(int availableCapacity) {
        return new ReservationAvailabilityResponse(true, availableCapacity, null);
    }
}
