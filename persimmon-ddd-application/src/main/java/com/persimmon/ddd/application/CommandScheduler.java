package com.persimmon.ddd.application;

import java.time.Instant;

/**
 * Sends a command later, durably. The scheduled row commits with the caller's transaction and is
 * sent when due, at least once, under the caller's tenant, correlation id and trace; a failing
 * handler is retried, then dead-lettered, like an integration event. The handler decides whether
 * the command still applies when it arrives, so a deadline that has become moot is a no-op.
 */
public interface CommandScheduler {
  void schedule(Command<?> command, Instant at, CommandContext context);
}
