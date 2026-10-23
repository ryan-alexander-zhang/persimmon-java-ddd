package com.persimmon.ddd.core;

/** A business rule an aggregate checks with {@link AbstractAggregateRoot#checkInvariant}. */
public interface Invariant {
  boolean isBroken();

  String message();

  ErrorCode errorCode();
}
