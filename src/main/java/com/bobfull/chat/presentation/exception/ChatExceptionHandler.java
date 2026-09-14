package com.bobfull.chat.presentation.exception;

import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Chat 신고 검토에서 발생하는 동시성 충돌을 기존 API 오류로 변환한다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ChatExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleReportOptimisticLockException(
            ObjectOptimisticLockingFailureException e
    ) {
        return ResponseEntity.status(ChatErrorCode.CHAT_ROOM_REPORT_ALREADY_REVIEWED.getHttpStatus())
                .body(ApiResponse.fail(ChatErrorCode.CHAT_ROOM_REPORT_ALREADY_REVIEWED));
    }
}
