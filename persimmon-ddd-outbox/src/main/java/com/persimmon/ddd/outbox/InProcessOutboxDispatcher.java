package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delivers to this process's {@code @EventListener}s as an {@code EventEnvelope<T>}, inside one
 * transaction with the inbox check, so a redelivered row is skipped and a failed delivery leaves
 * no inbox record behind. Listeners run under the tenant the row was written for; registered
 * upcasters move an older payload to the newest version first. A broker module hands records it
 * received to {@link #dispatch} too, so both legs deliver the same way.
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {
  private final ApplicationEventPublisher publisher;
  private final ObjectMapper json;
  private final IntegrationEventCatalog catalog;
  private final OutboxStore store;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final String consumer;
  private final Upcasters upcasters;

  public InProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectMapper json,
      IntegrationEventCatalog catalog,
      OutboxStore store,
      TransactionOperations transactions,
      Clock clock,
      String consumer,
      Upcasters upcasters) {
    this.publisher = publisher;
    this.json = json;
    this.catalog = catalog;
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
    this.consumer = consumer;
    this.upcasters = upcasters;
  }

  /** Whether this process has a class for the contract; a broker consumer skips what it has none for. */
  public boolean knows(String type, int version) {
    return catalog.lookup(type, version).isPresent();
  }

  @Override
  public boolean reachesExternalTargets() {
    return false;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    Class<? extends IntegrationEvent> type =
        catalog
            .lookup(message.type(), message.version())
            .orElseThrow(
                () ->
                    new PermanentDispatchException(
                        "no local class for " + message.type() + " v" + message.version()));
    IntegrationEvent payload;
    try {
      payload = upcasters.apply(json.readValue(message.payload(), type));
    } catch (JsonProcessingException e) {
      throw new PermanentDispatchException("unreadable payload of " + message.eventId() + ": " + e);
    }
    Class<? extends IntegrationEvent> delivered = payload.getClass();
    EventEnvelope<IntegrationEvent> envelope =
        new EventEnvelope<>(
            message.eventId(),
            message.source(),
            message.type(),
            delivered == type ? message.version() : IntegrationEvent.contractOf(delivered).version(),
            message.occurredAt(),
            message.subject(),
            message.tenantId(),
            message.correlationId(),
            message.causationId(),
            payload);
    ResolvableType eventType = ResolvableType.forClassWithGenerics(EventEnvelope.class, delivered);
    TenantContext.runAs(
        new TenantId(message.tenantId()),
        () ->
            transactions.executeWithoutResult(
                status -> {
                  if (!store.seenBefore(consumer, message.source(), message.eventId(), clock.instant())) {
                    publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, eventType));
                  }
                }));
  }
}
