package com.bobfull.payment.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 결제·환불 요청에서 사용하는 HTTP 상태와 오류 메시지를 정의한다.
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements BaseErrorCode {

    DUPLICATE_PAYMENT_ID(HttpStatus.CONFLICT, "이미 존재하는 paymentId입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),
    PAYMENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "결제 접근 권한이 없습니다."),
    PAYMENT_VERIFICATION_FAILED(HttpStatus.CONFLICT, "결제 검증에 실패했습니다."),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 가능 시간이 만료되었습니다."),
    RESERVATION_CONFIRMATION_NOT_READY(HttpStatus.CONFLICT, "예약 확정 기능이 아직 준비되지 않았습니다."),
    REFUND_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "환불을 찾을 수 없습니다."),
    PAYMENT_NOT_REFUNDABLE(HttpStatus.CONFLICT, "환불 가능한 결제 상태가 아닙니다."),
    REFUND_ALREADY_REQUESTED(HttpStatus.CONFLICT, "환불이 이미 요청되었습니다."),
    REFUND_PROCESSING(HttpStatus.CONFLICT, "환불 처리 중입니다."),
    REFUND_FAILED(HttpStatus.CONFLICT, "환불에 실패했습니다."),
    PORTONE_REFUND_FAILED(HttpStatus.BAD_GATEWAY, "PortOne 환불 요청에 실패했습니다."),
    REFUND_RECONCILIATION_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR,
            "환불 처리 결과를 확인하는 중 문제가 발생했습니다. 잠시 후 상태를 다시 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }

}
