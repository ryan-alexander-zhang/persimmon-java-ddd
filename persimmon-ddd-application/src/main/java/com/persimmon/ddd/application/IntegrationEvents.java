package com.persimmon.ddd.application;

/** Publishes an integration event as part of the command's transaction; the outbox delivers it. */
public interface IntegrationEvents {
  void publish(IntegrationEvent event, CommandContext context);
}
