package com.bobfull.restaurant.sharedtable.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record SharedTableRequest(
        @NotNull Integer capacity
) {
}
