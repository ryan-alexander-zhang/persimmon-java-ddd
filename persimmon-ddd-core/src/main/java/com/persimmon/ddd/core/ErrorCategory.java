package com.persimmon.ddd.core;

/** Coarse class of a failure; the edge maps it to a transport status. */
public enum ErrorCategory {
  DOMAIN_RULE,
  NOT_FOUND,
  CONFLICT,
  VALIDATION,
  UNAUTHORIZED,
  FORBIDDEN,
  UNEXPECTED
}
