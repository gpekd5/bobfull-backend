package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bobfull.payment.application.port.PortOneRefundPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundWebhookServiceTest {

    @Mock private RefundCompletionService completionService;
    @Mock private PortOneRefundPort refundPort;

    @Test
    void Cancelled_웹훅은_PortOne_완료를_재검증한_뒤_공통완료경로로_전달한다() {
        when(refundPort.isCancellationCompleted("payment-1", "cancellation-1")).thenReturn(true);

        service().complete("payment-1", "cancellation-1");

        verify(completionService).completeFromWebhook("payment-1", "cancellation-1");
    }

    @Test
    void PortOne_완료를_재검증하지_못하면_내부완료경로를_호출하지_않는다() {
        when(refundPort.isCancellationCompleted("payment-1", "cancellation-1")).thenReturn(false);

        assertThatThrownBy(() -> service().complete("payment-1", "cancellation-1"))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(completionService);
    }

    @Test
    void CancelPending_웹훅은_처리중상태만_공통서비스에_전달한다() {
        service().markProcessing("payment-1", "cancellation-1");

        verify(completionService).markProcessingFromWebhook("payment-1", "cancellation-1");
    }

    private RefundWebhookService service() {
        return new RefundWebhookService(completionService, refundPort);
    }
}
