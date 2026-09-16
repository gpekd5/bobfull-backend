package com.bobfull.reservation.domain.entity;

// 예약 참여자 개인의 처리 상태를 나타낸다.
public enum ParticipationStatus {

    RESERVED,
    NO_SHOW,
    // 환불 완료를 기다리는 동안에도 좌석을 계속 점유한다.
    CANCEL_REQUESTED,
    CANCELLED
}
