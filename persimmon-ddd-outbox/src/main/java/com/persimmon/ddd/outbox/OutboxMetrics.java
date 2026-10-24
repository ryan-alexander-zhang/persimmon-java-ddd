package com.persimmon.ddd.outbox;

import java.time.Duration;

/** What the relay reports; Micrometer when a {@code MeterRegistry} is present, nothing otherwise. */
public interface OutboxMetrics {
  void dispatched(String type, Duration took);

  void retried(String type);

  void deadLettered(String type, OutboxStore.DeadLetterReason reason);

  OutboxMetrics NOOP =
      new OutboxMetrics() {
        @Override
        public void dispatched(String type, Duration took) {}

        @Override
        public void retried(String type) {}

        @Override
        public void deadLettered(String type, OutboxStore.DeadLetterReason reason) {}
      };
}
