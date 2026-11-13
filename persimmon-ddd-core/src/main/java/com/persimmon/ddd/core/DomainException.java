package com.persimmon.ddd.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base for failures raised by the domain. Carries an optional {@link ErrorCode}; its
 * {@link #category()} is what the edge maps to a transport status. {@link #with} adds properties the
 * edge writes next to the code, e.g. {@code throw new DomainException(code, "...").with("field", "country")}.
 */
public class DomainException extends RuntimeException {
  private final transient ErrorCode errorCode;
  private final transient Map<String, Object> properties = new LinkedHashMap<>();

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

  /** Adds a property: a string, number, boolean, list, map or record. Call before throwing. */
  public DomainException with(String name, Object value) {
    properties.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    return this;
  }

  /** The properties in the order they were added; read-only. */
  public Map<String, Object> properties() {
    return Collections.unmodifiableMap(properties);
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
