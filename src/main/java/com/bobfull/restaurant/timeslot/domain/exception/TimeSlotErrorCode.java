package com.bobfull.restaurant.timeslot.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회차 도메인 전용 에러 코드다.
 */
@Getter
@RequiredArgsConstructor
public enum TimeSlotErrorCode implements BaseErrorCode {

    SESSION_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "sessionId에 해당하는 대상을 찾을 수 없습니다."),
    DUPLICATE_DINING_SESSION(HttpStatus.CONFLICT, "동일 테이블의 동일 시작 시각 회차가 이미 존재합니다."),
    SESSION_HAS_RESERVATION(HttpStatus.CONFLICT, "연결된 예약이 있어 회차를 변경할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }

}
