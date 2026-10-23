package com.persimmon.ddd.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.persimmon.ddd.core.ErrorCategory;
import com.persimmon.ddd.core.ErrorCode;
import org.junit.jupiter.api.Test;

class ApplicationExceptionTest {
  private enum Code implements ErrorCode {
    GONE;

    @Override
    public String code() {
      return "test.gone";
    }

    @Override
    public ErrorCategory category() {
      return ErrorCategory.FORBIDDEN;
    }
  }

  @Test
  void uncodedExceptionsFallBackToTheirOwnCategoryAndCodedOnesToTheCodes() {
    assertEquals(ErrorCategory.NOT_FOUND, new EntityNotFoundException("x").category());
    assertEquals(ErrorCategory.CONFLICT, new DuplicateEntityException("x", null).category());
    assertEquals(ErrorCategory.CONFLICT, new ConcurrencyConflictException("x", null).category());
    assertEquals(ErrorCategory.DOMAIN_RULE, new ApplicationException("x").category());
    assertEquals(ErrorCategory.FORBIDDEN, new EntityNotFoundException(Code.GONE, "x").category());
  }
}
