package com.bobfull.reservation.presentation.response;

/** §9-2 노쇼 처리·§9-3 노쇼 처리 해제 응답이다(Issue #48). */
public record NoShowProcessResponse(Long reservationId, Long participationId) {
}
