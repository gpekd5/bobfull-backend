package com.bobfull.payment.infrastructure.reservation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bobfull.reservation.application.service.ReservationCancellationCompletionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationCompletionAdapterTest {

    @Mock ReservationCancellationCompletionService completionService;

    @Test
    void 전달받은_식별자와_시각을_그대로_완료Service에_전달한다() {
        Instant completedAt = Instant.parse("2026-08-05T00:00:00Z");
        var adapter = new ReservationCancellationCompletionAdapter(completionService);

        adapter.complete(1L, 2L, completedAt);

        verify(completionService).complete(1L, 2L, completedAt);
        verifyNoMoreInteractions(completionService);
    }
}
