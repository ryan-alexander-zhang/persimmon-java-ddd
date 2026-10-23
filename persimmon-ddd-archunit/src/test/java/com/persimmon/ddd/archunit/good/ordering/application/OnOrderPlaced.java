package com.persimmon.ddd.archunit.good.ordering.application;

import com.persimmon.ddd.application.DomainEventHandler;
import com.persimmon.ddd.archunit.good.ordering.domain.OrderPlaced;
import org.springframework.context.event.EventListener;

@DomainEventHandler
public class OnOrderPlaced {
  @EventListener
  void on(OrderPlaced event) {}
}
