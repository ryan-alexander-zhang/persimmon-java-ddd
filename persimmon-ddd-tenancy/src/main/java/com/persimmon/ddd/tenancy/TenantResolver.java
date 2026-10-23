package com.persimmon.ddd.tenancy;

import com.persimmon.ddd.application.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Finds the tenant of a request: a header behind a trusted gateway, a JWT claim, the host name. */
@FunctionalInterface
public interface TenantResolver {
  Optional<TenantId> resolve(HttpServletRequest request);
}
