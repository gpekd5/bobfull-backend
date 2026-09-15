package com.bobfull.restaurant.restaurant.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 식당 도메인 전용 에러 코드다.
 */
@Getter
@RequiredArgsConstructor
public enum RestaurantErrorCode implements BaseErrorCode {

    RESTAURANT_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "restaurantId에 해당하는 대상을 찾을 수 없습니다."),
    RESTAURANT_DELETE_NOT_ALLOWED(HttpStatus.CONFLICT, "연결된 테이블·회차·예약이 있어 삭제할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }

}
