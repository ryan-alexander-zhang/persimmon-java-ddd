package com.example.app.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.app.ordering.domain.Order;
import com.example.app.ordering.domain.OrderId;
import com.example.app.ordering.domain.OrderLine;
import com.example.app.ordering.domain.OrderStatus;
import com.example.app.ordering.domain.Orders;
import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {
  private final OrderMapper orders;
  private final OrderLineMapper lines;

  MybatisOrders(OrderMapper orders, OrderLineMapper lines, DomainEvents events) {
    super(orders, events);
    this.orders = orders;
    this.lines = lines;
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  public Optional<Order> find(OrderId id) {
    OrderRow row = orders.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<OrderLine> orderLines =
        linesOf(id.value()).stream().map(l -> new OrderLine(l.getSku(), l.getQuantity())).toList();
    return Optional.of(
        Order.reconstitute(
            id, row.getCustomerId(), orderLines, OrderStatus.valueOf(row.getStatus()), row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    return row;
  }

  /** Lines are replaced wholesale; they have no identity of their own. */
  @Override
  protected void saveChildren(Order order) {
    lines.delete(new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, order.id().value()));
    for (OrderLine line : order.lines()) {
      OrderLineRow row = new OrderLineRow();
      row.setOrderId(order.id().value());
      row.setSku(line.sku());
      row.setQuantity(line.quantity());
      lines.insert(row);
    }
  }

  List<OrderLineRow> linesOf(String orderId) {
    return lines.selectList(
        new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, orderId).orderByAsc(OrderLineRow::getId));
  }
}
