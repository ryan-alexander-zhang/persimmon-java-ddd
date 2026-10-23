package com.persimmon.ddd.application;

import com.persimmon.ddd.core.IdGenerator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A command bus over an explicit handler registry. Not thread-safe during registration; register
 * everything first, then dispatch from any thread.
 */
public final class SimpleCommandBus implements CommandBus {
  private final Map<Class<?>, CommandHandler<?, ?>> handlers = new HashMap<>();
  private final List<CommandInterceptor> interceptors;
  private final IdGenerator ids;

  public SimpleCommandBus(IdGenerator ids, List<CommandInterceptor> interceptors) {
    this.ids = ids;
    this.interceptors =
        interceptors.stream().sorted(Comparator.comparingInt(CommandInterceptor::order)).toList();
  }

  public <C extends Command<R>, R> SimpleCommandBus register(
      Class<C> commandType, CommandHandler<C, R> handler) {
    CommandHandler<?, ?> existing = handlers.putIfAbsent(commandType, handler);
    if (existing != null) {
      throw new IllegalStateException("two command handlers for " + commandType.getName());
    }
    return this;
  }

  @Override
  public <R> R send(Command<R> command) {
    return dispatch(command, CommandContext.root(ids.newId()));
  }

  @Override
  public <R> R send(Command<R> command, CommandContext cause) {
    return dispatch(command, cause.deriveChild(ids.newId()));
  }

  @SuppressWarnings("unchecked")
  private <R> R dispatch(Command<R> command, CommandContext context) {
    CommandHandler<Command<R>, R> handler =
        (CommandHandler<Command<R>, R>) handlers.get(command.getClass());
    if (handler == null) {
      throw new IllegalStateException("no command handler for " + command.getClass().getName());
    }
    CommandInterceptor.Invocation<R> invocation = () -> handler.handle(command, context);
    for (int i = interceptors.size() - 1; i >= 0; i--) {
      CommandInterceptor interceptor = interceptors.get(i);
      CommandInterceptor.Invocation<R> next = invocation;
      invocation = () -> interceptor.intercept(command, context, next);
    }
    CommandInterceptor.Invocation<R> outermost = invocation;
    return context.runAs(outermost::proceed);
  }
}
