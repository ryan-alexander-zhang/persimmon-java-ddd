package com.persimmon.ddd.application;

/** A fact published to other bounded contexts, serialised as JSON. Must carry {@link EventType}. */
public interface IntegrationEvent {
  /** The entity this event is about; events sharing a subject are delivered in order. */
  default String subject() {
    return null;
  }

  /** The contract as it travels: {@code type} is {@code <context>.<name>}. */
  record Contract(String context, String name, int version) {
    public String type() {
      return context + "." + name;
    }
  }

  static Contract contractOf(Class<?> type) {
    EventType annotation = type.getAnnotation(EventType.class);
    if (annotation == null
        || annotation.context().isBlank()
        || annotation.context().contains(".")
        || annotation.name().isBlank()
        || annotation.version() < 1) {
      throw new IllegalStateException(
          type.getName() + " must declare @EventType(context = \"<no dots>\", name = \"...\", version >= 1)");
    }
    return new Contract(annotation.context(), annotation.name(), annotation.version());
  }
}
