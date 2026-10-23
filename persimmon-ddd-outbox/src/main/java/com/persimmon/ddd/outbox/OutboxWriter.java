package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.core.IdGenerator;
import java.time.Clock;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the event as an outbox row inside the command's transaction; the source is the event's context, the destination comes from the routes. */
public class OutboxWriter implements IntegrationEvents {
  private final OutboxStore store;
  private final ObjectMapper json;
  private final IdGenerator ids;
  private final Clock clock;
  private final OutboxProperties properties;
  private final TraceContextCarrier traces;

  public OutboxWriter(
      OutboxStore store, ObjectMapper json, IdGenerator ids, Clock clock, OutboxProperties properties, TraceContextCarrier traces) {
    this.store = store;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.traces = traces;
  }

  @Override
  public void publish(IntegrationEvent event, CommandContext context) {
    IntegrationEvent.Contract contract = IntegrationEvent.contractOf(event.getClass());
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "no active transaction while writing " + contract.type() + " to the outbox; the row must"
              + " commit with the change that caused it");
    }
    String payload;
    try {
      payload = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + event.getClass().getName(), e);
    }
    TraceContextCarrier.Captured trace = traces.capture();
    store.insert(
        new OutboxMessage(
            ids.newId(),
            contract.context(),
            contract.type(),
            contract.version(),
            payload,
            clock.instant(),
            event.subject(),
            context.correlationId(),
            context.messageId(),
            properties.destinationFor(contract.type()),
            TenantContext.effective().value(),
            trace.traceparent(),
            trace.tracestate(),
            0),
        clock.instant());
  }
}
