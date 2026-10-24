package com.persimmon.ddd.outbox;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandType;
import java.util.Optional;

/** Every {@link Command} class under the application's packages, by wire name. */
@FunctionalInterface
public interface CommandCatalog {
  Optional<Class<? extends Command<?>>> lookup(String type);

  /** {@code @CommandType} when declared, the class name otherwise. */
  static String typeOf(Class<?> command) {
    CommandType declared = command.getAnnotation(CommandType.class);
    return declared == null ? command.getName() : declared.value();
  }
}
