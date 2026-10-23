package com.persimmon.ddd.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Micrometer Tracing implementation; works with the OpenTelemetry and Brave bridges alike. */
public final class MicrometerTraceContextCarrier implements TraceContextCarrier {
  private final Tracer tracer;
  private final Propagator propagator;

  public MicrometerTraceContextCarrier(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public Captured capture() {
    Span current = tracer.currentSpan();
    if (current == null) {
      return Captured.NONE;
    }
    Map<String, String> headers = new HashMap<>();
    propagator.inject(current.context(), headers, Map::put);
    return new Captured(headers.get("traceparent"), headers.get("tracestate"));
  }

  @Override
  public <T> T restore(String traceparent, String tracestate, String spanName, Supplier<T> work) {
    Map<String, String> headers = new HashMap<>();
    if (traceparent != null) {
      headers.put("traceparent", traceparent);
    }
    if (tracestate != null) {
      headers.put("tracestate", tracestate);
    }
    Span span = propagator.extract(headers, Map::get).name(spanName).kind(Span.Kind.CONSUMER).start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      return work.get();
    } catch (RuntimeException e) {
      span.error(e);
      throw e;
    } finally {
      span.end();
    }
  }
}
