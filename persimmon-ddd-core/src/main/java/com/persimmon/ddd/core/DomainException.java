package com.persimmon.ddd.core;

import java.util.Optional;

/** Base for failures raised by the domain. Carries an optional {@link ErrorCode}. */
public class DomainException extends RuntimeException {
  private final transient ErrorCode errorCode;

  public DomainException(String message) {
    this(null, message, null);
  }

  public DomainException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  public DomainException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public Optional<ErrorCode> errorCode() {
    return Optional.ofNullable(errorCode);
  }
}
