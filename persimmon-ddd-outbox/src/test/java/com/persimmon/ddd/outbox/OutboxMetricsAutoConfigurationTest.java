package com.persimmon.ddd.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.persimmon.ddd.spring.PersimmonDddAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The registry comes from Boot's own metrics auto-configuration, not from a bean the app declares. */
class OutboxMetricsAutoConfigurationTest {
  @AutoConfigurationPackage
  static class App {}

  @Test
  void theRelayReportsToTheRegistryBootAutoConfigures() {
    new ApplicationContextRunner()
        .withUserConfiguration(App.class)
        .withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class,
            PersimmonDddAutoConfiguration.class,
            MetricsAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class,
            OutboxAutoConfiguration.class))
        .withPropertyValues("spring.datasource.url=jdbc:h2:mem:outbox-metrics;DB_CLOSE_DELAY=-1", "persimmon.ddd.outbox.relay-enabled=false")
        .run(context -> assertThat(context).hasSingleBean(MicrometerOutboxMetrics.class));
  }
}
