package com.persimmon.ddd.web;

import com.persimmon.ddd.core.DomainException;
import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every failure leaves as a problem detail. Spring MVC's own exceptions keep their standard
 * mapping from {@link ResponseEntityExceptionHandler}; this adds the domain, validation detail,
 * 503 for an unavailable dependency (a JDBC connection or timeout failure, or Spring's
 * data-access equivalents, anywhere in the cause chain) and the last-resort 500. Every 503 carries
 * {@code Retry-After}.
 */
@RestControllerAdvice
public class WebExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

  private static final Predicate<Throwable> JDBC_UNAVAILABLE = t -> t instanceof SQLTransientConnectionException
      || t instanceof SQLNonTransientConnectionException
      || t instanceof SQLTimeoutException;

  private final Problems problems;
  private final Predicate<Throwable> unavailable;
  private final WebProperties.Unavailable settings;

  public WebExceptionHandler(Problems problems) {
    this(problems, t -> false, new WebProperties.Unavailable(Duration.ofSeconds(1), null));
  }

  /** {@code unavailable} recognises further failures as an unavailable dependency. */
  public WebExceptionHandler(Problems problems, Predicate<Throwable> unavailable, WebProperties.Unavailable settings) {
    this.problems = problems;
    this.unavailable = JDBC_UNAVAILABLE.or(unavailable);
    this.settings = settings;
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
    return respond(problems.of(ex));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    Throwable cause = unavailableCause(ex);
    if (cause != null) {
      log.warn("event=dependency_unavailable error_type={} cause_type={}", ex.getClass().getName(), cause.getClass().getName());
      return respond(problems.of(new DependencyUnavailable(settings.code())));
    }
    log.error("unhandled exception while serving a request; answering 500", ex);
    return respond(problems.of(HttpStatus.INTERNAL_SERVER_ERROR, null, List.of()));
  }

  /** The first throwable in the cause chain that means a dependency is unavailable, else null. */
  private Throwable unavailableCause(Throwable ex) {
    Throwable t = ex;
    for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
      if (unavailable.test(t)) {
        return t;
      }
    }
    return null;
  }

  private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
    if (problem.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.ceilDiv(settings.retryAfter().toMillis(), 1000)));
    }
    return response.body(problem);
  }

  /** What an unavailable dependency becomes: the configured code, else uncoded, category UNAVAILABLE. */
  private static final class DependencyUnavailable extends DomainException {
    DependencyUnavailable(String code) {
      super(code == null ? null : new UnavailableCode(code), "A dependency this service needs is unavailable; retry later.");
    }

    @Override
    protected ErrorCategory fallbackCategory() {
      return ErrorCategory.UNAVAILABLE;
    }
  }

  private record UnavailableCode(String code) implements ErrorCode {
    @Override
    public ErrorCategory category() {
      return ErrorCategory.UNAVAILABLE;
    }
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
            .toList();
    return validationFailed(errors, headers, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            err ->
                                new FieldError(
                                    result.getMethodParameter().getParameterName(),
                                    err.getCodes() == null || err.getCodes().length == 0
                                        ? null
                                        : err.getCodes()[0],
                                    err.getDefaultMessage())))
            .toList();
    return validationFailed(errors, headers, request);
  }

  private ResponseEntity<Object> validationFailed(
      List<FieldError> errors, HttpHeaders headers, WebRequest request) {
    ProblemDetail body = problems.of(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    return new ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
  }

  /** Stamps the request id on the problem details Spring builds for its own exceptions. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail detail) {
      Problems.stamp(detail);
    }
    return response;
  }
}
