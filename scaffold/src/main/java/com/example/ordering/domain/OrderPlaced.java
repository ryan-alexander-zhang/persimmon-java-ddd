package com.example.ordering.domain;

import com.persimmon.ddd.core.DomainEvent;

public record OrderPlaced(OrderId orderId, String customerId) implements DomainEvent {}
