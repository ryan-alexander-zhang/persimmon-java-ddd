package com.persimmon.ddd.archunit.good.ordering.domain;

import com.persimmon.ddd.core.DomainEvent;

public record OrderPlaced(OrderId id) implements DomainEvent {}
