package com.persimmon.ddd.tenancy;

import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds the resolved tenant to the thread and the MDC for the request; 400 when there is none. */
public class TenantResolutionFilter extends OncePerRequestFilter {
  public static final String MDC_KEY = "tenant";

  private final TenantResolver resolver;
  private final TenancyProperties.MissingTenant missing;
  private final List<String> excludePaths;
  private final AntPathMatcher matcher = new AntPathMatcher();

  public TenantResolutionFilter(TenantResolver resolver, TenancyProperties.MissingTenant missing, List<String> excludePaths) {
    this.resolver = resolver;
    this.missing = missing;
    this.excludePaths = List.copyOf(excludePaths);
  }

  /** Only a plain path is excluded; path parameters or encoded separators could be a bypass. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String lower = path.toLowerCase(Locale.ROOT);
    if (path.contains(";") || path.contains("..") || lower.contains("%2") || lower.contains("%5c")) {
      return false;
    }
    return excludePaths.stream().anyMatch(pattern -> matcher.match(pattern, path));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    TenantId tenant;
    try {
      Optional<TenantId> resolved = resolver.resolve(request);
      if (resolved.isPresent()) {
        tenant = resolved.get();
      } else if (missing == TenancyProperties.MissingTenant.ROOT) {
        tenant = TenantId.ROOT;
      } else {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "missing tenant");
        return;
      }
    } catch (IllegalArgumentException invalid) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid tenant");
      return;
    }
    MDC.put(MDC_KEY, tenant.value());
    try {
      TenantContext.runAs(tenant, () -> {
        try {
          chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
          throw new IllegalStateException(e);
        }
      });
    } catch (IllegalStateException wrapped) {
      if (wrapped.getCause() instanceof IOException io) {
        throw io;
      }
      if (wrapped.getCause() instanceof ServletException servlet) {
        throw servlet;
      }
      throw wrapped;
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
