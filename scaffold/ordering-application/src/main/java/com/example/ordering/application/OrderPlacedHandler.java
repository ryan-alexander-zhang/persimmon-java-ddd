package com.example.ordering.application;

import com.example.ordering.domain.OrderPlaced;
import com.persimmon.ddd.application.DomainEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reacts in the same transaction as the command that raised the event. */
@Component
@DomainEventHandler
class OrderPlacedHandler {
  private static final Logger log = LoggerFactory.getLogger(OrderPlacedHandler.class);

  @EventListener
  void on(OrderPlaced event) {
    log.info("order {} placed by {}", event.orderId().value(), event.customerId());
  }
}
