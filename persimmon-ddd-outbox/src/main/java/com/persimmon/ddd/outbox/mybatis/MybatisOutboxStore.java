package com.persimmon.ddd.outbox.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.persimmon.ddd.outbox.OutboxMessage;
import com.persimmon.ddd.outbox.OutboxStore;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** The three tables behind the outbox. Column names are the SQL ones; see {@code persimmon/db/outbox}. */
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
  public void insert(OutboxMessage message, Instant createdAt) {
    OutboxRecord row = new OutboxRecord();
    copy(message, row);
    row.setSent(false);
    row.setAttempts(0);
    row.setCreatedAt(createdAt);
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
            dead.getDestination(), 0),
        now);
    deadLetters.deleteById(dead.getId());
    return true;
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
  }

  private static OutboxMessage toMessage(OutboxRecord r) {
    return new OutboxMessage(
        r.getEventId(), r.getSource(), r.getType(), r.getVersion(), r.getPayload(), r.getOccurredAt(),
        r.getSubject(), r.getCorrelationId(), r.getCausationId(), r.getDestination(), r.getAttempts());
  }
}
