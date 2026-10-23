package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;

/** Decides whether a dispatch failure is worth retrying. Declare a bean to replace the default. */
@FunctionalInterface
public interface FailureClassifier {
  boolean isPermanent(Throwable error);

  /** Unknown event types and unreadable payloads never get better; everything else is retried. */
  FailureClassifier DEFAULT =
      error -> {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
          if (cause instanceof PermanentDispatchException || cause instanceof JsonProcessingException) {
            return true;
          }
          if (cause.getCause() == cause) {
            break;
          }
        }
        return false;
      };
}
