package com.persimmon.ddd.application;

import java.util.HashMap;
import java.util.Map;

/** A query bus over an explicit handler registry. Register first, then ask from any thread. */
public final class SimpleQueryBus implements QueryBus {
  private final Map<Class<?>, QueryHandler<?, ?>> handlers = new HashMap<>();

  public <Q extends Query<R>, R> SimpleQueryBus register(
      Class<Q> queryType, QueryHandler<Q, R> handler) {
    if (handlers.putIfAbsent(queryType, handler) != null) {
      throw new IllegalStateException("two query handlers for " + queryType.getName());
    }
    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> R ask(Query<R> query) {
    QueryHandler<Query<R>, R> handler = (QueryHandler<Query<R>, R>) handlers.get(query.getClass());
    if (handler == null) {
      throw new IllegalStateException("no query handler for " + query.getClass().getName());
    }
    return handler.handle(query);
  }
}
