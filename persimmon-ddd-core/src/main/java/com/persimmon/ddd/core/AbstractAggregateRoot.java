package com.persimmon.ddd.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root base: records domain events, enforces invariants, carries an optimistic-lock
 * version, and is equal to another instance of the same class with the same id.
 */
public abstract class AbstractAggregateRoot<ID extends Identifier> {
  private final transient List<DomainEvent> domainEvents = new ArrayList<>();
  private long version;

  public abstract ID id();

  protected void registerEvent(DomainEvent event) {
    domainEvents.add(event);
  }

  /** Throws a {@link DomainException} carrying the invariant's code and message when it is broken. */
  protected void checkInvariant(Invariant invariant) {
    if (invariant.isBroken()) {
      throw new DomainException(invariant.errorCode(), invariant.message());
    }
  }

  /** A snapshot; later events do not appear in it. */
  public List<DomainEvent> domainEvents() {
    return List.copyOf(domainEvents);
  }

  /** Takes and clears the recorded events in one step. */
  public List<DomainEvent> drainDomainEvents() {
    List<DomainEvent> drained = List.copyOf(domainEvents);
    domainEvents.clear();
    return drained;
  }

  public final long version() {
    return version;
  }

  /** Called by a repository when reconstituting from storage. */
  protected final void restoreVersion(long persistedVersion) {
    if (persistedVersion < 0) {
      throw new IllegalArgumentException("version must not be negative: " + persistedVersion);
    }
    this.version = persistedVersion;
  }

  /** Called by a repository after a successful write. */
  public final void versionAdvanced() {
    version++;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass() || id() == null) {
      return false;
    }
    return Objects.equals(id(), ((AbstractAggregateRoot<?>) other).id());
  }

  @Override
  public final int hashCode() {
    return Objects.hashCode(id());
  }
}
