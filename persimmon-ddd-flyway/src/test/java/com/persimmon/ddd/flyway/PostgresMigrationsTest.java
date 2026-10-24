package com.persimmon.ddd.flyway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = FlywayComponentsAutoConfigurationTest.App.class)
class PostgresMigrationsTest extends VendorMigrationsTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
}
