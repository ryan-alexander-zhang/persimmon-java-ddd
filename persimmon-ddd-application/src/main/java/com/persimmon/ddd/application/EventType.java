package com.persimmon.ddd.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The published contract of an {@link IntegrationEvent}: the bounded context that owns it, its
 * name inside that context and its schema version. On the wire the type is {@code
 * <context>.<name>} and the source is {@code <context>}, whether the context runs inside this
 * process or as a service of its own. The Java class name is an implementation detail.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {
  String context();

  String name();

  int version();
}
