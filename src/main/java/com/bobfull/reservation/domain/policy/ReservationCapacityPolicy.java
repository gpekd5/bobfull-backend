package com.bobfull.reservation.domain.policy;

// 단건 조회와 QueryDSL 조회가 공유하는 성사 인원·잔여 좌석 공식을 제공한다.
public final class ReservationCapacityPolicy {

    private ReservationCapacityPolicy() {
    }

    // 최소 성사 인원은 정원 2명이면 2명, 그 외에는 정원보다 한 명 적은 인원이다.
    public static int confirmationThreshold(int capacity) {
        return capacity == 2 ? 2 : capacity - 1;
    }

    // 정원에서 결제 완료 인원과 만료되지 않은 READY 선점을 차감한다.
    public static int availableCapacity(int capacity, long currentParticipantCount, long temporaryHeldCount) {
        return (int) Math.max(0, capacity - currentParticipantCount - temporaryHeldCount);
    }
}
