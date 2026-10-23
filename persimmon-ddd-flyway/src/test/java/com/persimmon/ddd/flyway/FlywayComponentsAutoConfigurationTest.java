package com.persimmon.ddd.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FlywayComponentsAutoConfigurationTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {}

  @Autowired JdbcTemplate jdbc;

  @Test
  void theApplicationsMigrationsAndEveryComponentsTablesAreAppliedEachInItsOwnHistory() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema = 'PUBLIC' ORDER BY 1",
            String.class);
    assertEquals(
        List.of(
            "flyway_schema_history",
            "flyway_schema_history_persimmon_outbox",
            "persimmon_dead_letter",
            "persimmon_inbox",
            "persimmon_outbox",
            "widget"),
        tables);
    String applied = "SELECT COUNT(*) FROM \"flyway_schema_history_persimmon_outbox\" WHERE \"type\" = 'SQL' AND \"success\"";
    assertEquals(1, jdbc.queryForObject(applied, Integer.class));
    FlywayComponentsAutoConfiguration.migrateComponents(jdbc.getDataSource());
    assertEquals(1, jdbc.queryForObject(applied, Integer.class), "a second run applies nothing");
  }
}
