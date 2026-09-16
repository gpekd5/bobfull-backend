package com.bobfull.reservation.presentation.response;

// 노쇼 처리 또는 해제된 참여자를 식별한다.
public record NoShowProcessResponse(Long reservationId, Long participationId) {
}
