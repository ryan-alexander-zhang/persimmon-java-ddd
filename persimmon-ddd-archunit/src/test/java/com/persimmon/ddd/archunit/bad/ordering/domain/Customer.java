package com.persimmon.ddd.archunit.bad.ordering.domain;

import com.persimmon.ddd.core.annotation.AggregateRoot;
import com.persimmon.ddd.core.annotation.ValueObject;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Every line here breaks a rule. */
@AggregateRoot
@Component
public class Customer {
  @ValueObject
  public static class Address {
    String city;
  }

  final Order held = null;
  Instant createdAt = Instant.now();

  @AggregateRoot
  public static class Order extends com.persimmon.ddd.core.AbstractAggregateRoot<Id> {
    @Override
    public Id id() {
      return null;
    }

    void bump() {
      versionAdvanced();
    }
  }

  public record Id(String v) implements com.persimmon.ddd.core.Identifier {}
}
