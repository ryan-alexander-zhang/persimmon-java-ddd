package com.persimmon.ddd.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;

/**
 * {@code persimmon.outbox.dispatch} (timer, by type), {@code persimmon.outbox.retried} and
 * {@code persimmon.outbox.dead_lettered} (counters, by type and reason), and three gauges read from
 * the store on each scrape: {@code persimmon.outbox.pending} rows not yet sent, {@code
 * persimmon.outbox.lag.seconds} age of the oldest of them, {@code persimmon.outbox.dead_letters}.
 */
public final class MicrometerOutboxMetrics implements OutboxMetrics {
  private final MeterRegistry registry;

  public MicrometerOutboxMetrics(MeterRegistry registry, OutboxStore store, Clock clock) {
    this.registry = registry;
    Gauge.builder("persimmon.outbox.pending", store, OutboxStore::countPending)
        .description("outbox rows not yet sent")
        .register(registry);
    Gauge.builder(
            "persimmon.outbox.lag.seconds",
            store,
            s -> s.oldestPendingCreatedAt().map(t -> Math.max(0.0, Duration.between(t, clock.instant()).toMillis() / 1000.0)).orElse(0.0))
        .description("age of the oldest unsent outbox row")
        .register(registry);
    Gauge.builder("persimmon.outbox.dead_letters", store, OutboxStore::countDeadLetters)
        .description("rows in the dead-letter table")
        .register(registry);
  }

  @Override
  public void dispatched(String type, Duration took) {
    Timer.builder("persimmon.outbox.dispatch").tag("type", type).register(registry).record(took);
  }

  @Override
  public void retried(String type) {
    Counter.builder("persimmon.outbox.retried").tag("type", type).register(registry).increment();
  }

  @Override
  public void deadLettered(String type, OutboxStore.DeadLetterReason reason) {
    Counter.builder("persimmon.outbox.dead_lettered").tag("type", type).tag("reason", reason.name()).register(registry).increment();
  }
}
