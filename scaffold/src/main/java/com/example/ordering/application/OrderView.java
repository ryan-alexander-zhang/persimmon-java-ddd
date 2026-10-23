package com.example.ordering.application;

import com.persimmon.ddd.application.ReadModel;
import java.util.List;

@ReadModel
public record OrderView(String id, String customerId, String status, List<Line> lines) {
  public record Line(String sku, int quantity) {}
}
