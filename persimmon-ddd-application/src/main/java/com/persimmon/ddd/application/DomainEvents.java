package com.persimmon.ddd.application;

import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.DomainEvent;
import java.util.Collection;

/** Publishes domain events in-process; the infrastructure decides when listeners run. */
public interface DomainEvents {
  void publish(DomainEvent event);

  default void publishAll(Collection<? extends DomainEvent> events) {
    events.forEach(this::publish);
  }

  /**
   * Drains and publishes an already persisted aggregate's events. An event a listener records on
   * the same aggregate during publication announces state that was never written, so it fails.
   */
  default void publishAndClear(AbstractAggregateRoot<?> aggregate) {
    publishAll(aggregate.drainDomainEvents());
    if (!aggregate.domainEvents().isEmpty()) {
      throw new IllegalStateException(
          aggregate.getClass().getSimpleName()
              + " recorded further domain events while its events were being published, after it"
              + " was persisted; make that change before saving or in a separate use case");
    }
  }
}
