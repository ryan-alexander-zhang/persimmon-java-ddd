package com.persimmon.ddd.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.outbox.*}. */
@ConfigurationProperties("persimmon.ddd.outbox")
public record OutboxProperties(
    /* Rows one poll leases and dispatches. */
    @DefaultValue("100") int batchSize,
    /* Attempts before a transient failure is dead-lettered. */
    @DefaultValue("10") int maxAttempts,
    /* Pause between polls. */
    @DefaultValue("1s") Duration pollDelay,
    /* How long a claimed row stays invisible to other instances. */
    @DefaultValue("5m") Duration lease,
    @DefaultValue Retry retry,
    @DefaultValue Cleanup cleanup) {
  public record Retry(@DefaultValue("1s") Duration baseBackoff, @DefaultValue("1m") Duration maxBackoff) {}

  /* Deletes sent rows older than retention; off unless enabled. */
  public record Cleanup(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("7d") Duration retention,
      @DefaultValue("500") int batchSize,
      @DefaultValue("1h") Duration pollDelay) {}
}
