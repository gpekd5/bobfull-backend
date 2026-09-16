package com.bobfull.payment.infrastructure.performance;

import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.payment.domain.entity.Payment;
import com.bobfull.payment.application.port.PortOnePaymentPort;
import com.bobfull.payment.infrastructure.portone.PortOneSdkPaymentAdapter;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 성능 측정에서 PortOne 호출 없이 저장된 결제를 PAID 응답으로 재현한다.
// 실제 결제 검증을 건너뛰므로 performance 프로파일은 운영 환경에서 절대 활성화하면 안 된다.
@Component
@Profile("performance")
@Primary
@RequiredArgsConstructor
public class PerformanceTestPaymentAdapter implements PortOnePaymentPort {

    private final PaymentRepository paymentRepository;

    // 저장된 금액과 통화를 그대로 반환해 외부 네트워크 없이 완료 흐름을 측정한다.
    @Override
    public PortOnePayment read(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return new PortOnePayment(paymentId, true, payment.getAmount(), payment.getCurrency());
    }
}
