package com.persimmon.ddd.tenancy;

import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import java.util.Optional;
import org.springframework.core.task.TaskDecorator;

/** Carries the submitting thread's tenant onto {@code @Async} and executor tasks. */
public final class TenantContextTaskDecorator implements TaskDecorator {
  @Override
  public Runnable decorate(Runnable runnable) {
    Optional<TenantId> tenant = TenantContext.current();
    return tenant.map(t -> (Runnable) () -> TenantContext.runAs(t, runnable)).orElse(runnable);
  }
}
