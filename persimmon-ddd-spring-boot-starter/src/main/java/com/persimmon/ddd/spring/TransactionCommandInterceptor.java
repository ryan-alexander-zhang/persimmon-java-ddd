package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandInterceptor;
import com.persimmon.ddd.application.UnitOfWork;

/** Innermost: the handler runs inside one unit of work. */
public class TransactionCommandInterceptor implements CommandInterceptor {
  public static final int ORDER = 200;

  private final UnitOfWork unitOfWork;

  public TransactionCommandInterceptor(UnitOfWork unitOfWork) {
    this.unitOfWork = unitOfWork;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    return unitOfWork.execute(invocation::proceed);
  }

  @Override
  public int order() {
    return ORDER;
  }
}
