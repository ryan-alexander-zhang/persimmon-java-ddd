package com.persimmon.ddd.application;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;

/** The aggregate or read model a request names does not exist. Maps to not-found at the edge. */
public class EntityNotFoundException extends ApplicationException {
  public EntityNotFoundException(String message) {
    super(message);
  }

  public EntityNotFoundException(ErrorCode errorCode, String message) {
    super(errorCode, message);
  }

  @Override
  protected ErrorCategory fallbackCategory() {
    return ErrorCategory.NOT_FOUND;
  }
}
