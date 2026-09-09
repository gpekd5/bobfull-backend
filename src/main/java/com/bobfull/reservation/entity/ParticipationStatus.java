package com.bobfull.reservation.entity;

/**
 * 예약 참여자 개인의 상태다(docs/30-data/erd.md 4.6).
 */
public enum ParticipationStatus {

    RESERVED,
    NO_SHOW,
    /** 취소가 접수되어 환불 완료를 기다리는 중이다(Issue #44). 좌석은 계속 점유 상태로 집계한다. */
    CANCEL_REQUESTED,
    CANCELLED
}
