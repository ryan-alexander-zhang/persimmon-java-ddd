package com.persimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.DomainEvent;
import com.persimmon.ddd.core.Identifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainEventsTest {
  private record Id(String v) implements Identifier {}

  private record Happened(String what) implements DomainEvent {}

  private static final class Thing extends AbstractAggregateRoot<Id> {
    @Override
    public Id id() {
      return new Id("t");
    }

    void raise(String what) {
      registerEvent(new Happened(what));
    }
  }

  @Test
  void publishAndClearPublishesInOrderAndEmptiesTheAggregate() {
    List<DomainEvent> published = new ArrayList<>();
    DomainEvents events = published::add;
    Thing thing = new Thing();
    thing.raise("a");
    thing.raise("b");
    events.publishAndClear(thing);
    assertEquals(List.of(new Happened("a"), new Happened("b")), published);
    assertTrue(thing.domainEvents().isEmpty());
  }

  @Test
  void publishAndClearFailsWhenAListenerRecordsOnTheAlreadyPersistedAggregate() {
    Thing thing = new Thing();
    DomainEvents events = event -> thing.raise("echo");
    thing.raise("a");
    assertThrows(IllegalStateException.class, () -> events.publishAndClear(thing));
  }
}
