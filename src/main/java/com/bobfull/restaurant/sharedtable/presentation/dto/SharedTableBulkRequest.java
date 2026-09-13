package com.bobfull.restaurant.sharedtable.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SharedTableBulkRequest(
        @NotNull Integer capacity,
        @NotNull @Min(1) @Max(10) Integer count
) {
}
