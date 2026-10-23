package com.persimmon.ddd.kafka;

import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A row with a destination goes to that topic, keyed by subject so one subject stays on one
 * partition; a row without one stays in process. Blocks until the broker acknowledges, so a failed
 * or slow send is a relay failure with the relay's retries.
 */
public class KafkaOutboxDispatcher implements OutboxDispatcher {
  private final KafkaTemplate<String, String> kafka;
  private final InProcessOutboxDispatcher local;
  private final Duration sendTimeout;

  public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafka, InProcessOutboxDispatcher local, Duration sendTimeout) {
    this.kafka = kafka;
    this.local = local;
    this.sendTimeout = sendTimeout;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    if (message.destination() == null) {
      local.dispatch(message);
      return;
    }
    try {
      kafka.send(record(message)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing " + message.eventId(), e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("publishing " + message.eventId() + " failed", e.getCause());
    } catch (TimeoutException e) {
      throw new IllegalStateException("publishing " + message.eventId() + " took longer than " + sendTimeout, e);
    }
  }

  static ProducerRecord<String, String> record(OutboxMessage m) {
    String key = m.subject() == null || m.subject().isBlank() ? m.eventId() : m.subject();
    ProducerRecord<String, String> record = new ProducerRecord<>(m.destination(), key, m.payload());
    header(record, Headers.ID, m.eventId());
    header(record, Headers.SOURCE, m.source());
    header(record, Headers.SPEC_VERSION, "1.0");
    header(record, Headers.TYPE, m.type());
    header(record, Headers.TIME, m.occurredAt() == null ? null : m.occurredAt().toString());
    header(record, Headers.SUBJECT, m.subject());
    header(record, Headers.DATA_SCHEMA_VERSION, Integer.toString(m.version()));
    header(record, Headers.TENANT_ID, m.tenantId());
    header(record, Headers.CORRELATION_ID, m.correlationId());
    header(record, Headers.CAUSATION_ID, m.causationId());
    header(record, Headers.TRACEPARENT, m.traceparent());
    header(record, Headers.TRACESTATE, m.tracestate());
    header(record, Headers.CONTENT_TYPE, "application/json");
    return record;
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    if (value != null) {
      record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
