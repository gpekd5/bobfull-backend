package com.bobfull.member.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 회원 도메인 전용 에러 코드다.
 * 다른 도메인(식당·예약 등)은 같은 방식으로 BaseErrorCode를 구현하는 별도 Enum을 추가한다.
 */
public enum MemberErrorCode implements BaseErrorCode {

    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    MEMBER_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "memberId에 해당하는 대상을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다."),
    DUPLICATE_PHONE_NUMBER(HttpStatus.CONFLICT, "이미 사용 중인 phoneNumber입니다."),
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "이미 사용 중인 businessNumber입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    MemberErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
