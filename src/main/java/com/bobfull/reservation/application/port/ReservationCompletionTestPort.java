package com.bobfull.reservation.application.port;

/**
 * 예약 완료 처리(Reservation 락을 쥔 채로 실행되는 구간) 앞에서 지연을 넣거나 실패를 강제하는
 * 확장점이다. Issue #146 시나리오 D(예약 완료 로직이 무거워진 상황)·E(예약 완료 반영 실패)를
 * 측정하기 위한 테스트 전용 장치이며, 운영 프로파일에는 구현체가 없어(Bean 미등록) 아무 일도
 * 하지 않는다.
 */
public interface ReservationCompletionTestPort {
    void beforeCompletion(Long reservationId);
}
