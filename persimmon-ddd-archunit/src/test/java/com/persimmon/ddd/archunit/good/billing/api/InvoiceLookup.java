package com.persimmon.ddd.archunit.good.billing.api;

import java.util.Optional;

/** A synchronous call another context may make; billing implements it as a bean. */
public interface InvoiceLookup {
  Optional<InvoiceSummary> find(String orderId);

  record InvoiceSummary(String orderId, String status) {}
}
