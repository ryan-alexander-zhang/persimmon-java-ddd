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
    /* After this wait, a pending claim reports an unknown outcome; it still blocks execution. */
    @DefaultValue("1m") Duration lease,
    /* Reject unsafe requests that carry no key. */
    @DefaultValue("false") boolean requireKey,
    @DefaultValue({"POST", "PUT", "PATCH", "DELETE"}) List<String> methods,
    @DefaultValue("1MB") DataSize maxBodySize,
    /* Response headers replayed besides Content-Type, Location, ETag and Content-Language. */
    @DefaultValue List<String> replayedHeaders,
    /* Request headers that are part of "the same request": a different value under the same key is 422. */
    @DefaultValue List<String> fingerprintHeaders,
    /* Request headers that scope the key: another value of one is another key, which runs on its own. */
    @DefaultValue List<String> keyScopeHeaders) {}
