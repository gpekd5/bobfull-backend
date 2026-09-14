package com.bobfull.chat.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.application.service.ModerationAnalysisException;
import com.bobfull.common.exception.ChatErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.chat.infrastructure.kafka.InvalidChatMessageEventException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class ListenerExceptionUnwrapperTest {

    @Test void ModerationAnalysisException은_그대로_getErrorCode를_반환한다() {
        ModerationAnalysisException exception = new ModerationAnalysisException("OPENAI_TIMEOUT");
        assertThat(ListenerExceptionUnwrapper.errorCodeOf(exception)).isEqualTo("OPENAI_TIMEOUT");
    }

    @Test void ListenerExecutionFailedException으로_감싼_ModerationAnalysisException도_errorCode를_추출한다() {
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener failed",
                new ModerationAnalysisException("VALIDATION_FAILED"));
        assertThat(ListenerExceptionUnwrapper.errorCodeOf(wrapped)).isEqualTo("VALIDATION_FAILED");
    }

    @Test void CustomException은_ErrorCode의_code를_반환한다() {
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener failed",
                new CustomException(ChatErrorCode.CHAT_MESSAGE_ID_NOT_FOUND));
        assertThat(ListenerExceptionUnwrapper.errorCodeOf(wrapped)).isEqualTo("CHAT_MESSAGE_ID_NOT_FOUND");
    }

    @Test void 알수없는_예외는_최종_원인의_클래스_단순명을_반환한다() {
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener failed",
                new InvalidChatMessageEventException("잘못된 eventVersion"));
        assertThat(ListenerExceptionUnwrapper.errorCodeOf(wrapped)).isEqualTo("InvalidChatMessageEventException");
    }
}
