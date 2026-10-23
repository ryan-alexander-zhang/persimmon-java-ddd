package com.persimmon.ddd.application;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;

/** An optimistic lock failed: someone else changed the aggregate first. Retryable. */
public class ConcurrencyConflictException extends ApplicationException {
  public ConcurrencyConflictException(String message, Throwable cause) {
    super(null, message, cause);
  }

  public ConcurrencyConflictException(ErrorCode errorCode, String message, Throwable cause) {
    super(errorCode, message, cause);
  }

  @Override
  protected ErrorCategory fallbackCategory() {
    return ErrorCategory.CONFLICT;
  }
}
