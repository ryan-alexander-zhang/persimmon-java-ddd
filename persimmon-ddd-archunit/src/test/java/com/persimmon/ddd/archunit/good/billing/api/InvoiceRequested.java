package com.persimmon.ddd.archunit.good.billing.api;

import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;

@EventType(context = "billing", name = "InvoiceRequested", version = 1)
public record InvoiceRequested(String orderId) implements IntegrationEvent {}
