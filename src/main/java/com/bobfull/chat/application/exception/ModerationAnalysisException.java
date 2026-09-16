package com.bobfull.chat.application.exception;

/** #59 Consumer가 재시도·DLT 대상으로 구분할 수 있게 AI 분석 실패를 전파한다. */
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
