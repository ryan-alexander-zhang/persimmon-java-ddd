package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandInterceptor;
import com.persimmon.ddd.application.ConcurrencyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Outside the transaction: a command that lost an optimistic lock is run again, up to {@code
 * retries} more times, each in a fresh unit of work. Not when the caller already holds a
 * transaction (a command sent from inside a handler): the conflict then belongs to the caller.
 */
public class ConflictRetryCommandInterceptor implements CommandInterceptor {
  public static final int ORDER = 150;
  private static final Logger log = LoggerFactory.getLogger(ConflictRetryCommandInterceptor.class);

  private final int retries;

  public ConflictRetryCommandInterceptor(int retries) {
    this.retries = retries;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    for (int attempt = 0; ; attempt++) {
      try {
        return invocation.proceed();
      } catch (ConcurrencyConflictException e) {
        if (attempt >= retries || TransactionSynchronizationManager.isActualTransactionActive()) {
          throw e;
        }
        log.info("{} lost an optimistic lock, retry {}/{}", command.getClass().getSimpleName(), attempt + 1, retries);
      }
    }
  }

  @Override
  public int order() {
    return ORDER;
  }
}
