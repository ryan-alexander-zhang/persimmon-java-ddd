package com.persimmon.ddd.core;

/** A stable, machine-readable failure identity. Usually implemented by an enum per bounded context. */
public interface ErrorCode {
  String code();

  default ErrorCategory category() {
    return ErrorCategory.DOMAIN_RULE;
  }
}
