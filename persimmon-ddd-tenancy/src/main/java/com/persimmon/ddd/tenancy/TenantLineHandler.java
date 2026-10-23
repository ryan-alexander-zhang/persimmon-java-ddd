package com.persimmon.ddd.tenancy;

import com.persimmon.ddd.application.TenantContext;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/** Appends {@code tenant_id = <bound tenant>} to statements on the listed tables only. */
public final class TenantLineHandler
    implements com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler {
  private final String column;
  private final Set<String> tables;

  public TenantLineHandler(String column, Iterable<String> tables) {
    this.column = column;
    Set<String> cleaned = new java.util.HashSet<>();
    tables.forEach(t -> cleaned.add(clean(t)));
    this.tables = cleaned.stream().collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Expression getTenantId() {
    return new StringValue(TenantContext.effective().value());
  }

  @Override
  public String getTenantIdColumn() {
    return column;
  }

  @Override
  public boolean ignoreTable(String tableName) {
    String name = clean(tableName);
    int dot = name.lastIndexOf('.');
    return !tables.contains(name) && !tables.contains(dot < 0 ? name : name.substring(dot + 1));
  }

  static String clean(String tableName) {
    return tableName.replaceAll("[`\"\\[\\]]", "").trim().toLowerCase(Locale.ROOT);
  }
}
