package com.persimmon.ddd.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every component's scripts for one vendor apply on that vendor, each into its own history, and a second run applies nothing. */
abstract class VendorMigrationsTest {
  @Autowired JdbcTemplate jdbc;

  @Test
  void everyComponentsScriptsApplyOnThisVendor() {
    for (String table : new String[] {"widget", "persimmon_outbox", "persimmon_dead_letter", "persimmon_inbox", "persimmon_idempotency"}) {
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table + " exists and is empty");
    }
    for (String component : new String[] {"outbox", "idempotency"}) {
      int migrations = "idempotency".equals(component) ? 2 : 1;
      String applied = "SELECT COUNT(*) FROM flyway_schema_history_persimmon_" + component + " WHERE type = 'SQL' AND success = TRUE";
      assertEquals(migrations, jdbc.queryForObject(applied, Integer.class), component);
      FlywayComponentsAutoConfiguration.migrateComponents(jdbc.getDataSource());
      assertEquals(migrations, jdbc.queryForObject(applied, Integer.class), component + ": a second run applies nothing");
    }
  }
}
