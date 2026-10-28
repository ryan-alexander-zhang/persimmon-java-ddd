package com.persimmon.ddd.rabbit;

import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * A row with a destination is published to that exchange with its event type as the routing key,
 * so a consumer binds its queue with {@code ordering.*}; a row without one stays in process. With
 * publisher confirms configured ({@code spring.rabbitmq.publisher-confirm-type=correlated}) the
 * send blocks until the broker confirms, so a nack or a slow broker is a relay failure with the
 * relay's retries.
 */
public class RabbitOutboxDispatcher implements OutboxDispatcher {
  private final RabbitTemplate rabbit;
  private final InProcessOutboxDispatcher local;
  private final Duration sendTimeout;

  public RabbitOutboxDispatcher(RabbitTemplate rabbit, InProcessOutboxDispatcher local, Duration sendTimeout) {
    this.rabbit = rabbit;
    this.local = local;
    this.sendTimeout = sendTimeout;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    if (message.destination() == null) {
      local.dispatch(message);
      return;
    }
    CorrelationData confirm = new CorrelationData(message.eventId());
    rabbit.send(message.destination(), message.type(), message(message), confirm);
    if (!rabbit.isConfirmListener()) {
      return;
    }
    try {
      CorrelationData.Confirm result = confirm.getFuture().get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!result.isAck()) {
        throw new IllegalStateException("broker rejected " + message.eventId() + ": " + result.getReason());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while publishing " + message.eventId(), e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("publishing " + message.eventId() + " failed", e.getCause());
    } catch (TimeoutException e) {
      throw new IllegalStateException("publishing " + message.eventId() + " took longer than " + sendTimeout, e);
    }
  }

  static Message message(OutboxMessage m) {
    MessageProperties p = new MessageProperties();
    p.setMessageId(m.eventId());
    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    p.setContentEncoding(StandardCharsets.UTF_8.name());
    p.setDeliveryMode(MessageProperties.DEFAULT_DELIVERY_MODE);
    if (m.occurredAt() != null) {
      p.setTimestamp(Date.from(m.occurredAt()));
    }
    header(p, Headers.ID, m.eventId());
    header(p, Headers.SOURCE, m.source());
    header(p, Headers.SPEC_VERSION, "1.0");
    header(p, Headers.TYPE, m.type());
    header(p, Headers.TIME, m.occurredAt() == null ? null : m.occurredAt().toString());
    header(p, Headers.SUBJECT, m.subject());
    header(p, Headers.DATA_SCHEMA_VERSION, Integer.toString(m.version()));
    header(p, Headers.TENANT_ID, m.tenantId());
    header(p, Headers.CORRELATION_ID, m.correlationId());
    header(p, Headers.CAUSATION_ID, m.causationId());
    header(p, Headers.TRACEPARENT, m.traceparent());
    header(p, Headers.TRACESTATE, m.tracestate());
    return new Message(m.payload().getBytes(StandardCharsets.UTF_8), p);
  }

  private static void header(MessageProperties p, String name, String value) {
    if (value != null) {
      p.setHeader(name, value);
    }
  }
}
