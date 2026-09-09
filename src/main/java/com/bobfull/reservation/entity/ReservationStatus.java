package com.bobfull.reservation.entity;

/**
 * 예약의 전체 상태다(docs/030-data/erd.md 4.5).
 */
public enum ReservationStatus {

    RECRUITING,
    CONFIRMED,
    /** 취소가 접수되어 환불 완료를 기다리는 중이다(Issue #44). 좌석은 계속 점유 상태로 집계한다. */
    CANCELLING,
    CANCELLED,
    CLOSED
}
