package com.persimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.CommandScheduler;
import com.persimmon.ddd.application.CommandType;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import com.persimmon.ddd.outbox.billing.BillingListener;
import com.persimmon.ddd.outbox.inventory.InventoryListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
public class OutboxTest {
  @EventType(context = "ordering", name = "OrderPlaced", version = 1)
  public record OrderPlaced(String orderId, int lines) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  @EventType(context = "ordering", name = "OrderShipped", version = 1)
  record OrderShipped(String orderId) implements IntegrationEvent {}

  @CommandType("ordering.CancelOrder")
  record CancelOrder(String orderId) implements Command<Void> {}

  record RenamedLater(String orderId) implements Command<Void> {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App implements CommandHandler<CancelOrder, Void> {
    final List<EventEnvelope<OrderPlaced>> received = new ArrayList<>();
    final List<TenantId> tenants = new ArrayList<>();
    final List<CommandContext> cancelled = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public Void handle(CancelOrder command, CommandContext context) {
      cancelled.add(context);
      tenants.add(TenantContext.effective());
      return null;
    }

    @Bean
    BillingListener billing() {
      return new BillingListener();
    }

    /** {@code @SpringBootTest} switches metrics off; the outbox gauges and counters need a registry. */
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    InventoryListener inventory() {
      return new InventoryListener();
    }

    @EventListener
    void on(EventEnvelope<OrderPlaced> envelope) {
      if (failWith != null) {
        throw failWith;
      }
      received.add(envelope);
      tenants.add(TenantContext.effective());
    }
  }

  @Autowired App app;
  @Autowired BillingListener billing;
  @Autowired InventoryListener inventory;
  @Autowired IntegrationEvents events;
  @Autowired CommandScheduler scheduler;
  @Autowired OutboxRelay relay;
  @Autowired OutboxStore store;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;
  @Autowired OutboxEndpoint endpoint;

  @BeforeEach
  void clean() {
    for (String table : List.of("persimmon_outbox", "persimmon_dead_letter", "persimmon_inbox")) {
      jdbc.update("DELETE FROM " + table);
    }
    app.received.clear();
    app.tenants.clear();
    app.cancelled.clear();
    billing.invoiced.clear();
    inventory.reserved.clear();
    inventory.failWith = null;
    app.failWith = null;
  }

  private void publish(IntegrationEvent event) {
    tx.executeWithoutResult(s -> events.publish(event, CommandContext.root("cmd-1")));
  }

  private Map<String, Object> row(String table, String orderBy) {
    return jdbc.queryForMap("SELECT * FROM " + table + " ORDER BY " + orderBy + " LIMIT 1");
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  @Test
  void aPublishedEventIsWrittenWithTheCommandAndDeliveredByTheRelay() {
    publish(new OrderPlaced("o-1", 2));
    Map<String, Object> written = row("persimmon_outbox", "id");
    assertEquals("ordering.OrderPlaced", written.get("TYPE"));
    assertEquals("ordering", written.get("SOURCE"), "the source is the event's context, not the application");
    assertEquals("cmd-1", written.get("CORRELATION_ID"));
    assertEquals("cmd-1", written.get("CAUSATION_ID"));
    assertEquals(false, written.get("SENT"));

    assertEquals(1, relay.relay());
    EventEnvelope<OrderPlaced> envelope = app.received.get(0);
    assertEquals(new OrderPlaced("o-1", 2), envelope.payload());
    assertEquals("o-1", envelope.subject());
    assertEquals(true, row("persimmon_outbox", "id").get("SENT"));
    assertEquals(0, relay.relay(), "nothing left to relay");
  }

  @Test
  void theRowCarriesTheWritersTenantAndTheListenerRunsUnderIt() {
    TenantContext.runAs(TenantId.of("acme"), () -> publish(new OrderPlaced("o-1", 1)));
    assertEquals("acme", row("persimmon_outbox", "id").get("TENANT_ID"));
    relay.relay();
    assertEquals(List.of(TenantId.of("acme")), app.tenants);
    assertEquals("acme", app.received.get(0).tenantId());
  }

  @Test
  void aScheduledCommandIsSentWhenDueAsAChildOfTheSchedulingContext() {
    Instant now = Instant.now();
    TenantContext.runAs(
        TenantId.of("acme"),
        () -> tx.executeWithoutResult(s -> {
          scheduler.schedule(new CancelOrder("o-1"), now.plus(Duration.ofHours(1)), CommandContext.root("cmd-1"));
          scheduler.schedule(new CancelOrder("o-2"), now.minusSeconds(1), CommandContext.root("cmd-2"));
        }));
    assertEquals(2, count("persimmon_outbox"));

    assertEquals(1, relay.relay(), "only the due command is claimed");
    assertEquals(1, app.cancelled.size());
    assertEquals("cmd-2", app.cancelled.get(0).correlationId());
    assertEquals(List.of(TenantId.of("acme")), app.tenants);
    assertEquals(0, relay.relay(), "the other waits for its hour");
    Map<String, Object> waiting = row("persimmon_outbox", "id");
    assertEquals(ScheduledCommandWriter.SOURCE, waiting.get("SOURCE"));
    assertEquals("ordering.CancelOrder", waiting.get("TYPE"), "@CommandType names the row, not the class");
  }

  @Test
  void aScheduledCommandWhoseClassIsGoneIsDeadLetteredButARenamedOneStillArrives() {
    tx.executeWithoutResult(s -> {
      scheduler.schedule(new CancelOrder("o-1"), Instant.now().minusSeconds(60), CommandContext.root("c"));
      scheduler.schedule(new CancelOrder("o-2"), Instant.now().minusSeconds(60), CommandContext.root("c"));
    });
    jdbc.update("UPDATE persimmon_outbox SET type = 'com.example.Gone' WHERE payload LIKE '%o-1%'");
    jdbc.update("UPDATE persimmon_outbox SET type = ? WHERE payload LIKE '%o-2%'", CancelOrder.class.getName());
    relay.relay();
    assertEquals("PERMANENT", row("persimmon_dead_letter", "id").get("REASON"));
    assertEquals(1, app.cancelled.size(), "a row written under the old class name before @CommandType still resolves");
  }

  @Test
  void publishingOutsideATransactionIsRefused() {
    assertThrows(
        IllegalStateException.class,
        () -> events.publish(new OrderPlaced("o-1", 1), CommandContext.root("c")));
  }

  @Test
  void aTransientFailureIsRetriedWithBackoffThenDeadLettered() {
    publish(new OrderPlaced("o-1", 1));
    app.failWith = new IllegalStateException("broker down");
    relay.relay();
    Map<String, Object> after = row("persimmon_outbox", "id");
    assertEquals(1, after.get("ATTEMPTS"));
    assertNotNull(after.get("NEXT_ATTEMPT_AT"));
    assertEquals(0, relay.relay(), "not due again yet");

    jdbc.update("UPDATE persimmon_outbox SET next_attempt_at = NULL");
    relay.relay();
    jdbc.update("UPDATE persimmon_outbox SET next_attempt_at = NULL");
    relay.relay();
    assertEquals(0, count("persimmon_outbox"));
    Map<String, Object> dead = row("persimmon_dead_letter", "id");
    assertEquals("RETRIES_EXHAUSTED", dead.get("REASON"));
    assertEquals(3, dead.get("ATTEMPTS"));
    assertTrue(dead.get("LAST_ERROR").toString().contains("broker down"));
  }

  @Test
  void theRelayReportsToMicrometerAndTheEndpointListsReplaysAndDiscardsDeadLetters() {
    double deadLetteredBefore = counter("persimmon.outbox.dead_lettered");
    long dispatchedBefore = dispatches();
    publish(new OrderPlaced("o-1", 1));
    publish(new OrderPlaced("o-2", 1));
    assertEquals(2.0, meters.get("persimmon.outbox.pending").gauge().value());
    assertTrue(meters.get("persimmon.outbox.lag.seconds").gauge().value() >= 0.0);

    app.failWith = new PermanentDispatchException("gone");
    relay.relay();
    assertEquals(0.0, meters.get("persimmon.outbox.pending").gauge().value());
    assertEquals(2.0, meters.get("persimmon.outbox.dead_letters").gauge().value());
    assertEquals(deadLetteredBefore + 2, counter("persimmon.outbox.dead_lettered"));
    assertEquals(dispatchedBefore, dispatches());

    @SuppressWarnings("unchecked")
    List<OutboxStore.DeadLetter> letters = (List<OutboxStore.DeadLetter>) endpoint.deadLetters().get("letters");
    assertEquals(2, letters.size());
    assertEquals("ordering.OrderPlaced", letters.get(0).type());
    String first = letters.get(0).eventId();
    String second = letters.get(1).eventId();
    assertEquals(Map.of("eventId", first, "replayed", true), endpoint.replay(first));
    assertEquals(Map.of("eventId", first, "replayed", false), endpoint.replay(first));
    assertEquals(Map.of("eventId", second, "discarded", true), endpoint.discard(second));
    assertEquals(0, count("persimmon_dead_letter"));

    app.failWith = null;
    relay.relay();
    assertEquals(dispatchedBefore + 1, dispatches());
  }

  private double counter(String name) {
    return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
  }

  private long dispatches() {
    return meters.find("persimmon.outbox.dispatch").timers().stream().mapToLong(t -> t.count()).sum();
  }

  @Test
  void aPermanentFailureIsDeadLetteredAtOnceAndCanBeReplayed() {
    publish(new OrderPlaced("o-1", 1));
    app.failWith = new PermanentDispatchException("no such consumer");
    relay.relay();
    Map<String, Object> dead = row("persimmon_dead_letter", "id");
    assertEquals("PERMANENT", dead.get("REASON"));

    app.failWith = null;
    assertTrue(store.replay(dead.get("EVENT_ID").toString(), Instant.now()));
    assertFalse(store.replay("nope", Instant.now()));
    assertEquals(0, count("persimmon_dead_letter"));
    relay.relay();
    assertEquals(1, app.received.size());
  }

  @Test
  void eventsOfOneSubjectStayInOrderBehindAFailedOne() {
    publish(new OrderPlaced("o-1", 1));
    publish(new OrderPlaced("o-1", 2));
    publish(new OrderPlaced("o-2", 9));
    app.failWith = new IllegalStateException("first one fails");
    assertEquals(2, relay.relay(), "the second o-1 event waits behind the first; o-2 is independent");
    app.failWith = null;
    jdbc.update("UPDATE persimmon_outbox SET next_attempt_at = NULL");
    while (relay.relay() > 0) {}
    List<Integer> o1 =
        app.received.stream().filter(e -> e.subject().equals("o-1")).map(e -> e.payload().lines()).toList();
    assertEquals(List.of(1, 2), o1);
    assertEquals(3, app.received.size());
  }

  /** Several instances poll at once: the two-step claim must hand each row to exactly one of them. */
  @Test
  void concurrentClaimsLeaseEveryRowExactlyOnce() throws Exception {
    int rows = 60;
    for (int i = 0; i < rows; i++) {
      publish(new OrderPlaced("o-" + i, 1));
    }
    AtomicInteger total = new AtomicInteger();
    Set<String> seen = ConcurrentHashMap.newKeySet();
    List<String> duplicates = new java.util.concurrent.CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        workers.add(pool.submit(() -> {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
          while (total.get() < rows && System.nanoTime() < deadline) {
            Instant now = Instant.now();
            for (OutboxMessage m : store.claim(now, 10, 5, UUID.randomUUID().toString(), now.plus(Duration.ofMinutes(5)))) {
              if (!seen.add(m.eventId())) {
                duplicates.add(m.eventId());
              }
              total.incrementAndGet();
            }
          }
        }));
      }
      for (Future<?> worker : workers) {
        worker.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(List.of(), duplicates, "no row leased twice");
    assertEquals(rows, seen.size(), "every row leased");
  }

  @Test
  void aRedeliveredRowIsSkippedByTheInbox() {
    publish(new OrderPlaced("o-1", 1));
    relay.relay();
    jdbc.update("UPDATE persimmon_outbox SET sent = FALSE, sent_at = NULL");
    relay.relay();
    assertEquals(1, app.received.size());
    assertEquals(1, billing.invoiced.size());
    assertEquals(
        List.of("billing", "inventory", "shop-monolith"),
        jdbc.queryForList("SELECT consumer FROM persimmon_inbox ORDER BY consumer", String.class),
        "one inbox record per listening context; a listener outside any context counts as the deployable");
  }

  @Test
  void aFailingContextIsRetriedAloneAndNeverRollsBackAnother() {
    publish(new OrderPlaced("o-1", 1));
    inventory.failWith = new IllegalStateException("inventory bug");
    relay.relay();
    assertEquals(List.of("o-1"), billing.invoiced, "billing committed although inventory failed");
    assertEquals(1, row("persimmon_outbox", "id").get("ATTEMPTS"));

    inventory.failWith = null;
    jdbc.update("UPDATE persimmon_outbox SET next_attempt_at = NULL");
    relay.relay();
    assertEquals(List.of("o-1"), billing.invoiced, "the retry skipped billing");
    assertEquals(List.of("o-1"), inventory.reserved);
    assertEquals(1, app.received.size());
    assertEquals(true, row("persimmon_outbox", "id").get("SENT"));
  }

  @Test
  void aRoutedEventIsDeadLetteredWhileNoBrokerDispatcherIsPresent() {
    publish(new OrderShipped("o-1"));
    assertEquals("ordering.events", row("persimmon_outbox", "id").get("DESTINATION"));
    relay.relay();
    assertEquals("PERMANENT", row("persimmon_dead_letter", "id").get("REASON"));
  }

  @Test
  void cleanupDeletesOnlySentRowsOlderThanTheCutoff() {
    publish(new OrderPlaced("o-1", 1));
    publish(new OrderPlaced("o-2", 1));
    relay.relay();
    jdbc.update("UPDATE persimmon_outbox SET sent_at = ? WHERE subject = 'o-1'",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
    assertEquals(1, store.deleteSentBefore(Instant.now().minus(Duration.ofDays(7)), 100));
    assertEquals(1, count("persimmon_outbox"));
  }

  @Test
  void cleanupDeletesTheOldestInboxRecordsInBatches() {
    for (int i = 0; i < 5; i++) {
      publish(new OrderPlaced("o-" + i, 1));
    }
    relay.relay();
    int perEvent = count("persimmon_inbox") / 5;
    jdbc.update("UPDATE persimmon_inbox SET processed_at = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(60))));
    jdbc.update("UPDATE persimmon_inbox SET processed_at = ? WHERE message_key IN"
        + " (SELECT event_id FROM persimmon_outbox WHERE subject = 'o-4')",
        java.sql.Timestamp.from(Instant.now()));
    Instant cutoff = Instant.now().minus(Duration.ofDays(30));
    assertEquals(4 * perEvent, store.deleteInboxBefore(cutoff, 1), "one timestamp shared by the old rows: all of them go");
    assertEquals(0, store.deleteInboxBefore(cutoff, 100));
    assertEquals(perEvent, count("persimmon_inbox"), "the fresh event's records stay");
  }
}
