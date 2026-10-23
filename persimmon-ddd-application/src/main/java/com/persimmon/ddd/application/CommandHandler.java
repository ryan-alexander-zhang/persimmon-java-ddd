package com.persimmon.ddd.application;

/** Handles exactly one command type. One handler per command class. */
public interface CommandHandler<C extends Command<R>, R> {
  R handle(C command, CommandContext context);
}
