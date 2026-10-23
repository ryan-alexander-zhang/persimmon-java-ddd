package com.persimmon.ddd.application;

import java.util.Optional;

/** A fact published to other bounded contexts, serialised as JSON. Must carry {@link EventType}. */
public interface IntegrationEvent {
  /** The entity this event is about; events sharing a subject are delivered in order. */
  default String subject() {
    return null;
  }

  static EventType eventTypeOf(Class<?> type) {
    EventType annotation = type.getAnnotation(EventType.class);
    if (annotation == null || annotation.name().isBlank() || annotation.version() < 1) {
      throw new IllegalStateException(
          type.getName() + " must declare @EventType(name = \"...\", version >= 1)");
    }
    return annotation;
  }

  static Optional<String> destinationOf(Class<?> type) {
    Externalized annotation = type.getAnnotation(Externalized.class);
    return annotation == null ? Optional.empty() : Optional.of(annotation.value());
  }
}
