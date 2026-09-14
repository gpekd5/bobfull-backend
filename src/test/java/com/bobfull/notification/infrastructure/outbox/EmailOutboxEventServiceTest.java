package com.bobfull.notification.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bobfull.common.outbox.entity.OutboxEvent;
import com.bobfull.common.outbox.entity.OutboxEventType;
import com.bobfull.common.outbox.repository.OutboxEventRepository;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class EmailOutboxEventServiceTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private EmailOutboxDeliveryRepository deliveryRepository;
    @Mock private EmailOutboxSignalDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 커밋_전에는_처리하지_않고_afterCommit에서_Executor_제출만_요청한다() {
        // given
        OutboxEvent event = OutboxEvent.emailNotificationRequested(
                OutboxEventType.EMAIL_RESERVATION_CREATED, "RESERVATION_PARTICIPANT", 10L, Instant.EPOCH);
        ReflectionTestUtils.setField(event, "id", 100L);
        when(outboxEventRepository.save(any())).thenReturn(event);
        TransactionSynchronizationManager.initSynchronization();
        EmailOutboxEventService service = new EmailOutboxEventService(outboxEventRepository, deliveryRepository,
                dispatcher, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        // when
        service.enqueue(OutboxEventType.EMAIL_RESERVATION_CREATED, 1L, List.of(participant()));

        // then
        verifyNoInteractions(dispatcher);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(dispatcher).dispatch(100L);
    }

    private ReservationParticipant participant() {
        ReservationParticipant participant = ReservationParticipant.create(1L, 2L, 1);
        ReflectionTestUtils.setField(participant, "id", 10L);
        return participant;
    }
}
