package com.persimmon.ddd.outbox;

/** A dispatch failure no retry can fix; the relay dead-letters the message at once. */
public class PermanentDispatchException extends RuntimeException {
  public PermanentDispatchException(String message) {
    super(message);
  }
}
