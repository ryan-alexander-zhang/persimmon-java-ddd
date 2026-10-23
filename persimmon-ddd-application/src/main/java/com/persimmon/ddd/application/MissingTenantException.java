package com.persimmon.ddd.application;

import com.persimmon.ddd.core.ErrorCategory;

/** Tenancy is on and work ran without a bound tenant; a programming error, not a client error. */
public class MissingTenantException extends ApplicationException {
  public MissingTenantException(String message) {
    super(message);
  }

  @Override
  protected ErrorCategory fallbackCategory() {
    return ErrorCategory.UNEXPECTED;
  }
}
