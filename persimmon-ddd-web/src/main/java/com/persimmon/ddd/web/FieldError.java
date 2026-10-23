package com.persimmon.ddd.web;

/** One invalid field in a request, listed under {@code errors} in a problem detail. */
public record FieldError(String field, String code, String message) {
  public FieldError {
    field = field == null || field.isBlank() ? "?" : field;
    code = code == null || code.isBlank() ? "invalid" : code;
  }
}
