package com.persimmon.ddd.application;

import com.persimmon.ddd.core.DomainException;
import com.persimmon.ddd.core.ErrorCode;

/** A use-case level failure: the request was understood but cannot be carried out. */
public class ApplicationException extends DomainException {
  public ApplicationException(String message) {
    super(message);
  }

  public ApplicationException(ErrorCode errorCode, String message) {
    super(errorCode, message);
  }

  public ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
    super(errorCode, message, cause);
  }
}
