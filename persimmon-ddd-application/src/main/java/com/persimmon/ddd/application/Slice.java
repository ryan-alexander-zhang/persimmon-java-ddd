package com.persimmon.ddd.application;

import java.util.List;

/** A keyset-paged result: the items and the cursor for the next slice, or {@code null} at the end. */
public record Slice<T>(List<T> items, Cursor nextCursor) {
  public Slice {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public boolean hasNext() {
    return nextCursor != null;
  }
}
