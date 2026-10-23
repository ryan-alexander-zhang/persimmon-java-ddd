package com.persimmon.ddd.web;

import com.persimmon.ddd.core.DomainException;
import java.util.List;
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
 * mapping from {@link ResponseEntityExceptionHandler}; this adds the domain, validation detail
 * and the last-resort 500.
 */
@RestControllerAdvice
public class WebExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

  private final Problems problems;

  public WebExceptionHandler(Problems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(DomainException.class)
  public ProblemDetail handleDomain(DomainException ex) {
    return problems.of(ex);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    log.error("unhandled exception while serving a request; answering 500", ex);
    return problems.of(HttpStatus.INTERNAL_SERVER_ERROR, null, List.of());
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
