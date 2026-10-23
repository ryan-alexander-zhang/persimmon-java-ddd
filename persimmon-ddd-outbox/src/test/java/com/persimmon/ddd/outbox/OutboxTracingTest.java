package com.persimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureObservability
class OutboxTracingTest {
  @EventType(name = "ordering.Traced", version = 1)
  record Traced(String id) implements IntegrationEvent {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    String listenerTraceId;

    @Bean
    InMemorySpanExporter spans() {
      return InMemorySpanExporter.create();
    }

    @Bean
    Object probe(Tracer tracer) {
      return new Object() {
        @EventListener
        void on(EventEnvelope<Traced> envelope) {
          listenerTraceId = tracer.currentSpan().context().traceId();
        }
      };
    }
  }

  @Autowired App app;
  @Autowired Tracer tracer;
  @Autowired InMemorySpanExporter spans;
  @Autowired SdkTracerProvider provider;
  @Autowired IntegrationEvents events;
  @Autowired OutboxRelay relay;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @Test
  void theRelayedDispatchIsAChildOfTheSpanThatWroteTheRow() {
    jdbc.update("DELETE FROM persimmon_outbox");
    Span request = tracer.nextSpan().name("POST /orders").start();
    try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
      tx.executeWithoutResult(s -> events.publish(new Traced("t-1"), CommandContext.root("c-1")));
    } finally {
      request.end();
    }
    Map<String, Object> row = jdbc.queryForMap("SELECT traceparent FROM persimmon_outbox");
    assertNotNull(row.get("TRACEPARENT"));

    relay.relay();
    provider.forceFlush().join(5, TimeUnit.SECONDS);
    String traceId = request.context().traceId();
    assertEquals(traceId, app.listenerTraceId, "the listener runs inside the original trace");
    SpanData dispatch =
        spans.getFinishedSpanItems().stream().filter(s -> s.getName().startsWith("outbox.dispatch")).findFirst().orElseThrow();
    assertEquals(traceId, dispatch.getTraceId());
    assertEquals(request.context().spanId(), dispatch.getParentSpanId());
    assertEquals(List.of("outbox.dispatch ordering.Traced"), List.of(dispatch.getName()));
  }
}
