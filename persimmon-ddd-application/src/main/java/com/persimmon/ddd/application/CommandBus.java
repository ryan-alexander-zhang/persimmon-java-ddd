package com.persimmon.ddd.application;

/** Dispatches a command to its handler through the interceptor chain. */
public interface CommandBus {
  /** Starts a new causal chain: the context gets a fresh message id and correlation id. */
  <R> R send(Command<R> command);

  /** Continues the chain of {@code cause}: same correlation id, {@code cause} as causation. */
  <R> R send(Command<R> command, CommandContext cause);
}
