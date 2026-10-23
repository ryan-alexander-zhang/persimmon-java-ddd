package com.persimmon.ddd.outbox;

import java.time.Instant;

/** One outbox row as the relay sees it: envelope metadata, JSON payload, tenant and trace context. */
public record OutboxMessage(
    String eventId,
    String source,
    String type,
    int version,
    String payload,
    Instant occurredAt,
    String subject,
    String correlationId,
    String causationId,
    String destination,
    String tenantId,
    String traceparent,
    String tracestate,
    int attempts) {}
