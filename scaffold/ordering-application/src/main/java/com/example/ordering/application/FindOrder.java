package com.example.ordering.application;

import com.persimmon.ddd.application.Query;

public record FindOrder(String id) implements Query<OrderView> {}
