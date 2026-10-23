package com.persimmon.ddd.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One poll: lease a batch of due rows, dispatch each, mark the delivered ones sent, schedule a
 * retry or dead-letter the failed ones. Several instances poll at once and lease disjoint rows;
 * a crashed instance's rows free themselves when the lease expires.
 */
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxStore store;
  private final OutboxDispatcher dispatcher;
  private final FailureClassifier failures;
  private final RetryBackoff backoff;
  private final Clock clock;
  private final OutboxProperties properties;
  private final TraceContextCarrier traces;

  public OutboxRelay(
      OutboxStore store,
      OutboxDispatcher dispatcher,
      FailureClassifier failures,
      RetryBackoff backoff,
      Clock clock,
      OutboxProperties properties,
      TraceContextCarrier traces) {
    this.store = store;
    this.dispatcher = dispatcher;
    this.failures = failures;
    this.backoff = backoff;
    this.clock = clock;
    this.properties = properties;
    this.traces = traces;
  }

  /** Returns how many rows were claimed; 0 means nothing was due. */
  public int relay() {
    Instant now = clock.instant();
    List<OutboxMessage> claimed =
        store.claim(
            now,
            properties.maxAttempts(),
            properties.batchSize(),
            UUID.randomUUID().toString(),
            now.plus(properties.lease()));
    List<String> delivered = new ArrayList<>();
    for (OutboxMessage message : claimed) {
      try {
        if (message.destination() != null && !dispatcher.reachesExternalTargets()) {
          throw new PermanentDispatchException(
              message.type() + " is destined for '" + message.destination() + "' but the active"
                  + " dispatcher stays in process; add the messaging module for that transport");
        }
        dispatch(message);
        delivered.add(message.eventId());
      } catch (RuntimeException e) {
        handleFailure(message, e);
      }
    }
    if (!delivered.isEmpty()) {
      store.markSent(delivered, clock.instant());
    }
    return claimed.size();
  }

  /** Under the writer's trace as a child span, and with its correlation id on the MDC. */
  private void dispatch(OutboxMessage message) {
    MDC.put("correlationId", message.correlationId());
    try {
      traces.restore(
          message.traceparent(),
          message.tracestate(),
          "outbox.dispatch " + message.type(),
          () -> {
            dispatcher.dispatch(message);
            return null;
          });
    } finally {
      MDC.remove("correlationId");
    }
  }

  private void handleFailure(OutboxMessage message, RuntimeException error) {
    int attempts = message.attempts() + 1;
    OutboxStore.DeadLetterReason reason = null;
    if (failures.isPermanent(error)) {
      reason = OutboxStore.DeadLetterReason.PERMANENT;
    } else if (attempts >= properties.maxAttempts()) {
      reason = OutboxStore.DeadLetterReason.RETRIES_EXHAUSTED;
    }
    if (reason == null) {
      Duration delay = backoff.nextDelay(attempts);
      store.scheduleRetry(message.eventId(), clock.instant().plus(delay));
      log.warn(
          "outbox dispatch of {} failed, retry {}/{} in {}ms",
          message.eventId(), attempts, properties.maxAttempts(), delay.toMillis(), error);
      return;
    }
    store.deadLetter(message, attempts, reason, summarize(error), clock.instant());
    log.error("outbox dispatch of {} dead-lettered: {}", message.eventId(), reason, error);
  }

  static String summarize(Throwable error) {
    StringBuilder text = new StringBuilder();
    for (Throwable cause = error; cause != null && text.length() < 2000; cause = cause.getCause()) {
      if (!text.isEmpty()) {
        text.append(" <- ");
      }
      text.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
      if (cause.getCause() == cause) {
        break;
      }
    }
    return text.length() > 2000 ? text.substring(0, 2000) : text.toString();
  }
}
