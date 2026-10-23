package com.persimmon.ddd.application;

import java.time.Instant;

/** An integration event as delivered to a consumer: the payload plus its CloudEvents-style metadata. */
public record EventEnvelope<T extends IntegrationEvent>(
    String eventId,
    String source,
    String type,
    int version,
    Instant occurredAt,
    String subject,
    String correlationId,
    String causationId,
    T payload) {}
