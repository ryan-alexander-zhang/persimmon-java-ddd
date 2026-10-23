package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.core.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

/** Domain events are Spring application events; listen with {@code @EventListener}. */
public class SpringDomainEvents implements DomainEvents {
  private final ApplicationEventPublisher publisher;

  public SpringDomainEvents(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void publish(DomainEvent event) {
    publisher.publishEvent(event);
  }
}
