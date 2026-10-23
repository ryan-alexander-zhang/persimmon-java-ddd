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
 * no inbox record behind. Listeners run under the tenant the row was written for.
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {
  private final ApplicationEventPublisher publisher;
  private final ObjectMapper json;
  private final IntegrationEventCatalog catalog;
  private final OutboxStore store;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final String consumer;

  public InProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectMapper json,
      IntegrationEventCatalog catalog,
      OutboxStore store,
      TransactionOperations transactions,
      Clock clock,
      String consumer) {
    this.publisher = publisher;
    this.json = json;
    this.catalog = catalog;
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
    this.consumer = consumer;
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
      payload = json.readValue(message.payload(), type);
    } catch (JsonProcessingException e) {
      throw new PermanentDispatchException("unreadable payload of " + message.eventId() + ": " + e);
    }
    EventEnvelope<IntegrationEvent> envelope =
        new EventEnvelope<>(
            message.eventId(),
            message.source(),
            message.type(),
            message.version(),
            message.occurredAt(),
            message.subject(),
            message.tenantId(),
            message.correlationId(),
            message.causationId(),
            payload);
    ResolvableType eventType = ResolvableType.forClassWithGenerics(EventEnvelope.class, type);
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
