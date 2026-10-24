package com.persimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {
  @Test
  void routesMatchExactlyThenByTheLongestPrefix() {
    OutboxProperties p =
        new OutboxProperties(
            100, 10, Duration.ofSeconds(1), Duration.ofMinutes(5),
            new OutboxProperties.Retry(Duration.ofSeconds(1), Duration.ofMinutes(1)),
            new OutboxProperties.Cleanup(false, Duration.ofDays(7), Duration.ofDays(30), 500, Duration.ofHours(1)),
            Map.of("ordering.*", "ordering.events", "ordering.OrderShipped", "shipping.events", "ordering.Order*", "orders"));
    assertEquals("shipping.events", p.destinationFor("ordering.OrderShipped"));
    assertEquals("orders", p.destinationFor("ordering.OrderPlaced"));
    assertEquals("ordering.events", p.destinationFor("ordering.Invoiced"));
    assertNull(p.destinationFor("billing.Invoiced"));
  }
}
