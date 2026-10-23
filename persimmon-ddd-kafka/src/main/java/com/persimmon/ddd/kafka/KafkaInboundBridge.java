package com.persimmon.ddd.kafka;

import com.persimmon.ddd.application.TenantId;
import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxMessage;
import com.persimmon.ddd.outbox.PermanentDispatchException;
import com.persimmon.ddd.outbox.TraceContextCarrier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes the configured topics and hands each record to the in-process dispatcher as if it were
 * an outbox row, so the same {@code @EventListener}s run with the same inbox dedup, tenant and
 * trace. A type this process has no class for is skipped: a topic may carry more than one
 * context's events. A malformed record is permanent and goes to the dead-letter topic.
 */
public class KafkaInboundBridge {
  private static final Logger log = LoggerFactory.getLogger(KafkaInboundBridge.class);

  private final InProcessOutboxDispatcher local;
  private final TraceContextCarrier traces;

  public KafkaInboundBridge(InProcessOutboxDispatcher local, TraceContextCarrier traces) {
    this.local = local;
    this.traces = traces;
  }

  @KafkaListener(
      topics = "#{'${persimmon.ddd.kafka.consumer.topics}'.split(',')}",
      groupId = "${persimmon.ddd.kafka.consumer.group-id:${spring.application.name:persimmon}}")
  public void onRecord(ConsumerRecord<String, String> record) {
    String type = require(record, Headers.TYPE);
    int version = version(record);
    if (!local.knows(type, version)) {
      log.debug("skipping {} v{} from {}: no class for it here", type, version, record.topic());
      return;
    }
    OutboxMessage message =
        new OutboxMessage(
            require(record, Headers.ID),
            require(record, Headers.SOURCE),
            type,
            version,
            record.value(),
            occurredAt(record),
            header(record, Headers.SUBJECT),
            orElse(header(record, Headers.CORRELATION_ID), require(record, Headers.ID)),
            header(record, Headers.CAUSATION_ID),
            null,
            orElse(header(record, Headers.TENANT_ID), TenantId.ROOT.value()),
            header(record, Headers.TRACEPARENT),
            header(record, Headers.TRACESTATE),
            0);
    traces.restore(
        message.traceparent(),
        message.tracestate(),
        "kafka.consume " + type,
        () -> {
          local.dispatch(message);
          return null;
        });
  }

  private static int version(ConsumerRecord<String, String> record) {
    String raw = require(record, Headers.DATA_SCHEMA_VERSION);
    try {
      int version = Integer.parseInt(raw.trim());
      if (version < 1) {
        throw new NumberFormatException();
      }
      return version;
    } catch (NumberFormatException e) {
      throw new PermanentDispatchException(Headers.DATA_SCHEMA_VERSION + " must be a positive integer, was '" + raw + "'");
    }
  }

  private static Instant occurredAt(ConsumerRecord<String, String> record) {
    String raw = header(record, Headers.TIME);
    if (raw == null || raw.isBlank()) {
      return Instant.ofEpochMilli(record.timestamp());
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new PermanentDispatchException(Headers.TIME + " is not an ISO-8601 instant: '" + raw + "'");
    }
  }

  private static String require(ConsumerRecord<String, String> record, String name) {
    String value = header(record, name);
    if (value == null || value.isBlank()) {
      throw new PermanentDispatchException("record " + record.topic() + "-" + record.partition() + "@" + record.offset() + " has no " + name + " header");
    }
    return value;
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static String orElse(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
