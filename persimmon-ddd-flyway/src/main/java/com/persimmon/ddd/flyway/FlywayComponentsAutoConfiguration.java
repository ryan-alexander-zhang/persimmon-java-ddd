package com.persimmon.ddd.flyway;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * After Spring Boot's own Flyway has run the application's {@code db/migration}, runs each
 * persimmon component's {@code classpath:persimmon/db/migration/<component>/<vendor>} in its own
 * history table {@code flyway_schema_history_persimmon_<component>}. A module on the classpath
 * gets its tables; {@code persimmon.ddd.flyway.enabled=false} turns this off.
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
@ConditionalOnClass({Flyway.class, FlywayMigrationStrategy.class})
@ConditionalOnProperty(prefix = "persimmon.ddd.flyway", name = "enabled", matchIfMissing = true)
public class FlywayComponentsAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(FlywayComponentsAutoConfiguration.class);
  private static final String BASE = "persimmon/db/migration";

  @Bean
  @ConditionalOnMissingBean
  public FlywayMigrationStrategy persimmonFlywayMigrationStrategy() {
    return flyway -> {
      flyway.migrate();
      migrateComponents(flyway.getConfiguration().getDataSource());
    };
  }

  static void migrateComponents(DataSource dataSource) {
    String vendor = vendorOf(dataSource);
    for (String component : componentsFor(vendor)) {
      String location = "classpath:" + BASE + "/" + component + "/" + vendor;
      int applied =
          Flyway.configure(FlywayComponentsAutoConfiguration.class.getClassLoader())
              .dataSource(dataSource)
              .locations(location)
              .table("flyway_schema_history_persimmon_" + component.replace('-', '_'))
              .baselineOnMigrate(true)
              .baselineVersion("0")
              .load()
              .migrate()
              .migrationsExecuted;
      log.info("persimmon-ddd flyway: {} applied {} migration(s) from {}", component, applied, location);
    }
  }

  private static TreeSet<String> componentsFor(String vendor) {
    TreeSet<String> components = new TreeSet<>();
    Pattern pattern = Pattern.compile(Pattern.quote(BASE) + "/([^/]+)/" + Pattern.quote(vendor) + "/");
    try {
      Resource[] found =
          new PathMatchingResourcePatternResolver(FlywayComponentsAutoConfiguration.class.getClassLoader())
              .getResources("classpath*:" + BASE + "/*/" + vendor + "/*.sql");
      for (Resource resource : found) {
        Matcher matcher = pattern.matcher(resource.getURL().getPath());
        if (matcher.find()) {
          components.add(matcher.group(1));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot scan the classpath for persimmon migrations", e);
    }
    return components;
  }

  static String vendorOf(DataSource dataSource) {
    String product;
    try (Connection connection = dataSource.getConnection()) {
      product = JdbcUtils.commonDatabaseName(connection.getMetaData().getDatabaseProductName());
    } catch (SQLException e) {
      throw new IllegalStateException("cannot determine the database vendor", e);
    }
    String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
    for (String vendor : new String[] {"h2", "postgresql", "mysql"}) {
      if (name.contains(vendor)) {
        return vendor;
      }
    }
    throw new IllegalStateException(
        "persimmon-ddd ships no migrations for '" + product + "'; set persimmon.ddd.flyway.enabled=false"
            + " and apply persimmon/db/migration/*/<closest vendor> yourself");
  }
}
