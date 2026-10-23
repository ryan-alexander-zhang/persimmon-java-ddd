package com.example.app.ordering.api;

import java.util.Optional;

/**
 * Published contract for a synchronous call: another context depends on this interface, ordering
 * implements it as a bean. The day ordering becomes a service, the caller swaps the bean for an
 * HTTP client of the same interface and nothing else changes.
 */
public interface OrderLookup {
  Optional<OrderSummary> find(String orderId);
}
