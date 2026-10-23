package com.persimmon.ddd.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed state transitions of an aggregate, declared once as a constant. A refusal code is
 * declared per destination state, so a refused move carries a stable {@link ErrorCode}.
 */
public final class Transitions<S> {
  private final Map<S, Set<S>> allowed = new HashMap<>();
  private final Map<S, ErrorCode> refusalCodes = new HashMap<>();

  private Transitions() {}

  public static <S> Transitions<S> of() {
    return new Transitions<>();
  }

  public Transitions<S> allow(S from, S to) {
    allowed.computeIfAbsent(from, key -> new HashSet<>()).add(to);
    return this;
  }

  /** Same as {@link #allow(Object, Object)}; the code is raised when a move into {@code to} is refused. */
  public Transitions<S> allow(S from, S to, ErrorCode refusalCode) {
    refusalCodes.put(to, refusalCode);
    return allow(from, to);
  }

  public boolean permits(S from, S to) {
    return allowed.getOrDefault(from, Set.of()).contains(to);
  }

  public void check(S from, S to) {
    if (!permits(from, to)) {
      throw new IllegalStateTransitionException(refusalCodes.get(to), from, to);
    }
  }
}
