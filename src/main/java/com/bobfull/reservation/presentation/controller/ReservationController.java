package com.bobfull.reservation.presentation.controller;

import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.payment.entity.PaymentPurpose;
import com.bobfull.reservation.presentation.dto.ReservationAvailabilityResponse;
import com.bobfull.reservation.presentation.dto.ReservationCancellationRequest;
import com.bobfull.reservation.presentation.dto.ReservationCancellationResponse;
import com.bobfull.reservation.presentation.dto.ReservationPrepareRequest;
import com.bobfull.reservation.presentation.dto.ReservationPrepareResponse;
import com.bobfull.reservation.presentation.dto.ReservationSearchRequest;
import com.bobfull.reservation.presentation.dto.ReservationSearchResponse;
import com.bobfull.reservation.application.service.ReservationCancellationService;
import com.bobfull.reservation.application.service.ReservationPreparationService;
import com.bobfull.reservation.application.service.ReservationSearchService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationPreparationService reservationPreparationService;
    private final ReservationSearchService reservationSearchService;
    private final ReservationCancellationService reservationCancellationService;

    public ReservationController(
            ReservationPreparationService reservationPreparationService,
            ReservationSearchService reservationSearchService,
            ReservationCancellationService reservationCancellationService
    ) {
        this.reservationPreparationService = reservationPreparationService;
        this.reservationSearchService = reservationSearchService;
        this.reservationCancellationService = reservationCancellationService;
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<ReservationSearchResponse>> searchReservations(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalTime time,
            @RequestParam(required = false) Integer capacity,
            @RequestParam(required = false) Integer minimumRemainingSeats,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        ReservationSearchRequest request =
                new ReservationSearchRequest(keyword, date, time, capacity, minimumRemainingSeats);
        return ApiResponse.success(reservationSearchService.searchReservations(request, pageable));
    }

    @GetMapping("/availability")
    public ApiResponse<ReservationAvailabilityResponse> checkAvailability(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam PaymentPurpose type,
            @RequestParam Long targetId,
            @RequestParam Integer partySize
    ) {
        return ApiResponse.success(
                reservationPreparationService.checkAvailability(authMember.id(), type, targetId, partySize));
    }

    @PostMapping("/prepare")
    public ApiResponse<ReservationPrepareResponse> prepare(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ReservationPrepareRequest request
    ) {
        return ApiResponse.success(reservationPreparationService.prepare(authMember.id(), request));
    }

    @PostMapping("/{reservationId}/participations/me/cancel")
    public ApiResponse<ReservationCancellationResponse> cancelMyParticipation(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationCancellationRequest request
    ) {
        return ApiResponse.success(
                reservationCancellationService.cancel(authMember.id(), reservationId, request));
    }
}
