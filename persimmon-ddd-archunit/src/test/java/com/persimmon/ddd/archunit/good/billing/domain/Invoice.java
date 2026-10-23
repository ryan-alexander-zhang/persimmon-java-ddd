package com.persimmon.ddd.archunit.good.billing.domain;

import com.persimmon.ddd.core.annotation.Entity;

@Entity
public class Invoice {
  private final String orderId;

  public Invoice(String orderId) {
    this.orderId = orderId;
  }
}
