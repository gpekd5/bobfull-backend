package com.bobfull.payment.application.port;

import com.bobfull.payment.application.dto.CreateReadyPaymentCommand;
import com.bobfull.payment.application.dto.CreateReadyPaymentResult;

/**
 * 예약 도메인이 결제 구현 세부에 의존하지 않고 READY Payment 생성을 요청하는 계약이다.
 */
public interface ReadyPaymentCreator {

    CreateReadyPaymentResult createReadyPayment(CreateReadyPaymentCommand command);
}
