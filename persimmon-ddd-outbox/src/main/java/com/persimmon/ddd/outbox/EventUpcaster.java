package com.persimmon.ddd.outbox;

import com.persimmon.ddd.application.IntegrationEvent;

/**
 * Turns an older version of a published contract into the next one, so a consumer keeps a class
 * per version it still receives and handlers for the newest only. Declare one as a bean; chains
 * are followed ({@code v1 -> v2 -> v3}). {@code F} and {@code T} share context and name.
 */
@FunctionalInterface
public interface EventUpcaster<F extends IntegrationEvent, T extends IntegrationEvent> {
  T upcast(F event);
}
