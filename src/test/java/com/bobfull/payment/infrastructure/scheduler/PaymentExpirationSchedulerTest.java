package com.bobfull.payment.infrastructure.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.bobfull.payment.application.service.PaymentExpirationProcessor;
import com.bobfull.payment.domain.entity.PaymentStatus;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PaymentExpirationSchedulerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentExpirationProcessor processor;

    @Test
    void 한_후보_처리_실패후에도_다음_내부PK를_계속_처리한다() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        given(paymentRepository.findExpirationCandidateIds(org.mockito.ArgumentMatchers.eq(PaymentStatus.READY),
                org.mockito.ArgumentMatchers.eq(now), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .willReturn(List.of(10L, 20L));
        doThrow(new IllegalStateException("db failure")).when(processor).expire(10L);
        PaymentExpirationScheduler scheduler = new PaymentExpirationScheduler(paymentRepository, processor,
                Clock.fixed(now, ZoneOffset.UTC), 100);

        scheduler.expireReadyPayments();

        InOrder order = inOrder(processor);
        order.verify(processor).expire(10L);
        order.verify(processor).expire(20L);
    }
}
