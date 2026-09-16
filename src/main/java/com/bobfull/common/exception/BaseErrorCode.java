package com.bobfull.common.exception;

import org.springframework.http.HttpStatus;

// 도메인 오류를 공통 HTTP 응답으로 변환하기 위한 최소 계약이다.
public interface BaseErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
