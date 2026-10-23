package com.persimmon.ddd.application;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant bound to the current thread. The tenancy module binds it per request and per relayed
 * message and sets {@link #setRequired}; until then {@link #effective()} is {@link TenantId#ROOT}.
 */
public final class TenantContext {
  private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();
  private static volatile boolean required;

  private TenantContext() {}

  public static Optional<TenantId> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** The bound tenant, or {@link TenantId#ROOT} when tenancy is off; throws when it is on and none is bound. */
  public static TenantId effective() {
    TenantId tenant = CURRENT.get();
    if (tenant != null) {
      return tenant;
    }
    if (required) {
      throw new MissingTenantException("multi-tenancy is on but no tenant is bound to this thread");
    }
    return TenantId.ROOT;
  }

  public static <T> T runAs(TenantId tenant, Supplier<T> work) {
    TenantId previous = CURRENT.get();
    CURRENT.set(tenant);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public static void runAs(TenantId tenant, Runnable work) {
    runAs(
        tenant,
        () -> {
          work.run();
          return null;
        });
  }

  /** Set by the tenancy module when it is enabled. */
  public static void setRequired(boolean value) {
    required = value;
  }
}
