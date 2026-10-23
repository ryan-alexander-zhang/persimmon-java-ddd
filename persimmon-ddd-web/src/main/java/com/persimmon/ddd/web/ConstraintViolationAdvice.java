package com.persimmon.ddd.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Bean Validation failures raised outside argument binding, such as from a validated service. */
@RestControllerAdvice
public class ConstraintViolationAdvice {
  private final Problems problems;

  public ConstraintViolationAdvice(Problems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handle(ConstraintViolationException ex) {
    List<FieldError> errors =
        ex.getConstraintViolations().stream().map(ConstraintViolationAdvice::toFieldError).toList();
    return problems.of(HttpStatus.BAD_REQUEST, "Validation failed", errors);
  }

  private static FieldError toFieldError(ConstraintViolation<?> violation) {
    String code =
        violation.getConstraintDescriptor() == null
            ? null
            : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    return new FieldError(String.valueOf(violation.getPropertyPath()), code, violation.getMessage());
  }
}
