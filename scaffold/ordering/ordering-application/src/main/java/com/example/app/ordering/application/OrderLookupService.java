package com.example.app.ordering.application;

import com.example.app.ordering.api.OrderLookup;
import com.example.app.ordering.api.OrderSummary;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The in-process implementation of the published {@link OrderLookup}. */
@Component
class OrderLookupService implements OrderLookup {
  private final OrderViews views;

  OrderLookupService(OrderViews views) {
    this.views = views;
  }

  @Override
  public Optional<OrderSummary> find(String orderId) {
    return views.find(orderId).map(v -> new OrderSummary(v.id(), v.customerId(), v.status()));
  }
}
