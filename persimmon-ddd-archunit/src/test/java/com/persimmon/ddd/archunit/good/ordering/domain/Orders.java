package com.persimmon.ddd.archunit.good.ordering.domain;

import com.persimmon.ddd.core.annotation.Repository;
import java.util.Optional;

@Repository
public interface Orders {
  void save(Order order);

  Optional<Order> find(OrderId id);
}
