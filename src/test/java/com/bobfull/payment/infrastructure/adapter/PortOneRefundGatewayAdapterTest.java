package com.bobfull.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bobfull.payment.application.port.PortOneRefundRequester.ReconciliationStatus;
import com.bobfull.payment.application.port.PortOneRefundRequester;
import com.bobfull.payment.infrastructure.config.PortOneProperties;
import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.payment.CancelledPayment;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.Payment;
import io.portone.sdk.server.payment.PaymentCancellation;
import io.portone.sdk.server.payment.PaymentClient;
import io.portone.sdk.server.payment.Trigger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PortOneRefundGatewayAdapterTest {

    private static final String PAYMENT_ID = "payment-1";
    // DB DECIMAL(19,2) 컬럼에서 조회된 실제 Refund/Payment.amount와 동일하게 scale=2로 고정한다.
    // scale=0 값만 쓰면 REST 요청 본문에 "10000.00"처럼 소수점이 섞여 나가는 회귀를 테스트가 못 잡는다.
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("10000.00");
    private static final Instant REFUND_REQUESTED_AT = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void REST_환불요청은_저장된_값과_멱등성헤더를_전달하고_완료로_변환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.portone.io/payments/payment-1/cancel"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "PortOne secret"))
                .andExpect(header("Idempotency-Key", "\"raw-key-123456789\""))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"storeId\":\"store\",\"amount\":10000,\"reason\":\"stored reason\"}"))
                .andRespond(withSuccess("{\"cancellation\":{\"id\":\"cancel-123\",\"status\":\"SUCCEEDED\"}}", MediaType.APPLICATION_JSON));
        var result = restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "stored reason", "raw-key-123456789");
        assertThat(result.cancellationId()).isEqualTo("cancel-123");
        assertThat(result.completed()).isTrue();
        server.verify();
    }

    @Test
    void REST_환불요청의_REQUESTED는_처리중으로_변환한다() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel")).andRespond(withSuccess("{\"cancellation\":{\"id\":\"cancel-123\",\"status\":\"REQUESTED\"}}", MediaType.APPLICATION_JSON));
        assertThat(restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "reason", "raw-key-123456789").completed()).isFalse(); server.verify();
    }

    @Test
    void REST_환불요청의_FAILED만_명시적실패다() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel")).andRespond(withSuccess("{\"cancellation\":{\"id\":\"cancel-123\",\"status\":\"FAILED\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "reason", "raw-key-123456789")).isInstanceOf(PortOneRefundRequester.ExplicitRefundFailureException.class); server.verify();
    }

    @Test
    void SUCCEEDED_응답에_cancellationId가_없으면_명시적실패가_아니다() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel")).andRespond(withSuccess("{\"cancellation\":{\"status\":\"SUCCEEDED\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "reason", "raw-key-123456789")).isNotInstanceOf(PortOneRefundRequester.ExplicitRefundFailureException.class);
        server.verify();
    }

    @Test
    void 알수없는_상태값은_명시적실패가_아니다() {
        RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel")).andRespond(withSuccess("{\"cancellation\":{\"id\":\"cancel-123\",\"status\":\"UNKNOWN\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "reason", "raw-key-123456789")).isNotInstanceOf(PortOneRefundRequester.ExplicitRefundFailureException.class);
        server.verify();
    }

    @Test
    void HTTP_오류와_잘못된응답은_명시적실패가아니다() {
        for (var response : List.of(withStatus(HttpStatus.CONFLICT).body("{\"type\":\"IDEMPOTENCY_OUTSTANDING_REQUEST\"}"), withServerError(), withSuccess("not-json", MediaType.APPLICATION_JSON))) {
            RestClient.Builder builder = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel")).andRespond(response);
            assertThatThrownBy(() -> restAdapter(builder.baseUrl("https://api.portone.io").build()).request(PAYMENT_ID, REFUND_AMOUNT, "reason", "raw-key-123456789")).isNotInstanceOf(PortOneRefundRequester.ExplicitRefundFailureException.class);
            server.verify();
        }
    }

    @Test
    void 취소접수응답은_PROCESSING으로_해석한다() {
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", null);

        var result = PortOneRefundGatewayAdapter.toRefundResult(cancellation);

        assertThat(result.cancellationId()).isEqualTo("cancel-1");
        assertThat(result.completed()).isFalse();
        assertThat(PortOneRefundGatewayAdapter.isCompletedCancellation(cancellation, "cancel-1")).isFalse();
    }

    @Test
    void cancelledAt이_있는_취소만_완료로_해석한다() {
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", Instant.parse("2026-08-04T00:00:00Z"));

        var result = PortOneRefundGatewayAdapter.toRefundResult(cancellation);

        assertThat(result.completed()).isTrue();
        assertThat(PortOneRefundGatewayAdapter.isCompletedCancellation(cancellation, "cancel-1")).isTrue();
        assertThat(PortOneRefundGatewayAdapter.isCompletedCancellation(cancellation, "other")).isFalse();
    }

    private PaymentCancellation.Recognized cancellation(String id, Instant cancelledAt) {
        PaymentCancellation.Recognized cancellation = Mockito.mock(PaymentCancellation.Recognized.class);
        when(cancellation.getId()).thenReturn(id);
        when(cancellation.getCancelledAt()).thenReturn(cancelledAt);
        return cancellation;
    }

    @Test
    void 저장된_cancellationId가_완료로_확인되면_COMPLETED를_반환한다() {
        Instant cancelledAt = Instant.parse("2026-08-05T00:01:00Z");
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", 10000L, cancelledAt,
                Instant.parse("2026-08-05T00:00:30Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(cancellation));

        var result = adapter.reconcile(PAYMENT_ID, "cancel-1", REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.COMPLETED);
        assertThat(result.cancellationId()).isEqualTo("cancel-1");
        assertThat(result.cancelledAt()).isEqualTo(cancelledAt);
    }

    @Test
    void 저장된_cancellationId가_아직_취소중이면_PROCESSING을_반환한다() {
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", 10000L, null,
                Instant.parse("2026-08-05T00:00:30Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(paidPayment(cancellation));

        var result = adapter.reconcile(PAYMENT_ID, "cancel-1", REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.PROCESSING);
        assertThat(result.cancellationId()).isEqualTo("cancel-1");
    }

    @Test
    void 저장된_cancellationId의_금액이_다르면_AMBIGUOUS를_반환한다() {
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", 5000L,
                Instant.parse("2026-08-05T00:01:00Z"), Instant.parse("2026-08-05T00:00:30Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(cancellation));

        var result = adapter.reconcile(PAYMENT_ID, "cancel-1", REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.AMBIGUOUS);
    }

    @Test
    void 저장된_cancellationId를_찾지_못하면_NOT_COMPLETED를_반환한다() {
        PaymentCancellation.Recognized other = cancellation("cancel-2", 10000L,
                Instant.parse("2026-08-05T00:01:00Z"), Instant.parse("2026-08-05T00:00:30Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(other));

        var result = adapter.reconcile(PAYMENT_ID, "cancel-1", REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.NOT_COMPLETED);
    }

    @Test
    void cancellationId가_없고_전액취소_단일후보가_일치하면_COMPLETED를_반환한다() {
        Instant cancelledAt = Instant.parse("2026-08-05T00:01:00Z");
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", 10000L, cancelledAt,
                Instant.parse("2026-08-05T00:00:10Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(cancellation));

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.COMPLETED);
        assertThat(result.cancellationId()).isEqualTo("cancel-1");
        assertThat(result.cancelledAt()).isEqualTo(cancelledAt);
    }

    /** #148 리뷰 반영: PG는 전액 취소를 확정했는데 매칭 기준(trigger 등)에 맞는 후보가 0건이면
     * 재요청 없이도 AMBIGUOUS로 남겨 즉시 운영 경고가 나가야 한다(#141 계약 수정). */
    @Test
    void 전액취소인데_일치하는_후보가_없으면_AMBIGUOUS를_반환한다() {
        PaymentCancellation.Recognized cancellation = cancellation("cancel-1", 10000L,
                Instant.parse("2026-08-05T00:01:00Z"), Instant.parse("2026-08-05T00:00:10Z"), Trigger.Console.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(cancellation));

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.AMBIGUOUS);
    }

    @Test
    void 전액취소인데_후보가_여러건이면_AMBIGUOUS를_반환한다() {
        PaymentCancellation.Recognized first = cancellation("cancel-1", 10000L,
                Instant.parse("2026-08-05T00:01:00Z"), Instant.parse("2026-08-05T00:00:10Z"), Trigger.Api.INSTANCE);
        PaymentCancellation.Recognized second = cancellation("cancel-2", 10000L,
                Instant.parse("2026-08-05T00:02:00Z"), Instant.parse("2026-08-05T00:00:20Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(first, second));

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.AMBIGUOUS);
    }

    /** #148 재검토 반영: 매칭 기준(조건 1~5)을 만족하는 후보가 정확히 1개면, 같은 Payment에 다른
     * 인식된 취소 내역(트리거 불일치 등으로 매칭에서 제외된 내역)이 더 있어도 완료로 인정해야 한다.
     * Issue #141 확정 계약은 "조건을 만족하는 후보"의 개수만 기준으로 삼으며, Payment의 전체
     * 인식된 취소 건수를 별도로 제한하지 않는다. */
    @Test
    void 전액취소인데_매칭되지않는_다른_취소내역이_있어도_단일후보면_COMPLETED를_반환한다() {
        Instant cancelledAt = Instant.parse("2026-08-05T00:01:00Z");
        PaymentCancellation.Recognized matching = cancellation("cancel-1", 10000L, cancelledAt,
                Instant.parse("2026-08-05T00:00:10Z"), Trigger.Api.INSTANCE);
        PaymentCancellation.Recognized nonMatching = cancellation("cancel-2", 10000L,
                Instant.parse("2026-08-05T00:02:00Z"), Instant.parse("2026-08-05T00:00:20Z"), Trigger.Console.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(cancelledPayment(matching, nonMatching));

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.COMPLETED);
        assertThat(result.cancellationId()).isEqualTo("cancel-1");
    }

    @Test
    void 아직_전액취소가_아니고_취소내역도_없으면_NOT_COMPLETED를_반환한다() {
        PortOneRefundGatewayAdapter adapter = adapterFor(paidPayment());

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.NOT_COMPLETED);
    }

    @Test
    void 아직_전액취소가_아닌데_부분취소_내역이_있으면_AMBIGUOUS를_반환한다() {
        PaymentCancellation.Recognized partial = cancellation("cancel-1", 3000L,
                Instant.parse("2026-08-05T00:01:00Z"), Instant.parse("2026-08-05T00:00:10Z"), Trigger.Api.INSTANCE);
        PortOneRefundGatewayAdapter adapter = adapterFor(paidPayment(partial));

        var result = adapter.reconcile(PAYMENT_ID, null, REFUND_AMOUNT, REFUND_REQUESTED_AT);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.AMBIGUOUS);
    }

    private PaymentCancellation.Recognized cancellation(String id, long totalAmount, Instant cancelledAt,
                                                         Instant requestedAt, Trigger trigger) {
        PaymentCancellation.Recognized cancellation = Mockito.mock(PaymentCancellation.Recognized.class);
        when(cancellation.getId()).thenReturn(id);
        when(cancellation.getTotalAmount()).thenReturn(totalAmount);
        when(cancellation.getCancelledAt()).thenReturn(cancelledAt);
        when(cancellation.getRequestedAt()).thenReturn(requestedAt);
        when(cancellation.getTrigger()).thenReturn(trigger);
        return cancellation;
    }

    private CancelledPayment cancelledPayment(PaymentCancellation... cancellations) {
        CancelledPayment payment = Mockito.mock(CancelledPayment.class);
        when(payment.getCancellations()).thenReturn(List.of(cancellations));
        return payment;
    }

    private PaidPayment paidPayment(PaymentCancellation... cancellations) {
        PaidPayment payment = Mockito.mock(PaidPayment.class);
        when(payment.getCancellations()).thenReturn(List.of(cancellations));
        return payment;
    }

    private PortOneRefundGatewayAdapter adapterFor(Payment payment) {
        PaymentClient paymentClient = Mockito.mock(PaymentClient.class);
        when(paymentClient.getPayment(PAYMENT_ID)).thenReturn(CompletableFuture.completedFuture(payment));
        PortOneClient portOneClient = Mockito.mock(PortOneClient.class);
        when(portOneClient.getPayment()).thenReturn(paymentClient);
        return new PortOneRefundGatewayAdapter(portOneClient);
    }

    private PortOneRefundGatewayAdapter restAdapter(RestClient restClient) {
        return new PortOneRefundGatewayAdapter(Mockito.mock(PortOneClient.class), restClient,
                new PortOneProperties("secret", "store", "webhook"));
    }
}
