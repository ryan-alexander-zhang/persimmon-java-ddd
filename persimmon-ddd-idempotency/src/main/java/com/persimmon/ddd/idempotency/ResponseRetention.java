package com.persimmon.ddd.idempotency;

import com.persimmon.ddd.idempotency.IdempotencyStore.StoredResponse;

/**
 * Which responses settle an {@code Idempotency-Key} and are replayed for its TTL. Any other
 * response releases the key, so a retry with the same key runs again. Declare a bean to replace
 * the default, e.g. to also keep a 504 that says the outcome is unknown.
 */
@FunctionalInterface
public interface ResponseRetention {
  /** 2xx to 4xx except 429, as Stripe does: a refusal to run now, or a server failure, is not a result. */
  ResponseRetention DEFAULT = response -> response.status() < 500 && response.status() != 429;

  boolean retain(StoredResponse response);
}
