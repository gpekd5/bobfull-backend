package com.bobfull.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.portone.sdk.server.webhook.Webhook;
import io.portone.sdk.server.webhook.WebhookTransactionCancelledCancelled;
import io.portone.sdk.server.webhook.WebhookVerifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Issue #146 K6 Harness가 {@code /api/webhooks/portone}으로 직접 서명해 보낼
 * {@code Transaction.Cancelled} 페이로드 형식이, 실제 PortOne 서버 SDK({@link WebhookVerifier})가
 * 요구하는 서명·JSON 계약과 정확히 일치하는지 확인한다. K6는 JavaScript로 같은 서명 절차를
 * 재현하므로(k6/common/portoneWebhook.js), 이 테스트가 그 절차의 정답 기준이 된다.
 *
 * <p>{@link WebhookVerifier#WebhookVerifier(String)}는 인자가 {@code "whsec_"}로 시작하면 그
 * 접두어를 떼고 나머지를 base64 디코드하고, 그렇지 않으면 문자열 전체를 그대로 base64 디코드해
 * HMAC 키로 쓴다(SDK jar 역어셈블로 확인). 두 형태 모두 실제 배포 환경에서 쓰일 수 있으므로
 * ({@code application-local.yml}의 실제 값은 {@code "whsec_"}로 시작하고, JUnit 전용
 * {@code application-performance.yml}의 값은 그렇지 않다) 둘 다 검증한다.</p>
 */
class PortOnePerformanceWebhookSigningContractTest {

    @Test
    void whsec_접두어가_없는_설정값도_전체를_base64_디코드해_검증을_통과한다() throws Exception {
        verifySigningRoundTrip("d2hzZWNfcmVzZXJ2YXRpb24tc2VhdC1jb25jdXJyZW5jeQ==");
    }

    @Test
    void whsec_접두어가_있는_설정값은_접두어를_뗀_나머지만_base64_디코드해_검증을_통과한다() throws Exception {
        verifySigningRoundTrip("whsec_dGVzdC1vbmx5LXN5bnRoZXRpYy1zZWNyZXQtMTQ2");
    }

    private void verifySigningRoundTrip(String webhookSecretConfig) throws Exception {
        String body = """
                {"type":"Transaction.Cancelled","timestamp":"2026-08-13T03:00:00.000Z",\
                "data":{"paymentId":"perf-payment-1","storeId":"performance-test-store",\
                "transactionId":"perf-tx-perf-payment-1","cancellationId":"perf-cancel-perf-payment-1"}}""";
        String id = "cancelled-1-0-1";
        // WebhookVerifier는 webhook-timestamp를 현재 시각 ±300초로 검증한다(SDK jar 역어셈블로 확인).
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        // k6/common/portoneWebhook.js의 SECRET_KEY_BYTES 계산과 동일한 절차다.
        String base64Part = webhookSecretConfig.startsWith("whsec_")
                ? webhookSecretConfig.substring("whsec_".length())
                : webhookSecretConfig;
        byte[] keyBytes = Base64.getDecoder().decode(base64Part);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        String signedContent = id + "." + timestamp + "." + body;
        String signatureB64 = Base64.getEncoder().encodeToString(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        String signatureHeader = "v1," + signatureB64;

        Webhook webhook = new WebhookVerifier(webhookSecretConfig).verify(body, id, signatureHeader, timestamp);

        assertThat(webhook).isInstanceOf(WebhookTransactionCancelledCancelled.class);
        WebhookTransactionCancelledCancelled cancelled = (WebhookTransactionCancelledCancelled) webhook;
        assertThat(cancelled.getData().getPaymentId()).isEqualTo("perf-payment-1");
        assertThat(cancelled.getData().getCancellationId()).isEqualTo("perf-cancel-perf-payment-1");
    }
}
