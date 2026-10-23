package com.persimmon.ddd.outbox;

/** Delivers one relayed message; throw to have it retried or dead-lettered. */
public interface OutboxDispatcher {
  void dispatch(OutboxMessage message);

  /** False for a dispatcher that stays in process; a row with a destination is then dead-lettered. */
  default boolean reachesExternalTargets() {
    return true;
  }
}
