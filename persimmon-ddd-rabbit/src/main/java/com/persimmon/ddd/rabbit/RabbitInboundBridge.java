package com.persimmon.ddd.rabbit;

import com.persimmon.ddd.application.TenantId;
import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxMessage;
import com.persimmon.ddd.outbox.PermanentDispatchException;
import com.persimmon.ddd.outbox.TraceContextCarrier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Consumes the configured queue and hands each message to the in-process dispatcher as if it were
 * an outbox row, so the same {@code @EventListener}s run with the same inbox dedup, tenant and
 * trace. A type this process has no class for is skipped: a queue may be bound to more than one
 * context's events. A malformed message is permanent and goes to the dead-letter queue.
 */
public class RabbitInboundBridge {
  private static final Logger log = LoggerFactory.getLogger(RabbitInboundBridge.class);

  private final InProcessOutboxDispatcher local;
  private final TraceContextCarrier traces;

  public RabbitInboundBridge(InProcessOutboxDispatcher local, TraceContextCarrier traces) {
    this.local = local;
    this.traces = traces;
  }

  @RabbitListener(
      queues = "${persimmon.ddd.rabbit.consumer.queue:${spring.application.name:persimmon}}",
      containerFactory = RabbitAutoConfiguration.CONTAINER_FACTORY)
  public void onMessage(Message message) {
    String type = require(message, Headers.TYPE);
    int version = version(message);
    if (!local.knows(type, version)) {
      log.debug("skipping {} v{} from {}: no class for it here", type, version, message.getMessageProperties().getConsumerQueue());
      return;
    }
    OutboxMessage row =
        new OutboxMessage(
            require(message, Headers.ID),
            require(message, Headers.SOURCE),
            type,
            version,
            new String(message.getBody(), StandardCharsets.UTF_8),
            occurredAt(message),
            header(message, Headers.SUBJECT),
            orElse(header(message, Headers.CORRELATION_ID), require(message, Headers.ID)),
            header(message, Headers.CAUSATION_ID),
            null,
            orElse(header(message, Headers.TENANT_ID), TenantId.ROOT.value()),
            header(message, Headers.TRACEPARENT),
            header(message, Headers.TRACESTATE),
            0);
    traces.restore(
        row.traceparent(),
        row.tracestate(),
        "rabbit.consume " + type,
        () -> {
          local.dispatch(row);
          return null;
        });
  }

  private static int version(Message message) {
    String raw = require(message, Headers.DATA_SCHEMA_VERSION);
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

  private static Instant occurredAt(Message message) {
    String raw = header(message, Headers.TIME);
    if (raw == null || raw.isBlank()) {
      Date stamped = message.getMessageProperties().getTimestamp();
      return stamped == null ? Instant.now() : stamped.toInstant();
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new PermanentDispatchException(Headers.TIME + " is not an ISO-8601 instant: '" + raw + "'");
    }
  }

  private static String require(Message message, String name) {
    String value = header(message, name);
    if (value == null || value.isBlank()) {
      throw new PermanentDispatchException(
          "message " + message.getMessageProperties().getMessageId() + " on " + message.getMessageProperties().getConsumerQueue() + " has no " + name + " header");
    }
    return value;
  }

  private static String header(Message message, String name) {
    Object value = message.getMessageProperties().getHeader(name);
    return value == null ? null : value.toString();
  }

  private static String orElse(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
