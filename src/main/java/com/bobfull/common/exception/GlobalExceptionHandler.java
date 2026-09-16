package com.bobfull.common.exception;

import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// Controller 예외를 공통 API 응답으로 변환하고 처리되지 않은 실패를 관측한다.
// Security 필터 단계의 인증·인가는 전용 EntryPoint와 AccessDeniedHandler가 처리한다.
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectProvider<BusinessMetricRecorder> businessMetricRecorderProvider;

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        BaseErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e
    ) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.fail(CommonErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequestParameterException(Exception e) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.fail(CommonErrorCode.INVALID_INPUT_VALUE));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) {
        if ("/api/webhooks/portone".equals(request.getRequestURI())) {
            log.error("event=PORTONE_WEBHOOK_PROCESSING_FAILED method={} path={} paymentId={} cancellationId={} reason={}",
                    request.getMethod(), request.getRequestURI(),
                    request.getAttribute("portonePaymentId"),
                    request.getAttribute("portoneCancellationId"),
                    e.getClass().getSimpleName(), e);
            incrementBusinessMetric(BusinessMetricEvent.PORTONE_WEBHOOK_PROCESSING_FAILED);
        } else {
            log.error("event=UNHANDLED_EXCEPTION method={} path={} reason={}",
                    request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName(), e);
            incrementBusinessMetric(BusinessMetricEvent.UNHANDLED_EXCEPTION);
        }
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.fail(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private void incrementBusinessMetric(BusinessMetricEvent event) {
        BusinessMetricRecorder businessMetricRecorder = businessMetricRecorderProvider.getIfAvailable();
        if (businessMetricRecorder != null) {
            businessMetricRecorder.increment(event);
        }
    }
}
