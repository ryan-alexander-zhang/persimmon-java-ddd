package com.persimmon.ddd.application;

/** Routes a query to its handler. */
public interface QueryBus {
  <R> R ask(Query<R> query);
}
