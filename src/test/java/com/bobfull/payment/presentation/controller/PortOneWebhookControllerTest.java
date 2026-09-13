package com.bobfull.payment.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.bobfull.common.exception.*;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.application.port.PortOneWebhookVerifier;
import com.bobfull.payment.application.service.PaymentCompletionService;
import io.portone.sdk.server.errors.WebhookVerificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class PortOneWebhookControllerTest {
 @Mock PortOneWebhookVerifier verifier; @Mock PaymentCompletionService completion; @Mock BusinessMetricRecorder businessMetricRecorder;
 PortOneWebhookController controller(){ return new PortOneWebhookController(verifier, completion, null, businessMetricRecorder); }
 MockHttpServletRequest request(){ return new MockHttpServletRequest(); }
 @Test void 서명_실패는_400이고_완료서비스를_호출하지_않는다() throws Exception { when(verifier.verify(any(),any(),any(),any())).thenThrow(mock(WebhookVerificationException.class)); assertThat(controller().receive("{}","id","signature","timestamp",request()).getStatusCode().value()).isEqualTo(400); verifyNoInteractions(completion); }
 @Test void 미지원_이벤트는_200이다() throws Exception { when(verifier.verify(any(),any(),any(),any())).thenReturn(new PortOneWebhookVerifier.WebhookEvent(null)); assertThat(controller().receive("{}","id","sig","ts",request()).getStatusCode().value()).isEqualTo(200); verifyNoInteractions(completion); }
 @Test void 결제이벤트는_공통처리를_호출한다() throws Exception { when(verifier.verify(any(),any(),any(),any())).thenReturn(new PortOneWebhookVerifier.WebhookEvent("p")); assertThat(controller().receive("{}","id","sig","ts",request()).getStatusCode().value()).isEqualTo(200); verify(completion).completeFromWebhook("p"); }
 @Test void 알려진_업무실패는_200이다() throws Exception { when(verifier.verify(any(),any(),any(),any())).thenReturn(new PortOneWebhookVerifier.WebhookEvent("p")); doThrow(new CustomException(PaymentErrorCode.PAYMENT_EXPIRED)).when(completion).completeFromWebhook("p"); assertThat(controller().receive("{}","id","sig","ts",request()).getStatusCode().value()).isEqualTo(200); }
}
