package com.persimmon.ddd.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with full jitter in the upper half: delay in [cap/2, cap]. */
public final class RetryBackoff {
  private final long baseMillis;
  private final long maxMillis;

  public RetryBackoff(Duration base, Duration max) {
    this.baseMillis = base.toMillis();
    this.maxMillis = Math.max(baseMillis, max.toMillis());
  }

  public Duration nextDelay(int attempts) {
    int exponent = Math.min(Math.max(0, attempts - 1), 40);
    long cap = Math.min(maxMillis, baseMillis << exponent);
    long half = cap / 2;
    return Duration.ofMillis(half + (half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1)));
  }
}
