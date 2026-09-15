package com.bobfull.restaurant.sharedtable.presentation.response;

import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import java.util.List;

public record SharedTableBulkResponse(
        int createdTableCount,
        List<SharedTableResponse> tables
) {
    public static SharedTableBulkResponse from(List<SharedTable> tables) {
        return new SharedTableBulkResponse(
                tables.size(),
                tables.stream().map(SharedTableResponse::from).toList()
        );
    }
}
