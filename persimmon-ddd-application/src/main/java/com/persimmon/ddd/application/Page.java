package com.persimmon.ddd.application;

import java.util.List;

/** An offset-paged result with totals; use {@link Slice} when a count is too expensive. */
public record Page<T>(List<T> items, Cursor nextCursor, long totalElements, int totalPages) {
  public Page {
    items = items == null ? List.of() : List.copyOf(items);
    if (totalElements < 0 || totalPages < 0) {
      throw new IllegalArgumentException("totals must not be negative");
    }
  }
}
