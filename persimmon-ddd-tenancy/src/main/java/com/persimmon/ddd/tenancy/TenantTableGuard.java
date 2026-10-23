package com.persimmon.ddd.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Fails startup when a table carries the tenant column but is neither scoped nor exempt: a table
 * nobody decided about is the one that leaks across tenants.
 */
public final class TenantTableGuard {
  private static final Set<String> SYSTEM_SCHEMAS =
      Set.of("information_schema", "pg_catalog", "mysql", "sys", "performance_schema");

  private TenantTableGuard() {}

  public static void verify(DataSource dataSource, String column, List<String> scoped, List<String> exempt) {
    Set<String> decided = new java.util.HashSet<>();
    for (String t : scoped) {
      decided.add(TenantLineHandler.clean(t));
    }
    for (String t : exempt) {
      decided.add(TenantLineHandler.clean(t));
    }
    List<String> undecided = new ArrayList<>();
    String sql = "SELECT table_schema, table_name FROM information_schema.columns WHERE LOWER(column_name) = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, column.toLowerCase(Locale.ROOT));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String schema = rows.getString(1).toLowerCase(Locale.ROOT);
          String table = rows.getString(2).toLowerCase(Locale.ROOT);
          if (SYSTEM_SCHEMAS.contains(schema) || table.startsWith("persimmon_")) {
            continue;
          }
          if (!decided.contains(table) && !decided.contains(schema + "." + table)) {
            undecided.add(schema + "." + table);
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read information_schema to verify tenant tables", e);
    }
    if (!undecided.isEmpty()) {
      throw new IllegalStateException(
          "tables with a '" + column + "' column that are neither in persimmon.ddd.tenancy.tables nor"
              + " exempt-tables: " + undecided.stream().sorted().collect(Collectors.joining(", ")));
    }
  }
}
