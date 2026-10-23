package com.example.app.ordering.application;

import com.example.app.ordering.domain.OrderingErrorCode;
import com.persimmon.ddd.application.EntityNotFoundException;
import com.persimmon.ddd.application.QueryHandler;
import org.springframework.stereotype.Component;

@Component
class FindOrderHandler implements QueryHandler<FindOrder, OrderView> {
  private final OrderViews views;

  FindOrderHandler(OrderViews views) {
    this.views = views;
  }

  @Override
  public OrderView handle(FindOrder query) {
    return views
        .find(query.id())
        .orElseThrow(
            () -> new EntityNotFoundException(OrderingErrorCode.ORDER_NOT_FOUND, "no order " + query.id()));
  }
}
