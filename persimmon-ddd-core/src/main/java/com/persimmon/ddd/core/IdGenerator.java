package com.persimmon.ddd.core;

/** Supplies new identifier values; implementations live outside the domain. */
@FunctionalInterface
public interface IdGenerator {
  String newId();
}
