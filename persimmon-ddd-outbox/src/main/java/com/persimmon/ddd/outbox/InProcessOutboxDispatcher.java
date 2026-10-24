package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.context.event.GenericApplicationListenerAdapter;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.ClassUtils;

/**
 * Delivers to this process's listeners as an {@code EventEnvelope<T>}, one bounded context at a
 * time: each context's listeners run in their own transaction with their own inbox record, so a
 * context whose listener fails is retried alone and never rolls back or re-runs another's work,
 * exactly as if it were a service of its own. Listeners run under the tenant the row was written
 * for; registered upcasters move an older payload to the newest version first. A broker module
 * hands records it received to {@link #dispatch} too, so both legs deliver the same way. A
 * scheduled command row is sent on the command bus under the same guard, recorded for the
 * command's context, as a child of the context that scheduled it.
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {
  private final ApplicationContext application;
  private final ObjectMapper json;
  private final IntegrationEventCatalog catalog;
  private final OutboxStore store;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final ContextNames contexts;
  private final Upcasters upcasters;
  private final CommandBus commands;
  private final CommandCatalog commandCatalog;

  /** One context's listeners for one event type. */
  private record Delivery(String consumer, List<GenericApplicationListener> listeners) {}

  public InProcessOutboxDispatcher(
      ApplicationContext application,
      ObjectMapper json,
      IntegrationEventCatalog catalog,
      OutboxStore store,
      TransactionOperations transactions,
      Clock clock,
      ContextNames contexts,
      Upcasters upcasters,
      CommandBus commands,
      CommandCatalog commandCatalog) {
    this.application = application;
    this.json = json;
    this.catalog = catalog;
    this.store = store;
    this.transactions = transactions;
    this.clock = clock;
    this.contexts = contexts;
    this.upcasters = upcasters;
    this.commands = commands;
    this.commandCatalog = commandCatalog;
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
    if (ScheduledCommandWriter.SOURCE.equals(message.source())) {
      Class<? extends Command<?>> type =
          commandCatalog
              .lookup(message.type())
              .orElseThrow(() -> new PermanentDispatchException("no local command class for " + message.type()));
      Command<?> command;
      try {
        command = json.readValue(message.payload(), type);
      } catch (JsonProcessingException e) {
        throw new PermanentDispatchException("unreadable payload of " + message.eventId() + ": " + e);
      }
      CommandContext cause = new CommandContext(message.eventId(), message.correlationId(), message.causationId());
      onceUnderTenant(contexts.of(type.getName()), message, () -> commands.send(command, cause));
      return;
    }
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
    PayloadApplicationEvent<?> event = new PayloadApplicationEvent<>(this, envelope, envelopeType(delivered));
    for (Delivery delivery : deliveriesFor(delivered)) {
      onceUnderTenant(
          delivery.consumer(), message, () -> delivery.listeners().forEach(l -> l.onApplicationEvent(event)));
    }
  }

  /** In one transaction with the inbox check, under the row's tenant; a row the consumer saw before is skipped. */
  private void onceUnderTenant(String consumer, OutboxMessage message, Runnable delivery) {
    TenantContext.runAs(
        new TenantId(message.tenantId()),
        () ->
            transactions.executeWithoutResult(
                status -> {
                  if (!store.seenBefore(consumer, message.source(), message.eventId(), clock.instant())) {
                    delivery.run();
                  }
                }));
  }

  /** The listeners of {@code EventEnvelope<delivered>}, grouped by context in name order, ordered within one. Looked up per delivery, so a listener registered late is not missed. */
  private List<Delivery> deliveriesFor(Class<?> delivered) {
    ResolvableType eventType = ResolvableType.forClassWithGenerics(PayloadApplicationEvent.class, envelopeType(delivered));
    Map<String, List<GenericApplicationListener>> byConsumer = new TreeMap<>();
    for (ApplicationListener<?> listener : allListeners()) {
      GenericApplicationListener generic =
          listener instanceof GenericApplicationListener g ? g : new GenericApplicationListenerAdapter(listener);
      if (generic.supportsEventType(eventType) && generic.supportsSourceType(getClass())) {
        byConsumer.computeIfAbsent(contexts.of(ownerOf(listener)), k -> new ArrayList<>()).add(generic);
      }
    }
    byConsumer.values().forEach(AnnotationAwareOrderComparator::sort);
    return byConsumer.entrySet().stream().map(e -> new Delivery(e.getKey(), List.copyOf(e.getValue()))).toList();
  }

  /** {@code @EventListener} methods are registered on the context; {@code ApplicationListener} beans are not. */
  private Set<ApplicationListener<?>> allListeners() {
    Set<ApplicationListener<?>> all = new LinkedHashSet<>();
    if (application instanceof AbstractApplicationContext concrete) {
      all.addAll(concrete.getApplicationListeners());
    }
    application.getBeansOfType(ApplicationListener.class).values().forEach(all::add);
    return all;
  }

  /** The class that declares the listener: from the default listener id {@code pkg.Class.method(params)} or the bean class. */
  private static String ownerOf(ApplicationListener<?> listener) {
    if (listener instanceof ApplicationListenerMethodAdapter adapter) {
      String id = adapter.getListenerId();
      int params = id.indexOf('(');
      int method = params < 0 ? -1 : id.lastIndexOf('.', params);
      if (method > 0) {
        return id.substring(0, method);
      }
    }
    return ClassUtils.getUserClass(listener).getName();
  }

  private static ResolvableType envelopeType(Class<?> delivered) {
    return ResolvableType.forClassWithGenerics(EventEnvelope.class, delivered);
  }

}
