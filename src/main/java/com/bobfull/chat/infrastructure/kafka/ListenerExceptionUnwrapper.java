package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.common.exception.CustomException;

/** Kafka 리스너 실패를 감싸는 예외에서 #66 recordFinalFailure에 넘길 errorCode를 뽑아낸다. */
public final class ListenerExceptionUnwrapper {

    private ListenerExceptionUnwrapper() {
    }

    public static String errorCodeOf(Throwable listenerFailure) {
        Throwable cause = listenerFailure;
        while (cause != null) {
            if (cause instanceof ModerationAnalysisException moderationAnalysisException) {
                return moderationAnalysisException.getErrorCode();
            }
            if (cause instanceof CustomException customException) {
                return customException.getErrorCode().getCode();
            }
            cause = cause.getCause();
        }
        return rootCause(listenerFailure).getClass().getSimpleName();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
