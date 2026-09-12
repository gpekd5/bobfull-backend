package com.bobfull.reservation.domain;

/**
 * 취소가 참여자 한 명에게만 적용됐는지, 예약 전체에 적용됐는지를 나타낸다(§6-10).
 */
public enum CancellationScope {
    PARTICIPATION,
    RESERVATION
}
