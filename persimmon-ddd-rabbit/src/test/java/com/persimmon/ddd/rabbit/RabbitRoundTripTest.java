package com.persimmon.ddd.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import com.persimmon.ddd.outbox.EventUpcaster;
import com.persimmon.ddd.outbox.OutboxMessage;
import com.persimmon.ddd.outbox.OutboxRelay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RabbitRoundTripTest {
  @Container @ServiceConnection
  static final RabbitMQContainer broker = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-alpine"));

  @EventType(context = "ordering", name = "OrderConfirmed", version = 1)
  record OrderConfirmedV1(String orderId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  @EventType(context = "ordering", name = "OrderConfirmed", version = 2)
  record OrderConfirmed(String orderId, String customerId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    final List<EventEnvelope<OrderConfirmed>> received = new CopyOnWriteArrayList<>();
    final List<String> tenants = new CopyOnWriteArrayList<>();

    @Bean
    EventUpcaster<OrderConfirmedV1, OrderConfirmed> v1ToV2() {
      return v1 -> new OrderConfirmed(v1.orderId(), "unknown");
    }

    /** A second queue on the exchange, to see what the relay published. */
    @Bean
    Declarables probe() {
      Queue probe = new Queue("probe");
      return new Declarables(probe, BindingBuilder.bind(probe).to(new TopicExchange("ordering.events", true, false)).with("#"));
    }

    @EventListener
    void on(EventEnvelope<OrderConfirmed> envelope) {
      received.add(envelope);
      tenants.add(TenantContext.effective().value());
    }
  }

  @Autowired App app;
  @Autowired IntegrationEvents events;
  @Autowired OutboxRelay relay;
  @Autowired TransactionTemplate tx;
  @Autowired RabbitTemplate rabbit;

  private static String header(Message m, String name) {
    Object value = m.getMessageProperties().getHeader(name);
    return String.valueOf(value);
  }

  private static Message message(String body, Map<String, String> headers) {
    MessageProperties p = new MessageProperties();
    headers.forEach(p::setHeader);
    return new Message(body.getBytes(StandardCharsets.UTF_8), p);
  }

  @Test
  void aRoutedEventTravelsThroughRabbitAndBackIntoTheSameListenerOnce() throws Exception {
    int before = app.received.size();
    TenantContext.runAs(TenantId.of("acme"), () -> tx.executeWithoutResult(s -> events.publish(new OrderConfirmed("o-1", "c-1"), CommandContext.root("cmd-1"))));
    assertEquals(1, relay.relay());

    Message published = rabbit.receive("probe", 10_000);
    assertNotNull(published, "the routed event reached the exchange");
    assertEquals("ordering.OrderConfirmed", published.getMessageProperties().getReceivedRoutingKey());
    assertEquals("ordering.OrderConfirmed", header(published, Headers.TYPE));
    assertEquals("ordering", header(published, Headers.SOURCE));
    assertEquals("2", header(published, Headers.DATA_SCHEMA_VERSION));
    assertEquals("acme", header(published, Headers.TENANT_ID));
    assertEquals("cmd-1", header(published, Headers.CORRELATION_ID));
    assertEquals("o-1", header(published, Headers.SUBJECT));
    assertEquals(MessageProperties.CONTENT_TYPE_JSON, published.getMessageProperties().getContentType());

    awaitReceived(before + 1);
    EventEnvelope<OrderConfirmed> envelope = app.received.stream().filter(e -> "cmd-1".equals(e.correlationId())).findFirst().orElseThrow();
    assertEquals(new OrderConfirmed("o-1", "c-1"), envelope.payload());
    assertEquals("ordering", envelope.source());
    assertEquals("acme", envelope.tenantId());
    assertEquals("acme", app.tenants.get(app.received.indexOf(envelope)), "the listener ran under the message's tenant");

    // the same message again (a redelivery): the inbox has it
    rabbit.send("ordering.events", "ordering.OrderConfirmed", RabbitOutboxDispatcher.message(new OutboxMessage(
        envelope.eventId(), "ordering", "ordering.OrderConfirmed", 2, "{\"orderId\":\"o-1\",\"customerId\":\"c-1\"}",
        envelope.occurredAt(), "o-1", "cmd-1", "cmd-1", "ordering.events", "acme", null, null, 0)));
    Thread.sleep(1500);
    assertEquals(before + 1, app.received.size(), "a redelivered message is not delivered twice");
  }

  @Test
  void anOlderVersionIsUpcastAndAnUnknownTypeIsSkippedAndAMalformedMessageIsDeadLettered() throws Exception {
    int before = app.received.size();
    rabbit.send("ordering.events", "ordering.OrderConfirmed", message("{\"orderId\":\"o-9\"}",
        Map.of(Headers.ID, "evt-v1", Headers.SOURCE, "ordering", Headers.TYPE, "ordering.OrderConfirmed", Headers.DATA_SCHEMA_VERSION, "1", Headers.SPEC_VERSION, "1.0")));
    awaitReceived(before + 1);
    EventEnvelope<OrderConfirmed> envelope = app.received.get(before);
    assertEquals(new OrderConfirmed("o-9", "unknown"), envelope.payload());
    assertEquals(2, envelope.version(), "the envelope says what was delivered");
    assertEquals(TenantId.ROOT.value(), envelope.tenantId());

    rabbit.send("ordering.events", "ordering.Nope", message("{}",
        Map.of(Headers.ID, "evt-u", Headers.SOURCE, "billing", Headers.TYPE, "billing.Nope", Headers.DATA_SCHEMA_VERSION, "1")));
    rabbit.send("ordering.events", "ordering.OrderConfirmed", message("{not json",
        Map.of(Headers.ID, "evt-m", Headers.SOURCE, "ordering", Headers.TYPE, "ordering.OrderConfirmed", Headers.DATA_SCHEMA_VERSION, "2")));
    Message dead = rabbit.receive("inventory.dlq", 15_000);
    assertNotNull(dead, "the malformed message is dead-lettered");
    assertEquals("evt-m", header(dead, Headers.ID));
    assertNull(rabbit.receive("inventory.dlq", 1_000), "the unknown type is skipped, not dead-lettered");
    assertEquals(before + 1, app.received.size());
  }

  private void awaitReceived(int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (app.received.size() < n && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(app.received.size() >= n, "expected " + n + " deliveries, got " + app.received.size());
  }
}
