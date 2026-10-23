package com.persimmon.ddd.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.web.*}. */
@ConfigurationProperties("persimmon.ddd.web")
public record WebProperties(@DefaultValue RequestId requestId) {
  /** The request id header; {@code enabled=false} removes the filter. */
  public record RequestId(
      @DefaultValue("true") boolean enabled, @DefaultValue("X-Request-Id") String header) {}
}
