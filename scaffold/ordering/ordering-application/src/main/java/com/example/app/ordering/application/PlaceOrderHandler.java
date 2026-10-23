package com.example.app.ordering.application;

import com.example.app.ordering.domain.Order;
import com.example.app.ordering.domain.OrderId;
import com.example.app.ordering.domain.OrderLine;
import com.example.app.ordering.domain.Orders;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.core.IdGenerator;
import org.springframework.stereotype.Component;

@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {
  private final Orders orders;
  private final IdGenerator ids;

  PlaceOrderHandler(Orders orders, IdGenerator ids) {
    this.orders = orders;
    this.ids = ids;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(ids.newId());
    Order order =
        Order.place(
            id,
            command.customerId(),
            command.lines().stream().map(l -> new OrderLine(l.sku(), l.quantity())).toList());
    orders.save(order);
    return id.value();
  }
}
