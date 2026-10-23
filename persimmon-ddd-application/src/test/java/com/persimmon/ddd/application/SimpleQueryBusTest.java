package com.persimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SimpleQueryBusTest {
  private record Count(String of) implements Query<Integer> {}

  private record Missing() implements Query<Integer> {}

  @Test
  void routesByExactQueryClass() {
    SimpleQueryBus bus = new SimpleQueryBus().register(Count.class, query -> query.of().length());
    assertEquals(3, bus.ask(new Count("abc")));
    assertThrows(IllegalStateException.class, () -> bus.ask(new Missing()));
    assertThrows(IllegalStateException.class, () -> bus.register(Count.class, q -> 0));
  }
}
