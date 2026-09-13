package com.bobfull.sharedtable.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.sharedtable.dto.SharedTableIdResponse;
import com.bobfull.sharedtable.dto.SharedTableBulkRequest;
import com.bobfull.sharedtable.dto.SharedTableBulkResponse;
import com.bobfull.sharedtable.dto.SharedTableRequest;
import com.bobfull.sharedtable.dto.SharedTableResponse;
import com.bobfull.sharedtable.service.SharedTableService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner")
public class SharedTableController {

    private final SharedTableService sharedTableService;

    public SharedTableController(SharedTableService sharedTableService) {
        this.sharedTableService = sharedTableService;
    }

    @PostMapping("/restaurants/{restaurantId}/tables")
    public ResponseEntity<ApiResponse<SharedTableIdResponse>> register(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @Valid @RequestBody SharedTableRequest request
    ) {
        SharedTableIdResponse response = sharedTableService.register(authMember.id(), restaurantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/restaurants/{restaurantId}/tables/bulk")
    public ResponseEntity<ApiResponse<SharedTableBulkResponse>> registerBulk(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @Valid @RequestBody SharedTableBulkRequest request
    ) {
        SharedTableBulkResponse response = sharedTableService.registerBulk(authMember.id(), restaurantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/restaurants/{restaurantId}/tables")
    public ApiResponse<PageResponse<SharedTableResponse>> getTables(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(sharedTableService.getTables(authMember.id(), restaurantId, pageable));
    }

    @GetMapping("/tables/{tableId}")
    public ApiResponse<SharedTableResponse> getTable(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long tableId
    ) {
        return ApiResponse.success(sharedTableService.getTable(authMember.id(), tableId));
    }

    @PatchMapping("/tables/{tableId}")
    public ApiResponse<SharedTableIdResponse> update(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long tableId,
            @Valid @RequestBody SharedTableRequest request
    ) {
        return ApiResponse.success(sharedTableService.update(authMember.id(), tableId, request));
    }

    @DeleteMapping("/tables/{tableId}")
    public ApiResponse<SharedTableIdResponse> delete(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long tableId
    ) {
        return ApiResponse.success(sharedTableService.delete(authMember.id(), tableId));
    }
}
