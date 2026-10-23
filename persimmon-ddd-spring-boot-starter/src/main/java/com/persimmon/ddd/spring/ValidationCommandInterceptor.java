package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandInterceptor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;

/** Runs Bean Validation on the command before the transaction opens. */
public class ValidationCommandInterceptor implements CommandInterceptor {
  public static final int ORDER = 100;

  private final Validator validator;

  public ValidationCommandInterceptor(Validator validator) {
    this.validator = validator;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    Set<ConstraintViolation<Command<R>>> violations = validator.validate(command);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return invocation.proceed();
  }

  @Override
  public int order() {
    return ORDER;
  }
}
