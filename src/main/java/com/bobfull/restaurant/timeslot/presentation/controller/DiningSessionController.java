package com.bobfull.restaurant.timeslot.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.restaurant.timeslot.presentation.response.AvailableDiningSessionListResponse;
import com.bobfull.restaurant.timeslot.presentation.request.DiningSessionBulkRequest;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionBulkResponse;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionIdResponse;
import com.bobfull.restaurant.timeslot.presentation.request.DiningSessionRequest;
import com.bobfull.restaurant.timeslot.presentation.response.DiningSessionResponse;
import com.bobfull.restaurant.timeslot.application.service.TimeSlotService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DiningSessionController {

    private final TimeSlotService timeSlotService;

    @PostMapping("/owner/tables/{tableId}/dining-sessions")
    public ResponseEntity<ApiResponse<DiningSessionIdResponse>> register(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long tableId,
            @Valid @RequestBody DiningSessionRequest request
    ) {
        DiningSessionIdResponse response = timeSlotService.register(authMember.id(), tableId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/owner/tables/{tableId}/dining-sessions/bulk")
    public ResponseEntity<ApiResponse<DiningSessionBulkResponse>> registerBulk(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long tableId,
            @Valid @RequestBody DiningSessionBulkRequest request
    ) {
        DiningSessionBulkResponse response = timeSlotService.registerBulk(authMember.id(), tableId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/owner/restaurants/{restaurantId}/dining-sessions")
    public ApiResponse<PageResponse<DiningSessionResponse>> getOwnerDiningSessions(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long restaurantId,
            @RequestParam(required = false) LocalDate date,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(timeSlotService.getOwnerDiningSessions(
                authMember.id(), restaurantId, date, pageable));
    }

    @GetMapping("/restaurants/{restaurantId}/dining-sessions")
    public ApiResponse<AvailableDiningSessionListResponse> getAvailableDiningSessions(
            @PathVariable Long restaurantId,
            @RequestParam LocalDate date,
            @RequestParam(required = false) Integer partySize
    ) {
        return ApiResponse.success(timeSlotService.getAvailableDiningSessions(restaurantId, date, partySize));
    }

    @PatchMapping("/owner/dining-sessions/{sessionId}")
    public ApiResponse<DiningSessionIdResponse> update(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long sessionId,
            @Valid @RequestBody DiningSessionRequest request
    ) {
        return ApiResponse.success(timeSlotService.update(authMember.id(), sessionId, request));
    }

    @DeleteMapping("/owner/dining-sessions/{sessionId}")
    public ApiResponse<DiningSessionIdResponse> delete(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long sessionId
    ) {
        return ApiResponse.success(timeSlotService.delete(authMember.id(), sessionId));
    }
}
