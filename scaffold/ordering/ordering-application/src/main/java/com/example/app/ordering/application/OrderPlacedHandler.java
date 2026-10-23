package com.example.app.ordering.application;

import com.example.app.ordering.domain.OrderPlaced;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandScheduler;
import com.persimmon.ddd.application.DomainEventHandler;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts in the same transaction as the command that raised the event. Here it starts the
 * confirmation saga: the deadline commits with the order, and fires whether or not this process
 * is still alive by then.
 */
@Component
@DomainEventHandler
class OrderPlacedHandler {
  private final CommandScheduler scheduler;
  private final Clock clock;
  private final Duration confirmationTimeout;

  OrderPlacedHandler(
      CommandScheduler scheduler,
      Clock clock,
      @Value("${ordering.confirmation-timeout:30m}") Duration confirmationTimeout) {
    this.scheduler = scheduler;
    this.clock = clock;
    this.confirmationTimeout = confirmationTimeout;
  }

  @EventListener
  void on(OrderPlaced event) {
    scheduler.schedule(
        new CancelUnconfirmedOrder(event.orderId().value()),
        clock.instant().plus(confirmationTimeout),
        CommandContext.current().orElseThrow());
  }
}
