package com.persimmon.ddd.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;

/**
 * A bean that shapes every problem detail before it is written: the ones Spring MVC writes (the
 * exception handlers, or a controller returning one) and the ones persimmon filters write
 * themselves, such as the idempotency answers. It may add properties and response headers.
 */
@FunctionalInterface
public interface ProblemCustomizer {
  void customize(ProblemDetail problem, HttpHeaders headers);
}
