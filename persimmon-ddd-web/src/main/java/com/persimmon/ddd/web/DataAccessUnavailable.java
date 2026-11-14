package com.persimmon.ddd.web;

import java.util.function.Predicate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Spring's data-access failures that mean the store could not be reached, not that a statement was
 * wrong. Only built when spring-tx is on the classpath.
 */
final class DataAccessUnavailable implements Predicate<Throwable> {
  @Override
  public boolean test(Throwable t) {
    return t instanceof DataAccessResourceFailureException
        || t instanceof TransientDataAccessResourceException
        || t instanceof QueryTimeoutException
        || t instanceof CannotCreateTransactionException;
  }

  /**
   * The failure a transaction was rolling back for, when the rollback failed too: a lost
   * connection fails both, and only the first says why.
   */
  static Throwable applicationException(Throwable t) {
    return t instanceof TransactionSystemException failed ? failed.getApplicationException() : null;
  }
}
