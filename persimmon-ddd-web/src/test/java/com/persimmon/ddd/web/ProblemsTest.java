package com.persimmon.ddd.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {
  @AfterEach
  void clear() {
    MDC.clear();
  }

  private static Object traceId() {
    ProblemDetail problem = Problems.stamp(ProblemDetail.forStatus(HttpStatus.CONFLICT));
    return problem.getProperties() == null ? null : problem.getProperties().get("traceId");
  }

  @Test
  void theTraceIdComesFromMicrometerTracingOrTheOpenTelemetryAgent() {
    assertNull(traceId());
    MDC.put("trace_id", "agent-1");
    assertEquals("agent-1", traceId());
    MDC.put("traceId", "micrometer-1");
    assertEquals("micrometer-1", traceId(), "Micrometer's key wins when both are set");
  }
}
