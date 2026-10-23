package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** A unit of work is one Spring transaction. */
public class TransactionTemplateUnitOfWork implements UnitOfWork {
  private final TransactionTemplate template;

  public TransactionTemplateUnitOfWork(TransactionTemplate template) {
    this.template = template;
  }

  @Override
  public <R> R execute(Supplier<R> work) {
    return template.execute(status -> work.get());
  }
}
