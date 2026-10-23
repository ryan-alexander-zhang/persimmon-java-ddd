package com.persimmon.ddd.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AbstractAggregateRootTest {
  private record Ref(String value) implements Identifier {}

  private record SampleEvent(String what) implements DomainEvent {}

  private enum Code implements ErrorCode {
    CREDIT_EXCEEDED;

    @Override
    public String code() {
      return "sample.credit-exceeded";
    }
  }

  private record CreditRule(boolean broken) implements Invariant {
    @Override
    public boolean isBroken() {
      return broken;
    }

    @Override
    public String message() {
      return "credit limit exceeded";
    }

    @Override
    public ErrorCode errorCode() {
      return Code.CREDIT_EXCEEDED;
    }
  }

  private static final class Order extends AbstractAggregateRoot<Ref> {
    private final Ref id;

    Order(String id) {
      this.id = id == null ? null : new Ref(id);
    }

    static Order reconstitute(String id, long version) {
      Order order = new Order(id);
      order.restoreVersion(version);
      return order;
    }

    @Override
    public Ref id() {
      return id;
    }

    void raise(DomainEvent event) {
      registerEvent(event);
    }

    void enforce(Invariant invariant) {
      checkInvariant(invariant);
    }
  }

  private static final class Shipment extends AbstractAggregateRoot<Ref> {
    private final Ref id;

    Shipment(String id) {
      this.id = new Ref(id);
    }

    @Override
    public Ref id() {
      return id;
    }
  }

  @Test
  void recordsEventsInOrderAsASnapshot() {
    Order order = new Order("o-1");
    assertTrue(order.domainEvents().isEmpty());
    SampleEvent first = new SampleEvent("a");
    order.raise(first);
    List<DomainEvent> taken = order.domainEvents();
    order.raise(new SampleEvent("b"));
    assertEquals(List.of(first), taken, "earlier snapshot must not grow");
    assertEquals(2, order.domainEvents().size());
    assertThrows(UnsupportedOperationException.class, () -> taken.add(first));
  }

  @Test
  void drainTakesAndClearsInOneStep() {
    Order order = new Order("o-1");
    SampleEvent first = new SampleEvent("a");
    order.raise(first);
    assertEquals(List.of(first), order.drainDomainEvents());
    assertTrue(order.domainEvents().isEmpty());
    order.raise(new SampleEvent("b"));
    assertEquals(1, order.domainEvents().size());
    order.drainDomainEvents();
    assertTrue(order.drainDomainEvents().isEmpty(), "draining an empty aggregate is not an error");
  }

  @Test
  void checkInvariantThrowsDomainExceptionWithCodeWhenBroken() {
    Order order = new Order("o-1");
    assertDoesNotThrow(() -> order.enforce(new CreditRule(false)));
    DomainException ex = assertThrows(DomainException.class, () -> order.enforce(new CreditRule(true)));
    assertEquals("credit limit exceeded", ex.getMessage());
    assertSame(Code.CREDIT_EXCEEDED, ex.errorCode().orElseThrow());
    assertEquals(ErrorCategory.DOMAIN_RULE, ex.category());
    assertEquals(ErrorCategory.DOMAIN_RULE, new DomainException("plain").category());
  }

  @Test
  void versionStartsAtZeroRestoresAndAdvances() {
    assertEquals(0L, new Order("o-1").version());
    Order order = Order.reconstitute("o-1", 3L);
    order.versionAdvanced();
    assertEquals(4L, order.version());
    assertThrows(IllegalArgumentException.class, () -> Order.reconstitute("o-1", -1L));
  }

  @Test
  void equalityIsByClassAndId() {
    Order order = new Order("o-1");
    assertEquals(order, order);
    assertEquals(new Order("o-1"), Order.reconstitute("o-1", 9L));
    assertEquals(new Order("o-1").hashCode(), new Order("o-1").hashCode());
    assertNotEquals(new Order("o-1"), new Order("o-2"));
    assertNotEquals(new Order("o-1"), new Shipment("o-1"));
    assertNotEquals(new Order(null), new Order(null));
    assertNotEquals(null, order);
    assertNotEquals("o-1", order);
    Set<Order> set = new HashSet<>(List.of(new Order("o-1"), new Order("o-1")));
    assertEquals(1, set.size());
  }
}
