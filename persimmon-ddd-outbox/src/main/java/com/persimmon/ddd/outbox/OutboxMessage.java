package com.persimmon.ddd.outbox;

import java.time.Instant;

/** One outbox row as the relay sees it: envelope metadata plus the JSON payload. */
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
    int attempts) {}
