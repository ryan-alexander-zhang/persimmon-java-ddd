package com.persimmon.ddd.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.ConcurrencyConflictException;
import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.application.Query;
import com.persimmon.ddd.application.QueryBus;
import com.persimmon.ddd.application.QueryHandler;
import com.persimmon.ddd.core.DomainEvent;
import com.persimmon.ddd.core.IdGenerator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
class PersimmonDddAutoConfigurationTest {
  record Place(@NotBlank String sku) implements Command<String> {}

  record Confirm(String orderId) implements Command<Boolean> {}

  record Placed(String sku) implements DomainEvent {}

  record CountOrders() implements Query<Integer> {}

  /** Fails with a conflict {@code failures} times, then returns how many attempts it took. */
  record Bump(int failures) implements Command<Integer> {}

  record BumpInside(int failures) implements Command<Integer> {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    final List<Object> seen = new ArrayList<>();

    @Bean
    CommandHandler<Place, String> placeHandler(DomainEvents events, CommandBus bus) {
      return (command, context) -> {
        seen.add("tx=" + TransactionSynchronizationManager.isActualTransactionActive());
        events.publish(new Placed(command.sku()));
        bus.send(new Confirm("o-1"), context);
        return context.messageId();
      };
    }

    @Bean
    CommandHandler<Confirm, Boolean> confirmHandler() {
      return (command, context) -> {
        seen.add(context);
        return true;
      };
    }

    @Bean
    CommandHandler<Bump, Integer> bumpHandler() {
      return (command, context) -> {
        seen.add("bump");
        if (seen.stream().filter("bump"::equals).count() <= command.failures()) {
          throw new ConcurrencyConflictException("lost the lock", null);
        }
        return (int) seen.stream().filter("bump"::equals).count();
      };
    }

    @Bean
    CommandHandler<BumpInside, Integer> bumpInsideHandler(CommandBus bus) {
      return (command, context) -> bus.send(new Bump(command.failures()), context);
    }

    @Bean
    QueryHandler<CountOrders, Integer> countHandler() {
      return query -> 42;
    }

    @EventListener
    void on(Placed event) {
      seen.add(event);
    }
  }

  @Autowired CommandBus commands;
  @Autowired QueryBus queries;
  @Autowired IdGenerator ids;
  @Autowired App app;

  @BeforeEach
  void clean() {
    app.seen.clear();
  }

  @Test
  void aCommandRunsInATransactionPublishesEventsAndChainsChildCommands() {
    String rootId = commands.send(new Place("sku-1"));
    assertEquals("tx=true", app.seen.get(0));
    assertEquals(new Placed("sku-1"), app.seen.get(1));
    CommandContext child = (CommandContext) app.seen.get(2);
    assertEquals(rootId, child.correlationId());
    assertEquals(rootId, child.causationId());
  }

  @Test
  void anInvalidCommandIsRejectedBeforeItsHandler() {
    assertThrows(ConstraintViolationException.class, () -> commands.send(new Place(" ")));
    assertTrue(app.seen.isEmpty());
  }

  @Test
  void aLostOptimisticLockIsRetriedInAFreshTransactionButNotInsideACallersTransaction() {
    assertEquals(3, commands.send(new Bump(2)), "two conflicts, third attempt wins");
    app.seen.clear();
    assertEquals(4, commands.send(new Bump(3)), "three retries by default: four attempts");
    app.seen.clear();
    assertThrows(ConcurrencyConflictException.class, () -> commands.send(new Bump(4)), "the fifth is not tried");
    app.seen.clear();
    assertThrows(ConcurrencyConflictException.class, () -> commands.send(new BumpInside(4)));
    assertEquals(4, app.seen.stream().filter("bump"::equals).count(),
        "the inner command is not retried inside the caller's transaction; the outer one is, as a whole");
  }

  @Test
  void queriesRouteToTheirHandler() {
    assertEquals(42, queries.ask(new CountOrders()));
  }

  @Test
  void idsAreUniqueVersionSevenUuids() {
    Set<String> unique = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String id = ids.newId();
      UUID uuid = UUID.fromString(id);
      assertEquals(7, uuid.version());
      assertEquals(2, uuid.variant());
      assertTrue(unique.add(id));
    }
    assertNotNull(ids);
  }
}
