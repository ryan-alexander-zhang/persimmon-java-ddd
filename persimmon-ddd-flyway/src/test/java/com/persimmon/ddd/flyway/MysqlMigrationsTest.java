package com.persimmon.ddd.flyway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = FlywayComponentsAutoConfigurationTest.App.class)
class MysqlMigrationsTest extends VendorMigrationsTest {
  @Container @ServiceConnection
  static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4");
}
