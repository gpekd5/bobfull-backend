package com.bobfull.reservation.domain.entity;

// 예약 전체의 모집·성사·취소 생명주기를 나타낸다.
public enum ReservationStatus {

    RECRUITING,
    CONFIRMED,
    // 환불 완료를 기다리는 동안에도 좌석을 계속 점유한다.
    CANCELLING,
    CANCELLED,
    CLOSED
}
