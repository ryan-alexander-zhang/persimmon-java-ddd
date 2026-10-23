package com.example.ordering.domain;

import com.persimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The port; the MyBatis implementation lives in infrastructure. */
@Repository
public interface Orders {
  void save(Order order);

  Optional<Order> find(OrderId id);
}
