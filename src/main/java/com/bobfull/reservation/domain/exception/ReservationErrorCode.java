package com.bobfull.reservation.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 예약 도메인 전용 에러 코드다.
 */
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements BaseErrorCode {

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상 회차 또는 예약을 찾을 수 없습니다."),
    RESERVATION_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "reservationId에 해당하는 대상을 찾을 수 없습니다."),
    PARTICIPATION_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "participationId에 해당하는 대상을 찾을 수 없습니다."),
    ACTIVE_RESERVATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 활성 예약 또는 결제 준비가 존재합니다."),
    INSUFFICIENT_REMAINING_CAPACITY(HttpStatus.CONFLICT, "남은 참여 가능 인원을 초과했습니다."),
    INVALID_PARTY_SIZE(HttpStatus.BAD_REQUEST, "partySize가 올바르지 않습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 요청을 처리할 수 없습니다."),
    PARTICIPATION_NOT_FOUND(HttpStatus.NOT_FOUND, "본인 참여를 찾을 수 없습니다."),
    CANCELLATION_NOT_ALLOWED(HttpStatus.FORBIDDEN, "취소할 수 없는 참여 상태입니다."),
    CANCELLATION_DEADLINE_PASSED(HttpStatus.CONFLICT, "서버 시간 기준 식사 시작 2시간 이내에는 취소할 수 없습니다."),
    PARTICIPATION_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 참여입니다."),
    RESERVATION_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 예약입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
