package com.persimmon.ddd.core;

/** A state change the {@link Transitions} table does not allow; the edge maps it to a conflict. */
public final class IllegalStateTransitionException extends DomainException {
  public IllegalStateTransitionException(ErrorCode errorCode, Object from, Object to) {
    super(errorCode, "illegal state transition: " + from + " -> " + to);
  }
}
