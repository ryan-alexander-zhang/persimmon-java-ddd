package com.persimmon.ddd.archunit.good.ordering.application;

import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.archunit.good.billing.api.InvoiceRequested;
import com.persimmon.ddd.archunit.good.ordering.domain.Order;
import com.persimmon.ddd.archunit.good.ordering.domain.OrderId;
import com.persimmon.ddd.archunit.good.ordering.domain.Orders;
import java.time.Clock;

public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {
  private final Orders orders;
  private final IntegrationEvents events;
  private final Clock clock;

  public PlaceOrderHandler(Orders orders, IntegrationEvents events, Clock clock) {
    this.orders = orders;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    Order order = new Order(new OrderId(command.orderId()), command.customerId());
    order.place(command.lines(), clock.instant());
    orders.save(order);
    events.publish(new InvoiceRequested(command.orderId()), context);
    return command.orderId();
  }
}
