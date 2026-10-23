package com.persimmon.ddd.kafka;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.kafka.*}; the broker itself is {@code spring.kafka.*}. */
@ConfigurationProperties("persimmon.ddd.kafka")
public record KafkaProperties(
    /* How long the relay waits for the broker's acknowledgement of one record. */
    @DefaultValue("30s") Duration sendTimeout,
    @DefaultValue Consumer consumer) {
  /* Off by default; a producer-only service needs no consumer. */
  public record Consumer(
      @DefaultValue("false") boolean enabled,
      /* The topics this deployable consumes; events it has no class for are skipped. */
      @DefaultValue List<String> topics,
      /* Consumer group; default the application name, so instances share the topics. */
      String groupId,
      @DefaultValue Retry retry) {}

  /* Retries before a record goes to <topic>.DLT; permanent failures go at once. */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("1s") Duration initialInterval,
      @DefaultValue("2.0") double multiplier,
      @DefaultValue("10s") Duration maxInterval) {}
}
