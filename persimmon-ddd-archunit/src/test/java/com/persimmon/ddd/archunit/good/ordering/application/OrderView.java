package com.persimmon.ddd.archunit.good.ordering.application;

import com.persimmon.ddd.application.Query;
import com.persimmon.ddd.application.ReadModel;

@ReadModel
public record OrderView(String id, String status) {
  public record ById(String id) implements Query<OrderView> {}
}
