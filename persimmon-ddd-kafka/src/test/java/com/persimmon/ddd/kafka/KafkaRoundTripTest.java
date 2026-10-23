package com.persimmon.ddd.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import com.persimmon.ddd.outbox.EventUpcaster;
import com.persimmon.ddd.outbox.OutboxRelay;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"ordering.events", "ordering.events.DLT"})
class KafkaRoundTripTest {
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
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired EmbeddedKafkaBroker broker;

  private Consumer<String, String> rawConsumer(String group, String topic) {
    Map<String, Object> props = KafkaTestUtils.consumerProps(group, "true", broker);
    Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
    broker.consumeFromAnEmbeddedTopic(consumer, topic);
    return consumer;
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  @Test
  void aRoutedEventTravelsThroughKafkaAndBackIntoTheSameListenerOnce() throws Exception {
    Consumer<String, String> raw = rawConsumer("probe", "ordering.events");
    int before = app.received.size();
    TenantContext.runAs(TenantId.of("acme"), () -> tx.executeWithoutResult(s -> events.publish(new OrderConfirmed("o-1", "c-1"), CommandContext.root("cmd-1"))));
    assertEquals(1, relay.relay());

    ConsumerRecord<String, String> record = null;
    for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(raw, Duration.ofSeconds(10))) {
      if (r.headers().lastHeader(Headers.CORRELATION_ID) != null && "cmd-1".equals(header(r, Headers.CORRELATION_ID))) {
        record = r;
      }
    }
    assertTrue(record != null, "the routed event reached the topic");
    assertEquals("o-1", record.key());
    assertEquals("ordering.OrderConfirmed", header(record, Headers.TYPE));
    assertEquals("ordering", header(record, Headers.SOURCE));
    assertEquals("2", header(record, Headers.DATA_SCHEMA_VERSION));
    assertEquals("acme", header(record, Headers.TENANT_ID));
    assertEquals("cmd-1", header(record, Headers.CORRELATION_ID));
    raw.close();

    awaitReceived(before + 1);
    EventEnvelope<OrderConfirmed> envelope = app.received.stream().filter(e -> "cmd-1".equals(e.correlationId())).findFirst().orElseThrow();
    assertEquals(new OrderConfirmed("o-1", "c-1"), envelope.payload());
    assertEquals("ordering", envelope.source());
    assertEquals("acme", envelope.tenantId());
    assertEquals("acme", app.tenants.get(app.received.indexOf(envelope)), "the listener ran under the record's tenant");

    // the same record again (a redelivery): the inbox has it
    kafka.send(KafkaOutboxDispatcher.record(new com.persimmon.ddd.outbox.OutboxMessage(
        envelope.eventId(), "ordering", "ordering.OrderConfirmed", 2, "{\"orderId\":\"o-1\",\"customerId\":\"c-1\"}",
        envelope.occurredAt(), "o-1", "cmd-1", "cmd-1", "ordering.events", "acme", null, null, 0))).get();
    Thread.sleep(1500);
    assertEquals(before + 1, app.received.size(), "a redelivered record is not delivered twice");
  }

  @Test
  void anOlderVersionIsUpcastAndAnUnknownTypeIsSkippedAndAMalformedRecordIsDeadLettered() throws Exception {
    int before = app.received.size();
    ProducerRecord<String, String> v1 = new ProducerRecord<>("ordering.events", "o-9", "{\"orderId\":\"o-9\"}");
    for (var h : Map.of(Headers.ID, "evt-v1", Headers.SOURCE, "ordering", Headers.TYPE, "ordering.OrderConfirmed", Headers.DATA_SCHEMA_VERSION, "1", Headers.SPEC_VERSION, "1.0").entrySet()) {
      v1.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
    }
    kafka.send(v1).get();
    awaitReceived(before + 1);
    EventEnvelope<OrderConfirmed> envelope = app.received.get(before);
    assertEquals(new OrderConfirmed("o-9", "unknown"), envelope.payload());
    assertEquals(2, envelope.version(), "the envelope says what was delivered");
    assertEquals(TenantId.ROOT.value(), envelope.tenantId());

    Consumer<String, String> dlt = rawConsumer("dlt-probe", "ordering.events.DLT");
    ProducerRecord<String, String> unknown = new ProducerRecord<>("ordering.events", "x", "{}");
    for (var h : Map.of(Headers.ID, "evt-u", Headers.SOURCE, "billing", Headers.TYPE, "billing.Nope", Headers.DATA_SCHEMA_VERSION, "1").entrySet()) {
      unknown.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
    }
    ProducerRecord<String, String> malformed = new ProducerRecord<>("ordering.events", "o-10", "{not json");
    for (var h : Map.of(Headers.ID, "evt-m", Headers.SOURCE, "ordering", Headers.TYPE, "ordering.OrderConfirmed", Headers.DATA_SCHEMA_VERSION, "2").entrySet()) {
      malformed.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
    }
    kafka.send(unknown).get();
    kafka.send(malformed).get();
    ConsumerRecords<String, String> dead = KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(15));
    List<String> deadIds = new java.util.ArrayList<>();
    dead.forEach(r -> deadIds.add(header(r, Headers.ID)));
    assertEquals(List.of("evt-m"), deadIds, "only the malformed record is dead-lettered; the unknown type is skipped");
    assertEquals(before + 1, app.received.size());
    dlt.close();
  }

  private void awaitReceived(int n) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (app.received.size() < n && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(app.received.size() >= n, "expected " + n + " deliveries, got " + app.received.size());
  }
}
