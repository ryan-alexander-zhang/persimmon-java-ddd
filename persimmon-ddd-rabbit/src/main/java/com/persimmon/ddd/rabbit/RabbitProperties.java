package com.persimmon.ddd.rabbit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.rabbit.*}; the broker itself is {@code spring.rabbitmq.*}. */
@ConfigurationProperties("persimmon.ddd.rabbit")
public record RabbitProperties(
    /* How long the relay waits for the broker's confirm of one message; only with spring.rabbitmq.publisher-confirm-type=correlated. */
    @DefaultValue("30s") Duration sendTimeout,
    @DefaultValue Consumer consumer) {
  /* Off by default; a producer-only service needs no consumer. */
  public record Consumer(
      @DefaultValue("false") boolean enabled,
      /* The durable queue this deployable consumes; default the application name, so instances share it. */
      String queue,
      /* Exchange -> routing-key pattern the queue is bound with; the routing key of a message is its event type, so "ordering.*" or "#". */
      @DefaultValue Map<String, String> bindings,
      @DefaultValue Retry retry) {}

  /* Retries before a message goes to <queue>.dlq; permanent failures go at once. */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("1s") Duration initialInterval,
      @DefaultValue("2.0") double multiplier,
      @DefaultValue("10s") Duration maxInterval) {}
}
