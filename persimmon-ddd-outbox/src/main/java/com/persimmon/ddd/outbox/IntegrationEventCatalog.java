package com.persimmon.ddd.outbox;

import com.persimmon.ddd.application.IntegrationEvent;
import java.util.Optional;

/** Finds the local class for a (type, version) contract, so a payload can be deserialised. */
@FunctionalInterface
public interface IntegrationEventCatalog {
  Optional<Class<? extends IntegrationEvent>> lookup(String type, int version);
}
