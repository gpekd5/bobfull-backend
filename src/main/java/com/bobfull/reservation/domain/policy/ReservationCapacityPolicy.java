package com.bobfull.reservation.domain.policy;

/**
 * 테이블 정원 기준 확정 인원·잔여 좌석 계산 공식을 한 곳에서 관리한다(§0.8).
 * 단건 조회(엔티티 로딩)와 목록 조회(QueryDSL 프로젝션) 양쪽에서 같은 공식을 쓰도록
 * 엔티티에 의존하지 않는 순수 함수로 둔다.
 */
public final class ReservationCapacityPolicy {

    private ReservationCapacityPolicy() {
    }

    /** 확정 기준 인원이다: 정원 2면 2명, 그 외에는 정원-1명. */
    public static int confirmationThreshold(int capacity) {
        return capacity == 2 ? 2 : capacity - 1;
    }

    /** 정원에서 결제 완료 참여 인원과 만료되지 않은 READY 임시 선점 인원을 차감한 잔여 좌석이다. */
    public static int availableCapacity(int capacity, long currentParticipantCount, long temporaryHeldCount) {
        return (int) Math.max(0, capacity - currentParticipantCount - temporaryHeldCount);
    }
}
