package com.persimmon.ddd.web;

import java.util.function.Predicate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

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
}
