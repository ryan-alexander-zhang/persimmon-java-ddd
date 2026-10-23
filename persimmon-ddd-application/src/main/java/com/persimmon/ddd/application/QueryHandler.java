package com.persimmon.ddd.application;

/** Handles exactly one query type. */
public interface QueryHandler<Q extends Query<R>, R> {
  R handle(Q query);
}
