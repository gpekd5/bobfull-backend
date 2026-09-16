package com.bobfull.common.response;

import com.bobfull.common.exception.BaseErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

// 성공 data와 실패 code를 동일한 JSON 구조로 제공하는 공통 API 응답이다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final String code;

    private ApiResponse(boolean success, String message, T data, String code) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "요청이 성공했습니다.", data, null);
    }

    public static ApiResponse<Void> fail(BaseErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getMessage(), null, errorCode.getCode());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getCode() {
        return code;
    }
}
