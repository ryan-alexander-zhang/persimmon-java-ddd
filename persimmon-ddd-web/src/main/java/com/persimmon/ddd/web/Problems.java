package com.persimmon.ddd.web;

import com.persimmon.ddd.core.DomainException;
import com.persimmon.ddd.core.ErrorCode;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds problem details. A code's catalog override wins, else its category's default. */
public final class Problems {
  /** MDC key Micrometer Tracing fills; copied into the body as {@code traceId} when present. */
  public static final String MICROMETER_TRACE_ID_MDC_KEY = "traceId";

  /** MDC key the OpenTelemetry Java agent fills; read when Micrometer's is absent. */
  public static final String TRACE_ID_MDC_KEY = "trace_id";

  private final Map<String, ProblemDescriptor> overrides = new HashMap<>();

  public Problems(List<ProblemCatalog> catalogs) {
    for (ProblemCatalog catalog : catalogs) {
      catalog.overrides().forEach((code, descriptor) -> overrides.put(code.code(), descriptor));
    }
  }

  public ProblemDetail of(DomainException ex) {
    ProblemDescriptor descriptor =
        ex.errorCode().map(ErrorCode::code).map(overrides::get).orElse(null);
    if (descriptor == null) {
      descriptor = ProblemDescriptor.of(ex.category());
    }
    ProblemDetail detail = ProblemDetail.forStatus(descriptor.status());
    detail.setType(URI.create(descriptor.type()));
    detail.setTitle(descriptor.title());
    detail.setDetail(ex.getMessage());
    ex.errorCode().ifPresent(code -> detail.setProperty("code", code.code()));
    return stamp(detail);
  }

  public ProblemDetail of(HttpStatusCode status, String detail, List<FieldError> errors) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    if (!errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }
    return stamp(problem);
  }

  /** Adds the current request id and trace id, when the filters and observability set them. */
  public static ProblemDetail stamp(ProblemDetail detail) {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId != null) {
      detail.setProperty("requestId", requestId);
    }
    String traceId = MDC.get(MICROMETER_TRACE_ID_MDC_KEY);
    if (traceId == null) {
      traceId = MDC.get(TRACE_ID_MDC_KEY);
    }
    if (traceId != null) {
      detail.setProperty("traceId", traceId);
    }
    return detail;
  }
}
