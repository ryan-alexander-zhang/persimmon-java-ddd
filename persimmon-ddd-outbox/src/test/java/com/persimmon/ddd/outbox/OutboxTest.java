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
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class OutboxTest {
  @EventType(context = "ordering", name = "OrderPlaced", version = 1)
  record OrderPlaced(String orderId, int lines) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  @EventType(context = "ordering", name = "OrderShipped", version = 1)
  record OrderShipped(String orderId) implements IntegrationEvent {}

  record CancelOrder(String orderId) implements Command<Void> {}

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
  @Autowired IntegrationEvents events;
  @Autowired CommandScheduler scheduler;
  @Autowired OutboxRelay relay;
  @Autowired OutboxStore store;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    for (String table : List.of("persimmon_outbox", "persimmon_dead_letter", "persimmon_inbox")) {
      jdbc.update("DELETE FROM " + table);
    }
    app.received.clear();
    app.tenants.clear();
    app.cancelled.clear();
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
    assertEquals(
        ScheduledCommandWriter.SOURCE,
        jdbc.queryForObject("SELECT source FROM persimmon_outbox WHERE sent = FALSE", String.class));
  }

  @Test
  void aScheduledCommandWhoseClassIsGoneIsDeadLettered() {
    tx.executeWithoutResult(s -> scheduler.schedule(new CancelOrder("o-1"), Instant.EPOCH, CommandContext.root("c")));
    jdbc.update("UPDATE persimmon_outbox SET type = 'com.example.Gone'");
    relay.relay();
    assertEquals("PERMANENT", row("persimmon_dead_letter", "id").get("REASON"));
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

  @Test
  void aRedeliveredRowIsSkippedByTheInbox() {
    publish(new OrderPlaced("o-1", 1));
    relay.relay();
    jdbc.update("UPDATE persimmon_outbox SET sent = FALSE, sent_at = NULL");
    relay.relay();
    assertEquals(1, app.received.size());
    assertEquals(1, count("persimmon_inbox"));
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
}
