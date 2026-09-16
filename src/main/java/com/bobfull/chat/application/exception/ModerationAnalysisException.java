package com.bobfull.chat.application.exception;

// Consumer가 Retry·DLT 대상으로 구분할 수 있도록 AI 분석 실패 코드를 전달한다.
public class ModerationAnalysisException extends RuntimeException {

    public ModerationAnalysisException(String errorCode) {
        super(errorCode);
    }

    public ModerationAnalysisException(String errorCode, Throwable cause) {
        super(errorCode, cause);
    }

    public String getErrorCode() {
        return getMessage();
    }
}
