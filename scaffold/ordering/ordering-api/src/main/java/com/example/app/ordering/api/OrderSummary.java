package com.example.app.ordering.api;

import com.persimmon.ddd.application.ReadModel;

/** What ordering tells other contexts about an order; not the aggregate. */
@ReadModel
public record OrderSummary(String orderId, String customerId, String status) {}
