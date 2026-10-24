package com.persimmon.ddd.outbox;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every outbox scenario again, on MySQL. Skipped where there is no Docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OutboxTest.App.class)
class OutboxMysqlTest extends OutboxTest {
  @Container @ServiceConnection
  static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4");

  @DynamicPropertySource
  static void schema(DynamicPropertyRegistry registry) {
    registry.add("spring.sql.init.schema-locations", () -> "classpath:persimmon/db/migration/outbox/mysql/V1__persimmon_outbox.sql");
  }
}
