package com.persimmon.ddd.idempotency;

import java.time.Duration;
import java.util.Map;

/** Shared state behind the filter; one row per (tenant, principal, key). */
public interface IdempotencyStore {
  record Key(String tenant, String principal, String value) {}

  record StoredResponse(int status, byte[] body, Map<String, String> headers) {}

  sealed interface Claim {
    record Won() implements Claim {}

    record InProgress() implements Claim {}

    record Mismatch() implements Claim {}

    record Replay(StoredResponse response) implements Claim {}
  }

  /** Atomically takes the key for {@code lease}, or says what already holds it. */
  Claim claim(Key key, String fingerprint, Duration lease);

  void complete(Key key, StoredResponse response, Duration ttl);

  /** Drops a pending claim after a failure so the client's retry can run again. */
  void abandon(Key key);

  int deleteExpired(int limit);
}
