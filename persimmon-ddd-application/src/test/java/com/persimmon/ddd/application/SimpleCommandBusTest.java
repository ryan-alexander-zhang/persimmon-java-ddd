package com.persimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimpleCommandBusTest {
  private record Greet(String name) implements Command<String> {}

  private record Shout(String name) implements Command<String> {}

  private record Tagging(String tag, int order, List<String> trace) implements CommandInterceptor {
    @Override
    public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
      trace.add(tag + ">");
      R result = invocation.proceed();
      trace.add("<" + tag);
      return result;
    }
  }

  private final AtomicInteger seq = new AtomicInteger();
  private final List<String> trace = new ArrayList<>();
  private final List<CommandContext> seen = new ArrayList<>();
  private final SimpleCommandBus bus =
      new SimpleCommandBus(
              () -> "m" + seq.incrementAndGet(),
              List.of(new Tagging("inner", 10, trace), new Tagging("outer", 1, trace)))
          .register(
              Greet.class,
              (command, context) -> {
                seen.add(context);
                return "hi " + command.name();
              });

  @Test
  void dispatchesToTheHandlerThroughInterceptorsOrderedOutermostFirst() {
    assertEquals("hi ann", bus.send(new Greet("ann")));
    assertEquals(List.of("outer>", "inner>", "<inner", "<outer"), trace);
  }

  @Test
  void rootSendStartsAChainAndCausedSendContinuesIt() {
    bus.send(new Greet("a"));
    CommandContext root = seen.get(0);
    assertEquals("m1", root.messageId());
    assertEquals("m1", root.correlationId());
    assertNull(root.causationId());
    bus.send(new Greet("b"), root);
    CommandContext child = seen.get(1);
    assertEquals(new CommandContext("m2", "m1", "m1"), child);
  }

  @Test
  void currentContextIsVisibleInsideTheHandlerOnly() {
    bus.register(Shout.class, (command, context) -> CommandContext.current().orElseThrow().messageId());
    assertEquals("m1", bus.send(new Shout("x")));
    assertTrue(CommandContext.current().isEmpty());
  }

  @Test
  void refusesUnknownAndDuplicateHandlers() {
    assertThrows(IllegalStateException.class, () -> bus.send(new Shout("x")));
    assertThrows(IllegalStateException.class, () -> bus.register(Greet.class, (c, ctx) -> ""));
  }

  @Test
  void contextRequiresMessageAndCorrelationIds() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(" ", "c", null));
    assertThrows(IllegalArgumentException.class, () -> new CommandContext("m", null, null));
  }
}
