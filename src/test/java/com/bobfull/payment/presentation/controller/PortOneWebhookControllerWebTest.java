package com.bobfull.payment.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bobfull.common.config.ClockConfig;
import com.bobfull.common.exception.CustomException;
import com.bobfull.payment.domain.exception.PaymentErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.auth.infrastructure.security.SecurityConfig;
import com.bobfull.payment.application.port.PortOneWebhookVerifier;
import com.bobfull.payment.application.service.PaymentCompletionService;
import com.bobfull.payment.application.service.RefundWebhookService;
import com.bobfull.auth.infrastructure.redis.AccessTokenBlacklistStore;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PortOneWebhookController.class)
@Import({SecurityConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=webhook-web-test-secret-key-please-keep-this-long-enough",
        "jwt.access-token-expiration-seconds=1800"
})
class PortOneWebhookControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AccessTokenBlacklistStore accessTokenBlacklistStore;
    @MockitoBean private PortOneWebhookVerifier webhookVerifier;
    @MockitoBean private PaymentCompletionService paymentCompletionService;
    @MockitoBean private RefundWebhookService refundWebhookService;
    @MockitoBean private BusinessMetricRecorder businessMetricRecorder;

    @Test
    void 필수_서명헤더_누락은_400이고_결제처리를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/webhooks/portone").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(webhookVerifier, paymentCompletionService);
    }

    @Test
    void 서명검증_실패는_400이다() throws Exception {
        when(webhookVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new WebhookVerificationException("invalid", null));

        performSignedRequest().andExpect(status().isBadRequest());
        verifyNoInteractions(paymentCompletionService);
    }

    @Test
    void 미지원_이벤트는_200이다() throws Exception {
        when(webhookVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PortOneWebhookVerifier.WebhookEvent(null));

        performSignedRequest().andExpect(status().isOk());
        verifyNoInteractions(paymentCompletionService);
    }

    @Test
    void 알려진_영구업무실패는_JWT_없이도_200이다() throws Exception {
        when(webhookVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PortOneWebhookVerifier.WebhookEvent("payment-id"));
        doThrow(new CustomException(PaymentErrorCode.PAYMENT_EXPIRED))
                .when(paymentCompletionService).completeFromWebhook("payment-id");

        performSignedRequest().andExpect(status().isOk());
    }

    @Test
    void 예상하지_못한_오류는_5xx다() throws Exception {
        when(webhookVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PortOneWebhookVerifier.WebhookEvent("payment-id"));
        doThrow(new IllegalStateException("infrastructure failure"))
                .when(paymentCompletionService).completeFromWebhook("payment-id");

        performSignedRequest().andExpect(status().isInternalServerError());
    }

    @Test
    void 분류되지_않은_CustomException은_5xx다() throws Exception {
        when(webhookVerifier.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PortOneWebhookVerifier.WebhookEvent("payment-id"));
        doThrow(new CustomException(PaymentErrorCode.PAYMENT_ACCESS_DENIED))
                .when(paymentCompletionService).completeFromWebhook("payment-id");

        performSignedRequest().andExpect(status().isInternalServerError());
    }

    private org.springframework.test.web.servlet.ResultActions performSignedRequest() throws Exception {
        return mockMvc.perform(post("/api/webhooks/portone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(WebhookVerifier.HEADER_ID, "id")
                .header(WebhookVerifier.HEADER_SIGNATURE, "signature")
                .header(WebhookVerifier.HEADER_TIMESTAMP, "timestamp"));
    }
}
