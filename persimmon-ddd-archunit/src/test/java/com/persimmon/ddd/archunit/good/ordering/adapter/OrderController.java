package com.persimmon.ddd.archunit.good.ordering.adapter;

import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.archunit.good.billing.api.InvoiceRequested;
import com.persimmon.ddd.archunit.good.ordering.application.OrderView;
import com.persimmon.ddd.archunit.good.ordering.application.PlaceOrder;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
  private final CommandBus commands;

  public OrderController(CommandBus commands) {
    this.commands = commands;
  }

  @PostMapping("/orders")
  String place(PlaceOrder command) {
    return commands.send(command);
  }

  @GetMapping("/orders/{id}")
  OrderView get(String id) {
    return new OrderView(id, "PLACED");
  }

  @EventListener
  void on(EventEnvelope<InvoiceRequested> envelope) {}
}
