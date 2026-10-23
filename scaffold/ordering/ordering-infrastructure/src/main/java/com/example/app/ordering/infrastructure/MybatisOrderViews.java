package com.example.app.ordering.infrastructure;

import com.example.app.ordering.application.OrderView;
import com.example.app.ordering.application.OrderViews;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class MybatisOrderViews implements OrderViews {
  private final OrderMapper orders;
  private final MybatisOrders repository;

  MybatisOrderViews(OrderMapper orders, MybatisOrders repository) {
    this.orders = orders;
    this.repository = repository;
  }

  @Override
  public Optional<OrderView> find(String id) {
    OrderRow row = orders.selectById(id);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new OrderView(
            row.getId(),
            row.getCustomerId(),
            row.getStatus(),
            repository.linesOf(id).stream().map(l -> new OrderView.Line(l.getSku(), l.getQuantity())).toList()));
  }
}
