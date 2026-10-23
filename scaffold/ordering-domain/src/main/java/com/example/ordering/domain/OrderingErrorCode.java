package com.example.ordering.domain;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;

/** The context's error catalogue; the code is what clients see, the category picks the status. */
public enum OrderingErrorCode implements ErrorCode {
  EMPTY_ORDER("ordering.empty-order", ErrorCategory.DOMAIN_RULE),
  ORDER_NOT_CONFIRMABLE("ordering.order-not-confirmable", ErrorCategory.CONFLICT),
  ORDER_NOT_CANCELLABLE("ordering.order-not-cancellable", ErrorCategory.CONFLICT),
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND);

  private final String code;
  private final ErrorCategory category;

  OrderingErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}
