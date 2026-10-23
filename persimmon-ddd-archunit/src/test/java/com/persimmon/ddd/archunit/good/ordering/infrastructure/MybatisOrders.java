package com.persimmon.ddd.archunit.good.ordering.infrastructure;

import com.persimmon.ddd.archunit.good.ordering.domain.Order;
import com.persimmon.ddd.archunit.good.ordering.domain.OrderId;
import com.persimmon.ddd.archunit.good.ordering.domain.Orders;
import java.util.Optional;

public class MybatisOrders implements Orders {
  @Override
  public void save(Order order) {
    order.versionAdvanced();
  }

  @Override
  public Optional<Order> find(OrderId id) {
    return Optional.empty();
  }
}
