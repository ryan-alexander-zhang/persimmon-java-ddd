package com.persimmon.ddd.web;

import com.persimmon.ddd.core.ErrorCode;
import java.util.Map;

/** A bean that gives specific error codes their own problem type, status or title. */
@FunctionalInterface
public interface ProblemCatalog {
  Map<ErrorCode, ProblemDescriptor> overrides();
}
