package com.example.app.ordering.application;

import com.example.app.ordering.domain.OrderId;
import com.example.app.ordering.domain.OrderStatus;
import com.example.app.ordering.domain.Orders;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Arrives at least once, possibly after the order was confirmed: it decides whether it still applies. */
@Component
class CancelUnconfirmedOrderHandler implements CommandHandler<CancelUnconfirmedOrder, Void> {
  private static final Logger log = LoggerFactory.getLogger(CancelUnconfirmedOrderHandler.class);

  private final Orders orders;

  CancelUnconfirmedOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(CancelUnconfirmedOrder command, CommandContext context) {
    orders
        .find(new OrderId(command.orderId()))
        .filter(order -> order.status() == OrderStatus.PLACED)
        .ifPresent(
            order -> {
              order.cancel();
              orders.save(order);
              log.info("order {} cancelled: not confirmed in time", command.orderId());
            });
    return null;
  }
}
