package com.persimmon.ddd.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable state of the outbox, the dead-letter table and the inbox. */
public interface OutboxStore {
  enum DeadLetterReason {
    PERMANENT,
    RETRIES_EXHAUSTED
  }

  /** Inside the caller's transaction. */
  default void insert(OutboxMessage message, Instant createdAt) {
    insert(message, createdAt, null);
  }

  /** Inside the caller's transaction; a {@code dueAt} keeps the row from being claimed before then. */
  void insert(OutboxMessage message, Instant createdAt, Instant dueAt);

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

  /** A dead letter without its payload, for operators. */
  record DeadLetter(
      String eventId, String source, String type, int version, String subject, String correlationId,
      String tenantId, int attempts, String reason, String lastError, Instant failedAt) {}

  /** Moves a dead letter back into the outbox with a clean attempt count; false if unknown. */
  boolean replay(String eventId, Instant now);

  /** Drops a dead letter for good; false if unknown. */
  boolean discard(String eventId);

  /** Newest first. */
  List<DeadLetter> deadLetters(int limit);

  long countPending();

  long countDeadLetters();

  Optional<Instant> oldestPendingCreatedAt();

  int deleteSentBefore(Instant sentBefore, int limit);

  /** Deletes about {@code limit} of the oldest inbox records processed before the cutoff. */
  int deleteInboxBefore(Instant processedBefore, int limit);

  /** Records the message for the consumer; true when it was already recorded. */
  boolean seenBefore(String consumer, String source, String messageKey, Instant now);
}
