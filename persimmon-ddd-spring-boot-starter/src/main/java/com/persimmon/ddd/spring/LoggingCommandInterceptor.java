package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandInterceptor;
import com.persimmon.ddd.core.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Outermost: puts the correlation id on the MDC and logs rejections at info, failures at warn. */
public class LoggingCommandInterceptor implements CommandInterceptor {
  public static final int ORDER = 0;
  public static final String CORRELATION_ID_MDC_KEY = "correlationId";

  private static final Logger log = LoggerFactory.getLogger(LoggingCommandInterceptor.class);

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    String name = command.getClass().getSimpleName();
    String previous = MDC.get(CORRELATION_ID_MDC_KEY);
    MDC.put(CORRELATION_ID_MDC_KEY, context.correlationId());
    try {
      log.debug("handling {} [causationId={}]", name, context.causationId());
      return invocation.proceed();
    } catch (DomainException e) {
      log.info("{} rejected: {}", name, e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      log.warn("{} failed", name, e);
      throw e;
    } finally {
      if (previous == null) {
        MDC.remove(CORRELATION_ID_MDC_KEY);
      } else {
        MDC.put(CORRELATION_ID_MDC_KEY, previous);
      }
    }
  }

  @Override
  public int order() {
    return ORDER;
  }
}
