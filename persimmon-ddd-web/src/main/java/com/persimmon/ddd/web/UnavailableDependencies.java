package com.persimmon.ddd.web;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Recognises a failure that means a dependency is unavailable, and when to retry. Shared by
 * {@link WebExceptionHandler} and the filters that answer before MVC, so they agree on what is a 503.
 */
public final class UnavailableDependencies {
  /** The detail every such 503 carries; never the exception's message. */
  public static final String DETAIL = "A dependency this service needs is unavailable; retry later.";

  /**
   * Drivers report a lost connection by SQLState more often than by type: class 08 is the SQL
   * standard's connection exception; 57P01-57P03 are PostgreSQL's shutdown and cannot-connect-now.
   */
  private static final Set<String> UNAVAILABLE_SQL_STATES = Set.of("57P01", "57P02", "57P03");

  private static final Predicate<Throwable> JDBC = t -> t instanceof SQLTransientConnectionException
      || t instanceof SQLNonTransientConnectionException
      || t instanceof SQLTimeoutException
      || t instanceof SQLException sql && sql.getSQLState() != null
          && (sql.getSQLState().startsWith("08") || UNAVAILABLE_SQL_STATES.contains(sql.getSQLState()));

  private final Predicate<Throwable> unavailable;
  private final Function<Throwable, Throwable> origin;
  private final Duration retryAfter;

  /**
   * JDBC failures always count; {@code more} recognises further ones. {@code origin} names the
   * failure one was raised while handling, when that is not its cause (a failed rollback's
   * application exception), else null; its chain is searched too.
   */
  public UnavailableDependencies(Predicate<Throwable> more, Function<Throwable, Throwable> origin, Duration retryAfter) {
    this.unavailable = JDBC.or(more);
    this.origin = origin;
    this.retryAfter = retryAfter;
  }

  /** The first throwable in the cause chain, or an origin's, that means a dependency is unavailable, else null. */
  public Throwable cause(Throwable ex) {
    return cause(ex, 0);
  }

  private Throwable cause(Throwable ex, int depth) {
    for (Throwable t = ex; t != null && depth < 16; depth++, t = t.getCause()) {
      if (unavailable.test(t)) {
        return t;
      }
      Throwable found = cause(origin.apply(t), depth + 1);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The {@code Retry-After} value: whole seconds, rounded up. */
  public String retryAfter() {
    return String.valueOf(Math.ceilDiv(retryAfter.toMillis(), 1000));
  }
}
