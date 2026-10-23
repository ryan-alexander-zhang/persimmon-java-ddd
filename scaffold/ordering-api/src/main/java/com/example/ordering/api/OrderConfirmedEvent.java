package com.example.ordering.api;

import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;

/** Published contract: other contexts consume this, so its name and shape are versioned. */
@EventType(context = "ordering", name = "OrderConfirmed", version = 1)
public record OrderConfirmedEvent(String orderId, String customerId) implements IntegrationEvent {
  @Override
  public String subject() {
    return orderId;
  }
}
