package com.persimmon.ddd.outbox;

import java.time.Duration;
import java.util.Map;
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
    @DefaultValue Cleanup cleanup,
    /* Event type (exact, or a prefix ending in '*' such as "ordering.*") -> external destination. Unrouted types stay in process. */
    @DefaultValue Map<String, String> routes) {
  /** Exact match first, then the longest {@code prefix*} that matches; null when none does. */
  public String destinationFor(String type) {
    if (routes.containsKey(type)) {
      return routes.get(type);
    }
    return routes.entrySet().stream()
        .filter(e -> e.getKey().endsWith("*") && type.startsWith(e.getKey().substring(0, e.getKey().length() - 1)))
        .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
        .map(Map.Entry::getValue)
        .orElse(null);
  }

  public record Retry(@DefaultValue("1s") Duration baseBackoff, @DefaultValue("1m") Duration maxBackoff) {}

  /* Deletes sent rows older than retention and inbox records older than inboxRetention; off unless enabled. */
  public record Cleanup(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("7d") Duration retention,
      /* Must outlive the longest possible redelivery (a broker's retention); the inbox is the only dedup. */
      @DefaultValue("30d") Duration inboxRetention,
      @DefaultValue("500") int batchSize,
      @DefaultValue("1h") Duration pollDelay) {}
}
