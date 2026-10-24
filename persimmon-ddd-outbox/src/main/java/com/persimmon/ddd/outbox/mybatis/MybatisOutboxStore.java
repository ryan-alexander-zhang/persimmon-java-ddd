package com.persimmon.ddd.outbox.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.persimmon.ddd.outbox.OutboxMessage;
import com.persimmon.ddd.outbox.OutboxStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The three tables behind the outbox. Column names are the SQL ones; see {@code persimmon/db/migration/outbox}. */
public class MybatisOutboxStore implements OutboxStore {
  private final OutboxMapper outbox;
  private final DeadLetterMapper deadLetters;
  private final InboxMapper inbox;

  public MybatisOutboxStore(OutboxMapper outbox, DeadLetterMapper deadLetters, InboxMapper inbox) {
    this.outbox = outbox;
    this.deadLetters = deadLetters;
    this.inbox = inbox;
  }

  @Override
  public void insert(OutboxMessage message, Instant createdAt, Instant dueAt) {
    OutboxRecord row = new OutboxRecord();
    copy(message, row);
    row.setSent(false);
    row.setAttempts(0);
    row.setCreatedAt(createdAt);
    row.setNextAttemptAt(dueAt);
    outbox.insert(row);
  }

  @Override
  public List<OutboxMessage> claim(
      Instant now, int maxAttempts, int batchSize, String token, Instant leaseUntil) {
    List<String> candidates = outbox.selectClaimable(maxAttempts, now, batchSize);
    if (candidates.isEmpty()) {
      return List.of();
    }
    outbox.update(
        null,
        new UpdateWrapper<OutboxRecord>()
            .in("event_id", candidates)
            .eq("sent", false)
            .and(w -> w.isNull("lease_until").or().le("lease_until", now))
            .set("lease_token", token)
            .set("lease_until", leaseUntil));
    return outbox
        .selectList(
            new QueryWrapper<OutboxRecord>()
                .eq("lease_token", token)
                .orderByAsc("created_at", "id"))
        .stream()
        .map(MybatisOutboxStore::toMessage)
        .toList();
  }

  @Override
  public void markSent(List<String> eventIds, Instant sentAt) {
    outbox.update(
        null,
        unleased(new UpdateWrapper<OutboxRecord>().in("event_id", eventIds))
            .set("sent", true)
            .set("sent_at", sentAt));
  }

  @Override
  public void scheduleRetry(String eventId, Instant nextAttemptAt) {
    outbox.update(
        null,
        unleased(new UpdateWrapper<OutboxRecord>().eq("event_id", eventId))
            .setSql("attempts = attempts + 1")
            .set("next_attempt_at", nextAttemptAt));
  }

  @Override
  @Transactional
  public void deadLetter(
      OutboxMessage message, int attempts, DeadLetterReason reason, String lastError, Instant failedAt) {
    DeadLetterRecord row = new DeadLetterRecord();
    copy(message, row);
    row.setAttempts(attempts);
    row.setReason(reason.name());
    row.setLastError(lastError);
    row.setFailedAt(failedAt);
    deadLetters.insert(row);
    outbox.delete(new QueryWrapper<OutboxRecord>().eq("event_id", message.eventId()));
  }

  @Override
  @Transactional
  public boolean replay(String eventId, Instant now) {
    DeadLetterRecord dead =
        deadLetters.selectOne(new QueryWrapper<DeadLetterRecord>().eq("event_id", eventId));
    if (dead == null) {
      return false;
    }
    insert(
        new OutboxMessage(
            dead.getEventId(), dead.getSource(), dead.getType(), dead.getVersion(), dead.getPayload(),
            dead.getOccurredAt(), dead.getSubject(), dead.getCorrelationId(), dead.getCausationId(),
            dead.getDestination(), dead.getTenantId(), dead.getTraceparent(), dead.getTracestate(), 0),
        now);
    deadLetters.deleteById(dead.getId());
    return true;
  }

  @Override
  public boolean discard(String eventId) {
    return deadLetters.delete(new QueryWrapper<DeadLetterRecord>().eq("event_id", eventId)) > 0;
  }

  @Override
  public List<DeadLetter> deadLetters(int limit) {
    return deadLetters
        .selectList(new QueryWrapper<DeadLetterRecord>().orderByDesc("failed_at", "id").last("LIMIT " + limit))
        .stream()
        .map(d -> new DeadLetter(
            d.getEventId(), d.getSource(), d.getType(), d.getVersion(), d.getSubject(), d.getCorrelationId(),
            d.getTenantId(), d.getAttempts(), d.getReason(), d.getLastError(), d.getFailedAt()))
        .toList();
  }

  @Override
  public long countPending() {
    return outbox.selectCount(new QueryWrapper<OutboxRecord>().eq("sent", false));
  }

  @Override
  public long countDeadLetters() {
    return deadLetters.selectCount(null);
  }

  @Override
  public Optional<Instant> oldestPendingCreatedAt() {
    return outbox
        .selectObjs(new QueryWrapper<OutboxRecord>().select("created_at").eq("sent", false).orderByAsc("created_at").last("LIMIT 1"))
        .stream()
        .findFirst()
        .map(MybatisOutboxStore::toInstant);
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof java.time.LocalDateTime local) {
      return local.toInstant(java.time.ZoneOffset.UTC);
    }
    if (value instanceof java.time.OffsetDateTime offset) {
      return offset.toInstant();
    }
    throw new IllegalStateException("unexpected created_at type " + value.getClass());
  }

  @Override
  public int deleteSentBefore(Instant sentBefore, int limit) {
    List<Object> ids =
        outbox.selectObjs(
            new QueryWrapper<OutboxRecord>()
                .select("id")
                .eq("sent", true)
                .lt("sent_at", sentBefore)
                .last("LIMIT " + limit));
    return ids.isEmpty() ? 0 : outbox.deleteByIds(ids);
  }

  /** Portable across vendors (no DELETE ... LIMIT): deletes up to the {@code limit}-th oldest timestamp, ties included. */
  @Override
  public int deleteInboxBefore(Instant processedBefore, int limit) {
    List<Object> oldest =
        inbox.selectObjs(
            new QueryWrapper<InboxRecord>()
                .select("processed_at")
                .lt("processed_at", processedBefore)
                .orderByAsc("processed_at")
                .last("LIMIT " + limit));
    if (oldest.isEmpty()) {
      return 0;
    }
    Object bound = oldest.get(oldest.size() - 1);
    return inbox.delete(new QueryWrapper<InboxRecord>().lt("processed_at", processedBefore).le("processed_at", bound));
  }

  @Override
  public boolean seenBefore(String consumer, String source, String messageKey, Instant now) {
    boolean seen =
        inbox.exists(
            new QueryWrapper<InboxRecord>()
                .eq("consumer", consumer)
                .eq("source", source)
                .eq("message_key", messageKey));
    if (!seen) {
      inbox.insert(new InboxRecord(consumer, source, messageKey, now));
    }
    return seen;
  }

  private static UpdateWrapper<OutboxRecord> unleased(UpdateWrapper<OutboxRecord> wrapper) {
    return wrapper.set("lease_token", null).set("lease_until", null);
  }

  private static void copy(OutboxMessage m, OutboxRecord row) {
    row.setEventId(m.eventId());
    row.setSource(m.source());
    row.setType(m.type());
    row.setVersion(m.version());
    row.setPayload(m.payload());
    row.setOccurredAt(m.occurredAt());
    row.setSubject(m.subject());
    row.setCorrelationId(m.correlationId());
    row.setCausationId(m.causationId());
    row.setDestination(m.destination());
    row.setTenantId(m.tenantId());
    row.setTraceparent(m.traceparent());
    row.setTracestate(m.tracestate());
  }

  private static void copy(OutboxMessage m, DeadLetterRecord row) {
    row.setEventId(m.eventId());
    row.setSource(m.source());
    row.setType(m.type());
    row.setVersion(m.version());
    row.setPayload(m.payload());
    row.setOccurredAt(m.occurredAt());
    row.setSubject(m.subject());
    row.setCorrelationId(m.correlationId());
    row.setCausationId(m.causationId());
    row.setDestination(m.destination());
    row.setTenantId(m.tenantId());
    row.setTraceparent(m.traceparent());
    row.setTracestate(m.tracestate());
  }

  private static OutboxMessage toMessage(OutboxRecord r) {
    return new OutboxMessage(
        r.getEventId(), r.getSource(), r.getType(), r.getVersion(), r.getPayload(), r.getOccurredAt(),
        r.getSubject(), r.getCorrelationId(), r.getCausationId(), r.getDestination(), r.getTenantId(),
        r.getTraceparent(), r.getTracestate(), r.getAttempts());
  }
}
