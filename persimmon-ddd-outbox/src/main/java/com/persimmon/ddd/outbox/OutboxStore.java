package com.persimmon.ddd.outbox;

import java.time.Instant;
import java.util.List;

/** Durable state of the outbox, the dead-letter table and the inbox. */
public interface OutboxStore {
  enum DeadLetterReason {
    PERMANENT,
    RETRIES_EXHAUSTED
  }

  /** Inside the caller's transaction. */
  void insert(OutboxMessage message, Instant createdAt);

  /**
   * Leases up to {@code batchSize} due rows for {@code token} until {@code leaseUntil}, oldest
   * first, never a row whose subject still has an older unsent row, and returns them.
   */
  List<OutboxMessage> claim(Instant now, int maxAttempts, int batchSize, String token, Instant leaseUntil);

  void markSent(List<String> eventIds, Instant sentAt);

  /** Counts the attempt, schedules the next one and drops the lease. */
  void scheduleRetry(String eventId, Instant nextAttemptAt);

  /** Moves the row to the dead-letter table. */
  void deadLetter(OutboxMessage message, int attempts, DeadLetterReason reason, String lastError, Instant failedAt);

  /** Moves a dead letter back into the outbox with a clean attempt count; false if unknown. */
  boolean replay(String eventId, Instant now);

  int deleteSentBefore(Instant sentBefore, int limit);

  /** Records the message for the consumer; true when it was already recorded. */
  boolean seenBefore(String consumer, String source, String messageKey, Instant now);
}
