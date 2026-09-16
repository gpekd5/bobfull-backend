package com.bobfull.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 특정 도메인에 속하지 않는 서비스 전역 공통 에러 코드다.
 * 도메인 전용 에러는 이 Enum에 추가하지 않고 별도 Enum(예: MemberErrorCode)으로 분리한다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
