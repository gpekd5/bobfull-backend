package com.bobfull.notification.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.common.outbox.service.OutboxEventTransactionService;
import com.bobfull.reservation.application.service.ReservationNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailOutboxProcessorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
    @Mock OutboxEventRepository eventRepository;
    @Mock EmailOutboxDeliveryRepository deliveryRepository;
    @Mock OutboxEventTransactionService transactionService;
    @Mock EmailOutboxDeliveryTransactionService deliveryTransactionService;
    @Mock ReservationNotificationService notificationService;

    @Test
    void A와_C가_성공하고_B가_실패한_뒤_재처리하면_B만_다시_발송한다() {
        EmailOutboxDelivery a = delivery(1L, 11L);
        EmailOutboxDelivery b = delivery(2L, 12L);
        EmailOutboxDelivery c = delivery(3L, 13L);
        when(transactionService.claim(any(), anyList(), any())).thenReturn(java.util.Optional.of(claimed(0)), java.util.Optional.of(claimed(1)));
        when(deliveryRepository.findAllByOutboxEventIdAndStatus(100L, EmailDeliveryStatus.PENDING))
                .thenReturn(List.of(a, b, c), List.of(b));
        when(deliveryRepository.existsByOutboxEventIdAndStatus(100L, EmailDeliveryStatus.PENDING)).thenReturn(false);
        doThrow(new IllegalStateException("SMTP_FAILED")).doNothing().when(notificationService).sendOutboxEmail(any(), org.mockito.ArgumentMatchers.eq(b));
        when(transactionService.fail(any(), any(), any(), anyInt()))
                .thenReturn(new OutboxEventTransactionService.FailureResult(true, false, 1, CLOCK.instant()));

        EmailOutboxProcessor processor = processor();
        processor.process(100L);
        processor.process(100L);

        verify(notificationService, times(1)).sendOutboxEmail(any(), org.mockito.ArgumentMatchers.eq(a));
        verify(notificationService, times(2)).sendOutboxEmail(any(), org.mockito.ArgumentMatchers.eq(b));
        verify(notificationService, times(1)).sendOutboxEmail(any(), org.mockito.ArgumentMatchers.eq(c));
        verify(transactionService).complete(any(), any());
    }

    @Test
    void SMTP_실패가_반복되면_공통_Outbox_FAILED_정책으로_수렴한다() {
        EmailOutboxDelivery failed = delivery(1L, 11L);
        when(transactionService.claim(any(), anyList(), any())).thenReturn(java.util.Optional.of(claimed(0)), java.util.Optional.of(claimed(1)),
                java.util.Optional.of(claimed(2)), java.util.Optional.of(claimed(3)), java.util.Optional.of(claimed(4)), java.util.Optional.of(claimed(5)));
        when(deliveryRepository.findAllByOutboxEventIdAndStatus(100L, EmailDeliveryStatus.PENDING)).thenReturn(List.of(failed));
        doThrow(new IllegalStateException("SMTP_FAILED")).when(notificationService).sendOutboxEmail(any(), any());
        when(transactionService.fail(any(), any(), any(), anyInt())).thenReturn(
                new OutboxEventTransactionService.FailureResult(true, false, 1, CLOCK.instant()),
                new OutboxEventTransactionService.FailureResult(true, false, 2, CLOCK.instant()),
                new OutboxEventTransactionService.FailureResult(true, false, 3, CLOCK.instant()),
                new OutboxEventTransactionService.FailureResult(true, false, 4, CLOCK.instant()),
                new OutboxEventTransactionService.FailureResult(true, false, 5, CLOCK.instant()),
                new OutboxEventTransactionService.FailureResult(true, true, 6, CLOCK.instant()));

        EmailOutboxProcessor processor = processor();
        for (int i = 0; i < 6; i++) processor.process(100L);

        verify(transactionService, times(6)).fail(any(), any(), any(), anyInt());
    }

    private EmailOutboxProcessor processor() {
        return new EmailOutboxProcessor(eventRepository, deliveryRepository, transactionService,
                deliveryTransactionService, notificationService, CLOCK);
    }
    private OutboxEventTransactionService.ClaimedOutboxEvent claimed(int attempt) {
        return new OutboxEventTransactionService.ClaimedOutboxEvent(100L, "EMAIL_RECRUITMENT_CONFIRMED", 10L, attempt, "token");
    }
    private EmailOutboxDelivery delivery(Long id, Long memberId) {
        EmailOutboxDelivery delivery = EmailOutboxDelivery.pending(100L, 10L, id, memberId);
        ReflectionTestUtils.setField(delivery, "id", id);
        return delivery;
    }
}
