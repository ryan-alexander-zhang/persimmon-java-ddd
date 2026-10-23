package com.persimmon.ddd.outbox;

import com.persimmon.ddd.application.IntegrationEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The registered upcasters by source class; applied until no upcaster takes the current class. */
public final class Upcasters {
  public static final Upcasters NONE = new Upcasters(List.of());

  /** One upcaster with its type arguments resolved (a {@code @Bean} lambda keeps them only in its definition). */
  public record Registration(Class<?> from, Class<?> to, EventUpcaster<?, ?> upcaster) {}

  private final Map<Class<?>, EventUpcaster<IntegrationEvent, IntegrationEvent>> bySource = new HashMap<>();

  @SuppressWarnings("unchecked")
  public Upcasters(List<Registration> registrations) {
    for (Registration r : registrations) {
      IntegrationEvent.Contract source = IntegrationEvent.contractOf(r.from());
      IntegrationEvent.Contract target = IntegrationEvent.contractOf(r.to());
      if (!source.type().equals(target.type()) || target.version() <= source.version()) {
        throw new IllegalStateException(
            r.upcaster().getClass().getName() + " must upcast " + source.type() + " v" + source.version()
                + " to a higher version of the same type, not " + target.type() + " v" + target.version());
      }
      if (bySource.put(r.from(), (EventUpcaster<IntegrationEvent, IntegrationEvent>) r.upcaster()) != null) {
        throw new IllegalStateException("two upcasters start from " + r.from().getName());
      }
    }
  }

  public IntegrationEvent apply(IntegrationEvent event) {
    IntegrationEvent current = event;
    for (EventUpcaster<IntegrationEvent, IntegrationEvent> hop; (hop = bySource.get(current.getClass())) != null; ) {
      current = hop.upcast(current);
    }
    return current;
  }
}
