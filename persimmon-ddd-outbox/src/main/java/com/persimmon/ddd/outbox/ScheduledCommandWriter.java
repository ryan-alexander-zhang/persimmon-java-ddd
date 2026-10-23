package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandScheduler;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.core.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A scheduled command is an outbox row due later: source {@link #SOURCE}, type the command class,
 * no destination (it never leaves the process). The relay, retries, dead letters, inbox dedup,
 * tenant and trace carry over unchanged.
 */
public class ScheduledCommandWriter implements CommandScheduler {
  public static final String SOURCE = "persimmon.command";

  private final OutboxStore store;
  private final ObjectMapper json;
  private final IdGenerator ids;
  private final Clock clock;
  private final TraceContextCarrier traces;

  public ScheduledCommandWriter(
      OutboxStore store, ObjectMapper json, IdGenerator ids, Clock clock, TraceContextCarrier traces) {
    this.store = store;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
    this.traces = traces;
  }

  @Override
  public void schedule(Command<?> command, Instant at, CommandContext context) {
    String type = command.getClass().getName();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "no active transaction while scheduling " + type + "; the row must commit with the change that caused it");
    }
    String payload;
    try {
      payload = json.writeValueAsString(command);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + type, e);
    }
    TraceContextCarrier.Captured trace = traces.capture();
    Instant now = clock.instant();
    store.insert(
        new OutboxMessage(
            ids.newId(), SOURCE, type, 1, payload, now, null, context.correlationId(), context.messageId(),
            null, TenantContext.effective().value(), trace.traceparent(), trace.tracestate(), 0),
        now,
        at);
  }
}
