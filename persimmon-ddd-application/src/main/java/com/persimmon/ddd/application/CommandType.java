package com.persimmon.ddd.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stable wire name for a command that is scheduled ({@link CommandScheduler}). Without it the
 * class name is the wire name, and renaming or moving the class strands the rows already
 * scheduled under the old one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CommandType {
  String value();
}
