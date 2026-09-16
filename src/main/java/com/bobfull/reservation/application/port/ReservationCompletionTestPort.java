package com.bobfull.reservation.application.port;

// Reservation 잠금 구간의 지연과 실패를 재현하는 성능 측정 전용 확장점이다.
// 운영 프로파일에는 구현 Bean이 없어 실제 예약 완료 흐름에 개입하지 않는다.
public interface ReservationCompletionTestPort {
    void beforeCompletion(Long reservationId);
}
