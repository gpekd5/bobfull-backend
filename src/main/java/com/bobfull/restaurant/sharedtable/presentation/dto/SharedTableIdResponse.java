package com.bobfull.restaurant.sharedtable.presentation.dto;

import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;

public record SharedTableIdResponse(Long tableId, Integer displayNumber) {

    public static SharedTableIdResponse from(SharedTable sharedTable) {
        return new SharedTableIdResponse(sharedTable.getId(), sharedTable.getDisplayNumber());
    }
}
