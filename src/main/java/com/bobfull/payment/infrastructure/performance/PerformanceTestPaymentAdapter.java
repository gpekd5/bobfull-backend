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

/**
 * Issue #146 K6 성능 측정 전용 대체 구현이다. 실제 PortOne 결제 조회 API를 호출하지 않고
 * 저장된 Payment의 금액·통화를 그대로 돌려줘 {@code performance} 프로파일에서 웹훅 기반
 * PAID 확정을 실제 외부 호출 없이 재현한다. 다른 프로파일에서는 {@link PortOneSdkPaymentAdapter}가 그대로 쓰인다.
 *
 * <p><b>운영 주의:</b> 이 Bean은 실제 PortOne 검증을 완전히 건너뛰고 저장된 값만 보고 항상
 * PAID로 응답한다 — {@code performance}가 운영 배포의 {@code SPRING_PROFILES_ACTIVE}에 절대
 * 섞이지 않아야 한다(결제 검증 자체가 무력화된다).</p>
 */
@Component
@Profile("performance")
@Primary
@RequiredArgsConstructor
public class PerformanceTestPaymentAdapter implements PortOnePaymentPort {

    private final PaymentRepository paymentRepository;

    @Override
    public PortOnePayment read(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new CustomException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        return new PortOnePayment(paymentId, true, payment.getAmount(), payment.getCurrency());
    }
}
