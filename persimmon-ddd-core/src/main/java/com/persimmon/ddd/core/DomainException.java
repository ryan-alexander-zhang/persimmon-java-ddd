package com.persimmon.ddd.core;

import java.util.Optional;

/**
 * Base for failures raised by the domain. Carries an optional {@link ErrorCode}; its
 * {@link #category()} is what the edge maps to a transport status.
 */
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

  /** The code's category when there is a code, else {@link #fallbackCategory()}. */
  public final ErrorCategory category() {
    return errorCode == null ? fallbackCategory() : errorCode.category();
  }

  /** What an uncoded instance of this exception type means; subclasses override. */
  protected ErrorCategory fallbackCategory() {
    return ErrorCategory.DOMAIN_RULE;
  }
}
