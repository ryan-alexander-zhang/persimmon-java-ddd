package com.persimmon.ddd.archunit.good.ordering.application;

import com.persimmon.ddd.application.Command;

public record PlaceOrder(String orderId, String customerId, int lines) implements Command<String> {}
