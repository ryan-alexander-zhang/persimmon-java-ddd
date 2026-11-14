package com.persimmon.ddd.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.web.*}. */
@ConfigurationProperties("persimmon.ddd.web")
public record WebProperties(@DefaultValue RequestId requestId, @DefaultValue Unavailable unavailable) {
  /** The request id header; {@code enabled=false} removes the filter. */
  public record RequestId(
      @DefaultValue("true") boolean enabled, @DefaultValue("X-Request-Id") String header) {}

  /**
   * A 503 problem: {@code Retry-After} in whole seconds, and the {@code code} a failure the handler
   * recognises as an unavailable dependency carries (none by default).
   */
  public record Unavailable(@DefaultValue("1s") Duration retryAfter, String code) {}
}
