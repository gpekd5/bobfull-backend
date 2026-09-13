package com.bobfull.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bobfull.payment.domain.entity.RefundStatus;
import com.bobfull.payment.application.port.ReservationCancellationCompletionPort;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundCompletionServiceTest {

    @Mock RefundTransactionService transactionService;
    @Mock ReservationCancellationCompletionPort cancellationCompletionPort;

    private RefundCompletionService service() {
        return new RefundCompletionService(transactionService, cancellationCompletionPort);
    }

    @Test
    void 즉시응답이_완료면_예약취소_완료Port를_호출한다() {
        Instant completedAt = Instant.parse("2026-08-05T00:00:00Z");
        var completion = new RefundTransactionService.RefundCompletion(
                RefundStatus.COMPLETED, 1L, 2L, completedAt);
        when(transactionService.reflectExternalResult(10L, "cancel-1", true)).thenReturn(completion);

        var result = service().reflectExternalResult(10L, "cancel-1", true);

        assertThat(result).isEqualTo(completion);
        verify(cancellationCompletionPort).complete(1L, 2L, completedAt);
    }

    @Test
    void 처리중_응답이면_예약취소_완료Port를_호출하지_않는다() {
        var completion = new RefundTransactionService.RefundCompletion(
                RefundStatus.PROCESSING, 1L, 2L, null);
        when(transactionService.reflectExternalResult(10L, "cancel-1", false)).thenReturn(completion);

        service().reflectExternalResult(10L, "cancel-1", false);

        verify(cancellationCompletionPort, never()).complete(1L, 2L, null);
    }

    @Test
    void 웹훅_완료도_같은_예약취소_완료Port를_호출한다() {
        Instant completedAt = Instant.parse("2026-08-05T00:00:00Z");
        var completion = new RefundTransactionService.RefundCompletion(
                RefundStatus.COMPLETED, 1L, 2L, completedAt);
        when(transactionService.completeFromWebhook("payment-1", "cancel-1"))
                .thenReturn(Optional.of(completion));

        service().completeFromWebhook("payment-1", "cancel-1");

        verify(cancellationCompletionPort).complete(1L, 2L, completedAt);
    }
}
