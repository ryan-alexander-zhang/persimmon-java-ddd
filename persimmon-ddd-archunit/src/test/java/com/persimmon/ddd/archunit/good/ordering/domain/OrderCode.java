package com.persimmon.ddd.archunit.good.ordering.domain;

import com.persimmon.ddd.core.ErrorCode;

public enum OrderCode implements ErrorCode {
  EMPTY;

  @Override
  public String code() {
    return "ordering.empty";
  }
}
