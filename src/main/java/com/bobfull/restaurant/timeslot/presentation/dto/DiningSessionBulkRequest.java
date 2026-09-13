package com.bobfull.restaurant.timeslot.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record DiningSessionBulkRequest(
        @NotEmpty List<@NotNull LocalDate> dates,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull @Positive Integer intervalMinutes
) {
}
