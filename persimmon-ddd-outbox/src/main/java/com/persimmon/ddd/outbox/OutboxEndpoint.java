package com.persimmon.ddd.outbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * {@code /actuator/outbox}: the dead letters, newest first; {@code POST /actuator/outbox/{eventId}}
 * replays one, {@code DELETE} discards it. Expose it like any other endpoint
 * ({@code management.endpoints.web.exposure.include}).
 */
@Endpoint(id = "outbox")
public class OutboxEndpoint {
  private static final int LIMIT = 200;

  private final OutboxStore store;
  private final Clock clock;

  public OutboxEndpoint(OutboxStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @ReadOperation
  public Map<String, Object> deadLetters() {
    List<OutboxStore.DeadLetter> letters = store.deadLetters(LIMIT);
    return Map.of("pending", store.countPending(), "deadLetters", store.countDeadLetters(), "letters", letters);
  }

  @WriteOperation
  public Map<String, Object> replay(@Selector String eventId) {
    return Map.of("eventId", eventId, "replayed", store.replay(eventId, clock.instant()));
  }

  @DeleteOperation
  public Map<String, Object> discard(@Selector String eventId) {
    return Map.of("eventId", eventId, "discarded", store.discard(eventId));
  }
}
