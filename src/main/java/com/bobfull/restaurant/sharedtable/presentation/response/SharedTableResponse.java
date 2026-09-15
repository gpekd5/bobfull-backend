package com.bobfull.restaurant.sharedtable.presentation.response;

import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTableStatus;

public record SharedTableResponse(
        Long tableId,
        Long restaurantId,
        Integer displayNumber,
        Integer capacity,
        SharedTableStatus status
) {
    public static SharedTableResponse from(SharedTable sharedTable) {
        return new SharedTableResponse(
                sharedTable.getId(),
                sharedTable.getRestaurantId(),
                sharedTable.getDisplayNumber(),
                sharedTable.getCapacity(),
                sharedTable.getStatus()
        );
    }
}
