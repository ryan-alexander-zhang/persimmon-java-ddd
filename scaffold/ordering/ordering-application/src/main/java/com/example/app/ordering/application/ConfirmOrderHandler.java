package com.example.app.ordering.application;

import com.example.app.ordering.api.OrderConfirmedEvent;
import com.example.app.ordering.domain.Order;
import com.example.app.ordering.domain.OrderId;
import com.example.app.ordering.domain.OrderingErrorCode;
import com.example.app.ordering.domain.Orders;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.EntityNotFoundException;
import com.persimmon.ddd.application.IntegrationEvents;
import org.springframework.stereotype.Component;

@Component
class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {
  private final Orders orders;
  private final IntegrationEvents events;

  ConfirmOrderHandler(Orders orders, IntegrationEvents events) {
    this.orders = orders;
    this.events = events;
  }

  @Override
  public Void handle(ConfirmOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "no order " + id.value()));
    order.confirm();
    orders.save(order);
    events.publish(new OrderConfirmedEvent(id.value(), order.customerId()), context);
    return null;
  }
}
