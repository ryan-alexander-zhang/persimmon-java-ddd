package com.persimmon.ddd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainExceptionTest {
  @Test
  void propertiesKeepTheirOrderAndAreReadOnly() {
    DomainException ex = new DomainException("rejected").with("field", "country").with("limit", 3).with("rules", List.of("BR-36"));
    assertEquals(List.of("field", "limit", "rules"), List.copyOf(ex.properties().keySet()));
    assertEquals(3, ex.properties().get("limit"));
    assertThrows(UnsupportedOperationException.class, () -> ex.properties().put("x", 1));
    assertThrows(NullPointerException.class, () -> ex.with("x", null));
  }

  @Test
  void anExceptionWithoutPropertiesHasNone() {
    assertTrue(new IllegalStateTransitionException(null, "A", "B").properties().isEmpty());
    assertEquals(Map.of(), new DomainException("x").properties());
  }
}
