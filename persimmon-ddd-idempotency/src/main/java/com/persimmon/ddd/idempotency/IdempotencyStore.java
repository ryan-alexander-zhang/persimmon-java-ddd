package com.persimmon.ddd.idempotency;

import java.time.Duration;
import java.util.Map;

/** Shared state behind the filter; one row per (tenant, principal, key). */
public interface IdempotencyStore {
  record Key(String tenant, String principal, String value) {}

  record StoredResponse(int status, byte[] body, Map<String, String> headers) {}

  sealed interface Claim {
    record Won(String token) implements Claim {}

    record InProgress() implements Claim {}

    record OutcomeUnknown() implements Claim {}

    record Mismatch() implements Claim {}

    record Replay(StoredResponse response) implements Claim {}
  }

  /**
   * Takes an unused key under the caller's {@code token}, which {@link Claim.Won} returns; an
   * expired pending lease has an unknown outcome and cannot be retaken. The caller mints the token
   * so it can release a claim whose acknowledgement was lost.
   */
  Claim claim(Key key, String fingerprint, Duration lease, String token);

  /** Saves the owning claim's response, even after its lease; throws if ownership was lost. */
  void complete(Key key, String token, StoredResponse response, Duration ttl);

  /** Releases only the owning pending claim, when its outcome is known to permit another execution. */
  void abandon(Key key, String token);

  /** Deletes expired completed responses; pending outcomes are never purged. Returns rows actually deleted. */
  int deleteExpired(int limit);
}
