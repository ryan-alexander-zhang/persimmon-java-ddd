package com.example.ordering.domain;

import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.ErrorCode;
import com.persimmon.ddd.core.Invariant;
import com.persimmon.ddd.core.Transitions;
import com.persimmon.ddd.core.annotation.AggregateRoot;
import com.persimmon.ddd.core.annotation.Identity;
import java.util.List;

@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {
  private static final Transitions<OrderStatus> TRANSITIONS =
      Transitions.<OrderStatus>of()
          .allow(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderingErrorCode.ORDER_NOT_CONFIRMABLE)
          .allow(OrderStatus.PLACED, OrderStatus.CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE);

  @Identity private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;
  private OrderStatus status;

  private Order(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.status = status;
  }

  /** A new order; the only way in from outside the persistence layer. */
  public static Order place(OrderId id, String customerId, List<OrderLine> lines) {
    Order order = new Order(id, customerId, lines, OrderStatus.PLACED);
    order.checkInvariant(new HasAtLeastOneLine(lines));
    order.registerEvent(new OrderPlaced(id, customerId));
    return order;
  }

  /** Rebuilds a persisted order; no invariants, no events, version carried over. */
  public static Order reconstitute(
      OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, long version) {
    Order order = new Order(id, customerId, lines, status);
    order.restoreVersion(version);
    return order;
  }

  public void confirm() {
    TRANSITIONS.check(status, OrderStatus.CONFIRMED);
    status = OrderStatus.CONFIRMED;
    registerEvent(new OrderConfirmed(id));
  }

  public void cancel() {
    TRANSITIONS.check(status, OrderStatus.CANCELLED);
    status = OrderStatus.CANCELLED;
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<OrderLine> lines() {
    return lines;
  }

  public OrderStatus status() {
    return status;
  }

  private record HasAtLeastOneLine(List<OrderLine> lines) implements Invariant {
    @Override
    public boolean isBroken() {
      return lines.isEmpty();
    }

    @Override
    public String message() {
      return "an order needs at least one line";
    }

    @Override
    public ErrorCode errorCode() {
      return OrderingErrorCode.EMPTY_ORDER;
    }
  }
}
