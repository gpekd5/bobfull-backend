package com.bobfull.common.exception;

// 도메인 오류 코드를 공통 예외 처리 계층까지 전달하는 비즈니스 예외다.
public class CustomException extends RuntimeException {

    private final BaseErrorCode errorCode;

    public CustomException(BaseErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BaseErrorCode getErrorCode() {
        return errorCode;
    }
}
