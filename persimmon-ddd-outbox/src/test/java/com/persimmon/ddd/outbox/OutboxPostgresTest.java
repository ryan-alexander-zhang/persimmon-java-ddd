package com.persimmon.ddd.outbox;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every outbox scenario again, on PostgreSQL. Skipped where there is no Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OutboxTest.App.class)
class OutboxPostgresTest extends OutboxTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void schema(DynamicPropertyRegistry registry) {
    registry.add("spring.sql.init.schema-locations", () -> "classpath:persimmon/db/migration/outbox/postgresql/V1__persimmon_outbox.sql");
  }
}
