package com.bobfull.restaurant.sharedtable.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SharedTableErrorCode implements BaseErrorCode {

    INVALID_TABLE_CAPACITY(HttpStatus.BAD_REQUEST, "capacity는 2, 4, 6, 8 중 하나여야 합니다."),
    TABLE_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "tableId에 해당하는 대상을 찾을 수 없습니다."),
    TABLE_HAS_DINING_SESSION(HttpStatus.CONFLICT, "연결된 회차가 있어 삭제할 수 없습니다."),
    TABLE_HAS_RESERVATION(HttpStatus.CONFLICT, "연결된 활성 예약이 있어 정원을 변경할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }

}
