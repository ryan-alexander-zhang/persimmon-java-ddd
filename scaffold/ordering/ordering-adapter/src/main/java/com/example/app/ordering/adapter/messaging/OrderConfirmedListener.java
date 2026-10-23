package com.example.app.ordering.adapter.messaging;

import com.example.app.ordering.api.OrderConfirmedEvent;
import com.persimmon.ddd.application.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Receives the integration event the outbox relays, at least once. Another context would hold
 * a listener like this; here it only logs. Throw to have the relay retry.
 */
@Component
class OrderConfirmedListener {
  private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);

  @EventListener
  void on(EventEnvelope<OrderConfirmedEvent> envelope) {
    log.info("order {} confirmed (event {})", envelope.payload().orderId(), envelope.eventId());
  }
}
