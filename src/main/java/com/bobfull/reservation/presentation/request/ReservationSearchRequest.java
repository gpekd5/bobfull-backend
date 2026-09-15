package com.bobfull.reservation.presentation.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record ReservationSearchRequest(
        String keyword,
        LocalDate date,
        LocalTime time,
        Integer capacity,
        Integer minimumRemainingSeats
) {
}
