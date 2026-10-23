package com.persimmon.ddd.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransitionsTest {
  enum Status {
    PENDING,
    CONFIRMED,
    CANCELLED
  }

  enum Code implements ErrorCode {
    NOT_PENDING;

    @Override
    public String code() {
      return "test.not-pending";
    }

    @Override
    public ErrorCategory category() {
      return ErrorCategory.CONFLICT;
    }
  }

  private static final Transitions<Status> RULES =
      Transitions.<Status>of()
          .allow(Status.PENDING, Status.CONFIRMED, Code.NOT_PENDING)
          .allow(Status.PENDING, Status.CANCELLED);

  @Test
  void permitsOnlyDeclaredTransitions() {
    assertTrue(RULES.permits(Status.PENDING, Status.CONFIRMED));
    assertTrue(RULES.permits(Status.PENDING, Status.CANCELLED));
    assertFalse(RULES.permits(Status.CONFIRMED, Status.PENDING));
    assertDoesNotThrow(() -> RULES.check(Status.PENDING, Status.CONFIRMED));
  }

  @Test
  void refusedMoveCarriesTheDestinationCodeIfDeclared() {
    IllegalStateTransitionException refused =
        assertThrows(
            IllegalStateTransitionException.class,
            () -> RULES.check(Status.CANCELLED, Status.CONFIRMED));
    assertSame(Code.NOT_PENDING, refused.errorCode().orElseThrow());
    assertEquals("illegal state transition: CANCELLED -> CONFIRMED", refused.getMessage());
    assertEquals(ErrorCategory.CONFLICT, refused.category());
    assertTrue(
        assertThrows(
                IllegalStateTransitionException.class,
                () -> RULES.check(Status.CONFIRMED, Status.PENDING))
            .errorCode()
            .isEmpty());
    assertEquals(
        ErrorCategory.CONFLICT,
        assertThrows(
                IllegalStateTransitionException.class,
                () -> RULES.check(Status.CONFIRMED, Status.PENDING))
            .category(),
        "uncoded transition refusal is still a conflict");
  }
}
