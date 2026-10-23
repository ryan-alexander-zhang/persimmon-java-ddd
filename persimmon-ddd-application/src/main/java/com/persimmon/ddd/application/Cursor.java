package com.persimmon.ddd.application;

/** An opaque position in a result set; the client passes it back to get the next slice. */
public record Cursor(String value) {
  public Cursor {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("cursor value must not be blank");
    }
  }
}
