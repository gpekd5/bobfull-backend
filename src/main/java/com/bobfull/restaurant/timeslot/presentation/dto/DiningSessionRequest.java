package com.bobfull.restaurant.timeslot.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record DiningSessionRequest(
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {
}
