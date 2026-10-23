package com.persimmon.ddd.application;

/** Wraps every command dispatch. Lower {@link #order()} runs outermost. */
public interface CommandInterceptor {
  <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation);

  default int order() {
    return 0;
  }

  @FunctionalInterface
  interface Invocation<R> {
    R proceed();
  }
}
