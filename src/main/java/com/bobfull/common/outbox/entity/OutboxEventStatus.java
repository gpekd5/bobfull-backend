package com.bobfull.common.outbox.entity;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
