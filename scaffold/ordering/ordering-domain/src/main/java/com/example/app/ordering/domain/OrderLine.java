package com.example.app.ordering.domain;

import com.persimmon.ddd.core.annotation.ValueObject;

@ValueObject
public record OrderLine(String sku, int quantity) {
  public OrderLine {
    if (sku == null || sku.isBlank() || quantity < 1) {
      throw new IllegalArgumentException("a line needs a sku and a positive quantity");
    }
  }
}
