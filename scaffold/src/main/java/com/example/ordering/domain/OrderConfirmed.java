package com.example.ordering.domain;

import com.persimmon.ddd.core.DomainEvent;

public record OrderConfirmed(OrderId orderId) implements DomainEvent {}
