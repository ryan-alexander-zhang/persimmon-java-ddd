package com.persimmon.ddd.outbox;

import java.util.function.Supplier;

/**
 * Carries the trace across the outbox hop: captured as W3C {@code traceparent}/{@code tracestate}
 * when the row is written, restored as a child span when the relay dispatches it. No tracing on
 * the classpath means {@link #NOOP}.
 */
public interface TraceContextCarrier {
  record Captured(String traceparent, String tracestate) {
    static final Captured NONE = new Captured(null, null);
  }

  Captured capture();

  <T> T restore(String traceparent, String tracestate, String spanName, Supplier<T> work);

  TraceContextCarrier NOOP =
      new TraceContextCarrier() {
        @Override
        public Captured capture() {
          return Captured.NONE;
        }

        @Override
        public <T> T restore(String traceparent, String tracestate, String spanName, Supplier<T> work) {
          return work.get();
        }
      };
}
