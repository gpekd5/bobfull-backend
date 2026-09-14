package com.bobfull.common.outbox.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test void chatMessageCreated는_CHAT_MESSAGE_타입과_messageId를_aggregateId로_PENDING상태로_생성한다() {
        OutboxEvent event = OutboxEvent.chatMessageCreated(42L, NOW);

        assertThat(event.getEventType()).isEqualTo(OutboxEventType.CHAT_MESSAGE_CREATED);
        assertThat(event.getAggregateId()).isEqualTo(42L);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getEventId()).isNotBlank();
    }

    @Test void chatMessageCreated는_매번_다른_eventId를_발급해_재시도_중복발행을_구분할_수_있게_한다() {
        OutboxEvent first = OutboxEvent.chatMessageCreated(1L, NOW);
        OutboxEvent second = OutboxEvent.chatMessageCreated(1L, NOW);

        assertThat(first.getEventId()).isNotEqualTo(second.getEventId());
    }
}
