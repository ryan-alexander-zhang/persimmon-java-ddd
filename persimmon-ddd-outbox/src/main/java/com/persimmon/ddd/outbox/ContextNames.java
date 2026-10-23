package com.persimmon.ddd.outbox;

import java.util.List;

/**
 * Names the bounded context a class belongs to: the first package segment under one of the
 * application's base packages, the same convention ArchUnit's bounded-context rule uses. A class
 * that is under none of them, or directly in one, belongs to the deployable ({@code fallback}).
 */
public record ContextNames(List<String> basePackages, String fallback) {
  public String of(String className) {
    for (String base : basePackages) {
      String prefix = base + ".";
      if (className.startsWith(prefix)) {
        String rest = className.substring(prefix.length());
        int dot = rest.indexOf('.');
        if (dot > 0) {
          return rest.substring(0, dot);
        }
      }
    }
    return fallback;
  }
}
