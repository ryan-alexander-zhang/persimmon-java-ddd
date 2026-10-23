package com.persimmon.ddd.archunit.good.billing.api;

import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.Externalized;
import com.persimmon.ddd.application.IntegrationEvent;

@EventType(name = "billing.InvoiceRequested", version = 1)
@Externalized("billing.events")
public record InvoiceRequested(String orderId) implements IntegrationEvent {}
