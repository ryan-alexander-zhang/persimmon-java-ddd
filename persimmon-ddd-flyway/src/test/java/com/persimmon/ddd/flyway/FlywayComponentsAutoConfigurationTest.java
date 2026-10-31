package com.persimmon.ddd.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
            "flyway_schema_history_persimmon_idempotency",
            "flyway_schema_history_persimmon_outbox",
            "persimmon_dead_letter",
            "persimmon_idempotency",
            "persimmon_inbox",
            "persimmon_outbox",
            "widget"),
        tables);
    String applied = "SELECT COUNT(*) FROM \"flyway_schema_history_persimmon_outbox\" WHERE \"type\" = 'SQL' AND \"success\"";
    assertEquals(1, jdbc.queryForObject(applied, Integer.class));
    FlywayComponentsAutoConfiguration.migrateComponents(jdbc.getDataSource());
    assertEquals(1, jdbc.queryForObject(applied, Integer.class), "a second run applies nothing");
  }

  @Test
  void idempotencyUpgradePreservesExistingClaimsAndResponses() {
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:idempotency-upgrade;DB_CLOSE_DELAY=-1", "sa", "");
    var configuration = Flyway.configure().dataSource(dataSource)
        .locations("classpath:persimmon/db/migration/idempotency/h2")
        .table("flyway_schema_history_persimmon_idempotency");
    assertEquals(1, configuration.target("1").load().migrate().migrationsExecuted);
    JdbcTemplate existing = new JdbcTemplate(dataSource);
    existing.update("""
        INSERT INTO persimmon_idempotency
          (tenant_id, principal, idempotency_key, fingerprint, state, response_status, created_at, expires_at)
        VALUES ('tenant', 'principal', 'pending', 'f', 'PENDING', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
               ('tenant', 'principal', 'complete', 'f', 'COMPLETE', 201, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """);
    Flyway upgraded = configuration.target("latest").load();
    assertEquals(1, upgraded.migrate().migrationsExecuted);
    assertEquals(2, existing.queryForObject("SELECT COUNT(*) FROM persimmon_idempotency WHERE claim_token IS NULL", Integer.class));
    assertEquals("PENDING", existing.queryForObject("SELECT state FROM persimmon_idempotency WHERE idempotency_key = 'pending'", String.class));
    assertEquals(201, existing.queryForObject("SELECT response_status FROM persimmon_idempotency WHERE idempotency_key = 'complete'", Integer.class));
    assertEquals(0, upgraded.migrate().migrationsExecuted, "restarting does not reapply the additive migration");
  }
}
