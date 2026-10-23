package com.persimmon.ddd.application;

/**
 * The data-isolation boundary. {@link #ROOT} is the single tenant of a single-tenant deployment;
 * user tenants may not start with {@code __}.
 */
public record TenantId(String value) {
  public static final TenantId ROOT = new TenantId("__root__");

  public TenantId {
    if (value == null || value.isBlank() || value.length() > 32) {
      throw new IllegalArgumentException("tenant id must be 1..32 characters");
    }
  }

  /** A tenant named by a client; rejects the reserved {@code __} prefix. */
  public static TenantId of(String value) {
    if (value != null && value.startsWith("__")) {
      throw new IllegalArgumentException("tenant id must not start with '__': " + value);
    }
    return new TenantId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
