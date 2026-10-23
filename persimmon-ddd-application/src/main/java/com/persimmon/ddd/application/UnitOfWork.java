package com.persimmon.ddd.application;

import java.util.function.Supplier;

/** Runs work atomically; the infrastructure binds it to a transaction. */
public interface UnitOfWork {
  <R> R execute(Supplier<R> work);

  default void execute(Runnable work) {
    execute(
        () -> {
          work.run();
          return null;
        });
  }
}
