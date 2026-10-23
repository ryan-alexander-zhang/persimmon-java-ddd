package com.persimmon.ddd.archunit.good.ordering.domain;

import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.Invariant;
import com.persimmon.ddd.core.Transitions;
import com.persimmon.ddd.core.annotation.AggregateRoot;
import com.persimmon.ddd.core.annotation.Identity;
import java.time.Instant;

@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {
  enum Status {
    NEW,
    PLACED
  }

  private static final Transitions<Status> RULES = Transitions.<Status>of().allow(Status.NEW, Status.PLACED);

  @Identity private final OrderId id;
  private final String customerId;
  private Status status = Status.NEW;
  private Instant placedAt;

  public Order(OrderId id, String customerId) {
    this.id = id;
    this.customerId = customerId;
  }

  @Override
  public OrderId id() {
    return id;
  }

  public void place(int lines, Instant now) {
    checkInvariant(new HasLines(lines));
    RULES.check(status, Status.PLACED);
    status = Status.PLACED;
    placedAt = now;
    registerEvent(new OrderPlaced(id));
  }

  record HasLines(int lines) implements Invariant {
    @Override
    public boolean isBroken() {
      return lines == 0;
    }

    @Override
    public String message() {
      return "an order needs lines";
    }

    @Override
    public com.persimmon.ddd.core.ErrorCode errorCode() {
      return OrderCode.EMPTY;
    }
  }
}
