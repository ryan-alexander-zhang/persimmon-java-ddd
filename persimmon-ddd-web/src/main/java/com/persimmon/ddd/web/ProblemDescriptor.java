package com.persimmon.ddd.web;

import com.persimmon.ddd.core.ErrorCategory;
import java.util.Map;

/** How a failure appears on the wire: problem type URI, HTTP status, human title. */
public record ProblemDescriptor(String type, int status, String title) {
  private static final Map<ErrorCategory, ProblemDescriptor> BY_CATEGORY =
      Map.of(
          ErrorCategory.DOMAIN_RULE,
              new ProblemDescriptor("/problems/domain-rule-violation", 422, "Domain rule violated"),
          ErrorCategory.NOT_FOUND,
              new ProblemDescriptor("/problems/resource-not-found", 404, "Resource not found"),
          ErrorCategory.CONFLICT,
              new ProblemDescriptor("/problems/resource-conflict", 409, "Resource conflict"),
          ErrorCategory.VALIDATION,
              new ProblemDescriptor("/problems/validation-failed", 400, "Validation failed"),
          ErrorCategory.UNAUTHORIZED, new ProblemDescriptor("/problems/unauthorized", 401, "Unauthorized"),
          ErrorCategory.FORBIDDEN, new ProblemDescriptor("/problems/forbidden", 403, "Forbidden"),
          ErrorCategory.UNEXPECTED, new ProblemDescriptor("about:blank", 500, "Internal Server Error"));

  public ProblemDescriptor {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("status must be an HTTP status, was " + status);
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    type = type == null || type.isBlank() ? "about:blank" : type;
  }

  /** The default descriptor of a category; a {@link ProblemCatalog} overrides it per code. */
  public static ProblemDescriptor of(ErrorCategory category) {
    return BY_CATEGORY.get(category);
  }
}
