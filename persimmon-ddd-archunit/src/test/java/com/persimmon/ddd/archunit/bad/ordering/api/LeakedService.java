package com.persimmon.ddd.archunit.bad.ordering.api;

/** Behaviour in the contract package: another context would compile against this implementation. */
public class LeakedService {
  public String lookup(String id) {
    return id;
  }
}
