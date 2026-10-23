package com.persimmon.ddd.kafka;

/** CloudEvents binary-mode attribute headers, plus W3C trace context. */
public final class Headers {
  public static final String ID = "ce_id";
  public static final String SOURCE = "ce_source";
  public static final String SPEC_VERSION = "ce_specversion";
  public static final String TYPE = "ce_type";
  public static final String TIME = "ce_time";
  public static final String SUBJECT = "ce_subject";
  public static final String DATA_SCHEMA_VERSION = "ce_dataschemaversion";
  public static final String TENANT_ID = "ce_tenantid";
  public static final String CORRELATION_ID = "ce_correlationid";
  public static final String CAUSATION_ID = "ce_causationid";
  public static final String TRACEPARENT = "traceparent";
  public static final String TRACESTATE = "tracestate";
  public static final String CONTENT_TYPE = "content-type";

  private Headers() {}
}
