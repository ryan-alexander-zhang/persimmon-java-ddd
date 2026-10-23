package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.core.IdGenerator;
import java.time.Clock;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the event as an outbox row inside the command's transaction. */
public class OutboxWriter implements IntegrationEvents {
  private final OutboxStore store;
  private final ObjectMapper json;
  private final IdGenerator ids;
  private final Clock clock;
  private final String source;

  public OutboxWriter(OutboxStore store, ObjectMapper json, IdGenerator ids, Clock clock, String source) {
    this.store = store;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
    this.source = source;
  }

  @Override
  public void publish(IntegrationEvent event, CommandContext context) {
    EventType type = IntegrationEvent.eventTypeOf(event.getClass());
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "no active transaction while writing " + type.name() + " to the outbox; the row must"
              + " commit with the change that caused it");
    }
    String payload;
    try {
      payload = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + event.getClass().getName(), e);
    }
    store.insert(
        new OutboxMessage(
            ids.newId(),
            source,
            type.name(),
            type.version(),
            payload,
            clock.instant(),
            event.subject(),
            context.correlationId(),
            context.messageId(),
            IntegrationEvent.destinationOf(event.getClass()).orElse(null),
            0),
        clock.instant());
  }
}
