package com.bobfull.chat.application.exception;

// 필터링 전 poll index를 보존해 미완료 source offset을 건너뛰지 않게 한다.
public class BatchModerationException extends RuntimeException {
    private final int index;

    public BatchModerationException(int index, RuntimeException cause) {
        super("MODERATION_BATCH_RECORD_FAILED", cause);
        this.index = index;
    }

    public int index() {
        return index;
    }
}
