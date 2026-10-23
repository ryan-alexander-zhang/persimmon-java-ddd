package com.persimmon.ddd.application;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Identity of one command dispatch: its message id, the correlation id shared by the whole causal
 * chain, and the message id of the command or event that caused it ({@code null} for a root).
 */
public record CommandContext(String messageId, String correlationId, String causationId) {
  private static final ThreadLocal<CommandContext> CURRENT = new ThreadLocal<>();

  public CommandContext {
    if (messageId == null || messageId.isBlank()) {
      throw new IllegalArgumentException("messageId required");
    }
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId required");
    }
  }

  public static CommandContext root(String messageId) {
    return new CommandContext(messageId, messageId, null);
  }

  public CommandContext deriveChild(String childMessageId) {
    return new CommandContext(childMessageId, correlationId, messageId);
  }

  /** The context of the command being handled on this thread, if any. */
  public static Optional<CommandContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Runs {@code work} with this context as {@link #current()}; buses call it, handlers do not. */
  public <T> T runAs(Supplier<T> work) {
    CommandContext previous = CURRENT.get();
    CURRENT.set(this);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
