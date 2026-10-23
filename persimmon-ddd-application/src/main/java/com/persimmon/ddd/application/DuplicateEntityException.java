package com.persimmon.ddd.application;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;

/** A uniqueness rule was violated on write. Maps to conflict at the edge. */
public class DuplicateEntityException extends ApplicationException {
  public DuplicateEntityException(String message, Throwable cause) {
    super(null, message, cause);
  }

  public DuplicateEntityException(ErrorCode errorCode, String message, Throwable cause) {
    super(errorCode, message, cause);
  }

  @Override
  protected ErrorCategory fallbackCategory() {
    return ErrorCategory.CONFLICT;
  }
}
