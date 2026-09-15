package com.bobfull.restaurant.sharedtable.presentation.request;

import jakarta.validation.constraints.NotNull;

public record SharedTableRequest(
        @NotNull Integer capacity
) {
}
