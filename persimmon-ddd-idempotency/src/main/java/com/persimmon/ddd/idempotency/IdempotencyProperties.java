package com.persimmon.ddd.idempotency;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** {@code persimmon.ddd.idempotency.*}. */
@ConfigurationProperties("persimmon.ddd.idempotency")
public record IdempotencyProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("Idempotency-Key") String header,
    /* How long a stored response is replayed. */
    @DefaultValue("24h") Duration ttl,
    /* How long an in-progress claim blocks retries if the first request never settles. */
    @DefaultValue("1m") Duration lease,
    /* Reject unsafe requests that carry no key. */
    @DefaultValue("false") boolean requireKey,
    @DefaultValue({"POST", "PUT", "PATCH", "DELETE"}) List<String> methods,
    @DefaultValue("1MB") DataSize maxBodySize) {}
