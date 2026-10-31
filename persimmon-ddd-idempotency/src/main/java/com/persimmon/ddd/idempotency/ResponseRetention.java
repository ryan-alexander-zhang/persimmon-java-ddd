package com.persimmon.ddd.idempotency;

import com.persimmon.ddd.idempotency.IdempotencyStore.StoredResponse;

/**
 * Which responses settle an {@code Idempotency-Key} and are replayed for its TTL. Any other
 * response releases the key, so a retry with the same key runs again. Declare a bean to replace
 * the default, e.g. to also keep a 504 that says the outcome is unknown. Returning false authorizes
 * another execution; external side effects with unknown outcomes must be retained.
 */
@FunctionalInterface
public interface ResponseRetention {
  /** Keeps responses below 500 except 429; external integrations must also retain uncertain outcomes. */
  ResponseRetention DEFAULT = response -> response.status() < 500 && response.status() != 429;

  boolean retain(StoredResponse response);
}
