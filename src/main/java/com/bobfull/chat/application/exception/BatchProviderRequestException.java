package com.bobfull.chat.application.exception;

// Batch underfill Native 요청의 Provider 실패를 Validation/저장 실패와 구분한다.
public class BatchProviderRequestException extends RuntimeException {
    public BatchProviderRequestException(RuntimeException cause) {
        super("MODERATION_BATCH_NATIVE_REQUEST_FAILED", cause);
    }
}
